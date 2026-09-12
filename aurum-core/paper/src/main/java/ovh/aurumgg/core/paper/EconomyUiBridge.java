package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;

/**
 * Экономика для игрового окна AurumUI.
 *
 * <h2>Что здесь есть и чего здесь нет</h2>
 *
 * <p>Здесь нет ни одного финансового правила: лимиты перевода, задержка,
 * разбор отказа ledger и сами проводки живут в {@link EconomyOperations}, и
 * этот мост только спрашивает. Иначе окно стало бы вторым местом, где
 * записаны правила, — а расходятся такие места всегда.</p>
 *
 * <p>Правила игрового администрирования тоже сюда не переезжают: ставки
 * налогов, комиссии и курсы редактируются из веб-панели. Решение и его
 * причины записаны в {@code docs/aurum-core-architecture.md}.</p>
 *
 * <h2>Снимок никогда не ходит в базу</h2>
 *
 * <p>Companion собирает снимок в главном потоке сервера, поэтому здесь только
 * кэшированные значения: баланс онлайн-игроков Core и так держит свежим, а
 * глобальные показатели обновляет по таймеру. Чужой баланс — единственное,
 * чего в кэше может не быть; его приносит асинхронное действие «выбрать
 * игрока» и кладёт в сессию, откуда снимок уже читает без ожидания.</p>
 *
 * <h2>Права</h2>
 *
 * <p>Проверяются на каждый вызов по текущему состоянию игрока, а не по тому,
 * что прислал клиент: клиентский мод — код на чужой машине, и набор кнопок,
 * который он показывает, ничего не доказывает.</p>
 */
final class EconomyUiBridge {
    /** Сколько живёт выбор администратора, если он про него забыл. */
    private static final long SESSION_TTL_MILLIS = 10 * 60 * 1000L;
    /** Больше этого числа сессий — самое время выбросить протухшие. */
    private static final int SESSION_CLEANUP_THRESHOLD = 256;

    static final String PLAYER_SCOPE = "economy";
    static final String ADMIN_SCOPE = "economy-admin";

    private final AurumCorePlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    EconomyUiBridge(AurumCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ снимок

    List<Map<String, String>> snapshot(Player viewer, String scope) {
        if (!plugin.activeReady()) return List.of(status(viewer, unavailable()));
        if (ADMIN_SCOPE.equals(scope)) return adminSnapshot(viewer);
        if (PLAYER_SCOPE.equals(scope)) return playerSnapshot(viewer);
        return List.of();
    }

    private List<Map<String, String>> playerSnapshot(Player viewer) {
        if (!viewer.hasPermission("aurum.balance")) return List.of(status(viewer, denied()));
        List<Map<String, String>> objects = new ArrayList<>();
        for (CurrencySpec currency : plugin.settings().currencies().values()) {
            Optional<BalanceSnapshot> balance =
                    plugin.cachedBalance(AccountId.player(viewer.getUniqueId()), currency.id());
            objects.add(object("balance:" + currency.id(), "balance", currency.displayName(), Map.of(
                    "titleKey", "screen.aurumui.economy.balance",
                    "currency", currency.id(),
                    "symbol", currency.symbol(),
                    // Пустая строка, а не ноль: «мы ещё не знаем» и «у тебя
                    // ничего нет» — разные утверждения, и второе обиднее.
                    "amount", balance.map(it -> plain(it.balance())).orElse(""),
                    "primary", Boolean.toString(currency.id().equals(plugin.settings().currency().id())))));
        }
        // Коридор и задержка нужны форме перевода: она обязана показать границы
        // до отправки, а не отвечать отказом после.
        objects.add(object("limits", "limits", "", Map.of(
                "titleKey", "screen.aurumui.economy.transfer",
                "enabled", Boolean.toString(plugin.settings().paymentsEnabled() && viewer.hasPermission("aurum.pay")),
                "minimum", plain(plugin.settings().paymentMinimum()),
                "maximum", plain(plugin.settings().paymentMaximum()),
                "cooldown", Long.toString(plugin.settings().paymentCooldownSeconds()),
                "symbol", plugin.settings().currency().symbol())));
        objects.add(status(viewer, null));
        return List.copyOf(objects);
    }

    private List<Map<String, String>> adminSnapshot(Player viewer) {
        if (!viewer.hasPermission("aurum.admin.economy")) return List.of(status(viewer, denied()));
        List<Map<String, String>> objects = new ArrayList<>();
        Session session = session(viewer.getUniqueId());
        CurrencySpec currency = currency(session.currencyId);

        Optional<GlobalEconomySnapshot> snapshot = plugin.cachedGlobalSnapshot(currency.id());
        if (snapshot.isPresent()) {
            GlobalEconomySnapshot global = snapshot.get();
            objects.add(object("treasury", "treasury", currency.displayName(), Map.of(
                    "titleKey", "screen.aurumui.economy.treasury",
                    "currency", currency.id(),
                    "symbol", currency.symbol(),
                    "treasury", plain(global.treasuryBalance()),
                    "supply", plain(global.moneySupply()),
                    "taxes", plain(global.taxesCollected()),
                    // Снимок мог быть собран до последней проводки; честнее
                    // сказать это, чем выдать устаревшее за текущее.
                    "authoritative", Boolean.toString(global.authoritative()))));
        }
        for (CurrencySpec value : plugin.settings().currencies().values()) {
            objects.add(object("currency:" + value.id(), "currency", value.displayName(), Map.of(
                    "titleKey", "screen.aurumui.economy.currency",
                    "currency", value.id(),
                    "symbol", value.symbol(),
                    "selected", Boolean.toString(value.id().equals(currency.id())))));
        }
        // «Найти игрока» — отдельная строка, а не действие на казне: казны
        // может не быть (валюта без глобального снимка), а искать надо всегда.
        objects.add(object("find", "find", "", Map.of("titleKey", "screen.aurumui.economy.find")));
        if (session.targetUuid != null) {
            objects.add(object("target", "target", session.targetName, Map.of(
                    "uuid", session.targetUuid.toString(),
                    "currency", currency.id(),
                    "symbol", currency.symbol(),
                    "amount", session.targetBalance == null ? "" : session.targetBalance,
                    "online", Boolean.toString(Bukkit.getPlayer(session.targetUuid) != null))));
        }
        objects.add(status(viewer, null));
        return List.copyOf(objects);
    }

    // ---------------------------------------------------------------- действия

    /**
     * Выполнить действие окна.
     *
     * <p>Возвращается либо ключ сообщения, либо {@link CompletionStage} с ним —
     * канал Companion понимает оба. Ключ с префиксом {@code error.} канал
     * считает отказом, поэтому отказы называются именно так.</p>
     */
    Object action(Player viewer, String id, String action, Map<String, String> arguments) {
        if (!plugin.activeReady()) return remember(viewer, unavailable());
        // Разбор идёт по действию, а не по id объекта: id приходит от клиента и
        // ничего не удостоверяет. Право на каждое действие проверяется в своей
        // ветке, поэтому назвать чужой id бесполезно.
        return switch (action) {
            case "pay" -> playerAction(viewer, arguments);
            case "select", "find", "clear", "currency", "give", "take", "set" ->
                    adminAction(viewer, action, arguments);
            default -> "error.unknown_action";
        };
    }

    private Object playerAction(Player viewer, Map<String, String> arguments) {
        if (!viewer.hasPermission("aurum.pay")) return remember(viewer, denied());
        OfflinePlayer target = lookup(arguments.get("player"));
        if (target == null) return remember(viewer, new Outcome(false, "player-unknown", Map.of()));
        Optional<BigDecimal> amount = EconomyOperations.parseAmount(
                text(arguments.get("amount")), false, plugin.settings().currency());
        if (amount.isEmpty()) return remember(viewer, invalidAmount(plugin.settings().currency()));
        return plugin.economyOperations()
                .pay(viewer, target, amount.get(), text(arguments.get("reason")))
                .thenApply(outcome -> remember(viewer, Outcome.from(outcome)));
    }

    private Object adminAction(Player viewer, String action, Map<String, String> arguments) {
        if (!viewer.hasPermission("aurum.admin.economy")) return remember(viewer, denied());
        Session session = session(viewer.getUniqueId());
        switch (action) {
            case "select", "find" -> {
                OfflinePlayer target = lookup(arguments.get("player"));
                if (target == null) return remember(viewer, new Outcome(false, "player-unknown", Map.of()));
                return selectTarget(viewer, session, target);
            }
            case "clear" -> {
                session.targetUuid = null;
                session.targetName = "";
                session.targetBalance = null;
                return remember(viewer, new Outcome(true, "economy.cleared", Map.of()));
            }
            case "currency" -> {
                CurrencySpec selected = plugin.settings().currencies().get(lower(arguments.get("id")));
                if (selected == null) {
                    return remember(viewer, new Outcome(false, "currency-unknown",
                            Map.of("id", text(arguments.get("id")))));
                }
                session.currencyId = selected.id();
                // Баланс был посчитан в другой валюте: показывать его дальше
                // значило бы подписать чужое число чужим символом.
                session.targetBalance = null;
                if (session.targetUuid != null) {
                    return refreshTarget(viewer, session, new Outcome(true, "economy.currency", Map.of()));
                }
                return remember(viewer, new Outcome(true, "economy.currency", Map.of()));
            }
            case "give", "take", "set" -> {
                return adjust(viewer, session, action, arguments);
            }
            default -> {
                return "error.unknown_action";
            }
        }
    }

    private Object adjust(Player viewer, Session session, String action, Map<String, String> arguments) {
        if (session.targetUuid == null) return remember(viewer, new Outcome(false, "economy.no-target", Map.of()));
        EconomyOperations.Adjustment operation = EconomyOperations.Adjustment.of(action).orElseThrow();
        CurrencySpec currency = currency(session.currencyId);
        Optional<BigDecimal> amount = EconomyOperations.parseAmount(
                text(arguments.get("amount")), operation == EconomyOperations.Adjustment.SET, currency);
        if (amount.isEmpty()) return remember(viewer, invalidAmount(currency));
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid);
        return plugin.economyOperations()
                .adjust(viewer.getName(), operation, target, currency, amount.get(), text(arguments.get("reason")))
                .thenCompose(outcome -> {
                    // Баланс цели изменился — обновляем его до того, как окно
                    // перерисуется, иначе администратор увидит прежнее число и
                    // решит, что выдача не прошла.
                    if (!outcome.success()) return completed(remember(viewer, Outcome.from(outcome)));
                    return balance(session.targetUuid, currency).thenApply(value -> {
                        session.targetBalance = value.orElse(null);
                        return remember(viewer, Outcome.from(outcome));
                    });
                });
    }

    private CompletionStage<String> selectTarget(Player viewer, Session session, OfflinePlayer target) {
        session.targetUuid = target.getUniqueId();
        session.targetName = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        session.targetBalance = null;
        return refreshTarget(viewer, session, new Outcome(true, "economy.selected",
                Map.of("player", session.targetName)));
    }

    private CompletionStage<String> refreshTarget(Player viewer, Session session, Outcome outcome) {
        UUID target = session.targetUuid;
        return balance(target, currency(session.currencyId)).thenApply(value -> {
            // Пока ходили в базу, администратор мог выбрать другого: записывать
            // тогда нельзя — это был бы баланс одного под именем другого.
            if (target.equals(session.targetUuid)) session.targetBalance = value.orElse(null);
            return remember(viewer, outcome);
        });
    }

    private CompletionStage<Optional<String>> balance(UUID player, CurrencySpec currency) {
        return plugin.activeEconomy().balance(AccountId.player(player), currency.id())
                .thenApply(snapshot -> snapshot.map(it -> plain(it.balance())))
                // Не смогли спросить — пусто, а не ноль: ноль здесь означал бы
                // «у человека пусто», и на этом основании кто-нибудь начислит.
                .exceptionally(error -> Optional.empty());
    }

    // ----------------------------------------------------------------- служебное

    /** Последний ответ операции — строкой состояния окна, вместе с подстановками. */
    private Map<String, String> status(Player viewer, Outcome override) {
        Outcome outcome = override != null ? override : session(viewer.getUniqueId()).lastOutcome;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", "status");
        fields.put("kind", "status");
        fields.put("title", "");
        fields.put("message", outcome == null ? "" : outcome.key());
        fields.put("success", Boolean.toString(outcome == null || outcome.success()));
        if (outcome != null) {
            // Подстановки в переводе позиционные, а карта порядка не имеет.
            // Поэтому порядок объявляется явно — по алфавиту имён — и едет
            // рядом с самими значениями: клиенту нечего угадывать.
            List<String> names = new ArrayList<>(outcome.placeholders().keySet());
            names.sort(String::compareTo);
            fields.put("args", String.join(",", names));
            names.forEach(name -> fields.put("arg." + name, outcome.placeholders().get(name)));
        }
        return Map.copyOf(fields);
    }

    private String remember(Player viewer, Outcome outcome) {
        session(viewer.getUniqueId()).lastOutcome = outcome;
        return outcome.success() ? outcome.key() : "error." + outcome.key();
    }

    private Session session(UUID player) {
        long now = System.currentTimeMillis();
        if (sessions.size() > SESSION_CLEANUP_THRESHOLD) {
            sessions.entrySet().removeIf(entry -> now - entry.getValue().touched > SESSION_TTL_MILLIS);
        }
        Session session = sessions.computeIfAbsent(player, ignored -> new Session());
        session.touched = now;
        return session;
    }

    private CurrencySpec currency(String id) {
        CurrencySpec value = id == null ? null : plugin.settings().currencies().get(id);
        return value == null ? plugin.settings().currency() : value;
    }

    private OfflinePlayer lookup(String name) {
        String value = text(name);
        if (value.isEmpty() || value.length() > 16) return null;
        Player online = Bukkit.getPlayerExact(value);
        return online != null ? online : Bukkit.getServer().getOfflinePlayerIfCached(value);
    }

    private Outcome unavailable() {
        return new Outcome(false, plugin.activeMode() ? "active-not-ready" : "active-only", Map.of());
    }

    private static Outcome denied() { return new Outcome(false, "permission", Map.of()); }

    private static Outcome invalidAmount(CurrencySpec currency) {
        return new Outcome(false, "invalid-amount", Map.of("scale", Integer.toString(currency.scale())));
    }

    private static Map<String, String> object(String id, String kind, String title, Map<String, String> fields) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("kind", kind);
        result.put("title", title);
        result.putAll(fields);
        return Map.copyOf(result);
    }

    private static CompletionStage<String> completed(String value) {
        return CompletableFuture.completedFuture(value);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String lower(String value) { return text(value).toLowerCase(java.util.Locale.ROOT); }
    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    /** Ответ операции в виде, пригодном и для строки состояния, и для ключа канала. */
    private record Outcome(boolean success, String key, Map<String, String> placeholders) {
        static Outcome from(EconomyOperations.Outcome outcome) {
            return new Outcome(outcome.success(), outcome.key(), outcome.placeholders());
        }
    }

    /** Что окно администратора помнит между запросами. */
    private static final class Session {
        private volatile UUID targetUuid;
        private volatile String targetName = "";
        private volatile String targetBalance;
        private volatile String currencyId;
        private volatile Outcome lastOutcome;
        private volatile long touched;
    }
}
