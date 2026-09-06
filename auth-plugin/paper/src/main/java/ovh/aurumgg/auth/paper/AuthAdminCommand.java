package ovh.aurumgg.auth.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.core.AuthAccount;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.HelpBook;
import ovh.aurumgg.auth.core.LoginRecord;

/**
 * Администраторские команды: /auth &lt;подкоманда&gt;.
 *
 * <pre>
 * /auth reset  &lt;ник&gt;  — выдать токен сброса пароля
 * /auth info   &lt;ник&gt;  — что известно об аккаунте
 * /auth unlock &lt;ник&gt;  — снять блокировку по неудачным попыткам
 * /auth logout &lt;ник&gt;  — разавторизовать игрока в сети и погасить сессию
 * /auth history &lt;ник&gt; [24h|3d|7d|30d] — история входов
 * /auth unregister &lt;ник&gt; confirm — снять регистрацию
 * /auth 2fa-off &lt;ник&gt; — выключить двухфакторку потерявшему телефон
 * /auth reload        — перечитать тексты сообщений и подсказок
 * </pre>
 *
 * ОДНА КОМАНДА С ПОДКОМАНДАМИ, А НЕ ПЯТЬ ОТДЕЛЬНЫХ. Пять команд верхнего
 * уровня — это пять шансов столкнуться именами с другим плагином (/info и
 * /reload заняты примерно везде) и пять записей в списке команд сервера.
 *
 * ЧЕГО ЗДЕСЬ НАМЕРЕННО НЕТ — команды «задать игроку пароль». Пароль,
 * переданный аргументом, попадает в историю команд, в логи консоли и в чужие
 * плагины логирования; сброс токеном решает ту же задачу, не создавая
 * долгоживущей копии пароля. Токен для этого и придуман.
 */
final class AuthAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("reset", "info", "unlock", "logout", "history", "unregister", "2fa-off",
                    "reload", "help");

    /** Сколько строк истории показывать за раз: чат больше не вмещает. */
    private static final int HISTORY_LIMIT = 30;

    /** Периоды для /auth history. Ключ — то, что набирает человек. */
    private static final Map<String, Duration> PERIODS = Map.of(
            "1h", Duration.ofHours(1),
            "24h", Duration.ofHours(24),
            "3d", Duration.ofDays(3),
            "7d", Duration.ofDays(7),
            "30d", Duration.ofDays(30));

    private final AurumAuthPlugin plugin;
    private final AuthService service;
    /** Нужен ровно для одного: вернуть подсказку и отсчёт разавторизованному. */
    private final AuthGuardListener guard;

    AuthAdminCommand(AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard) {
        this.plugin = plugin;
        this.service = service;
        this.guard = guard;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("help") || sub.equals("помощь") || sub.equals("?")) {
            usage(sender, args.length > 1 ? args[1] : "1");
            return true;
        }

        if (sub.equals("reload")) {
            if (!allowed(sender, "aurumauth.admin.reload")) return true;
            plugin.reloadMessages();
            sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.reloaded")));
            return true;
        }

        if (args.length < 2) {
            usage(sender);
            return true;
        }
        String name = args[1];

        switch (sub) {
            case "reset" -> reset(sender, name);
            case "info" -> info(sender, name);
            case "unlock" -> unlock(sender, name);
            case "logout" -> logout(sender, name);
            case "history" -> history(sender, name, args.length > 2 ? args[2] : "24h");
            case "unregister" -> unregister(sender, name, args.length > 2 && args[2].equalsIgnoreCase("confirm"));
            case "2fa-off" -> disableTotp(sender, name);
            default -> usage(sender);
        }
        return true;
    }

    /**
     * Выдать токен сброса.
     *
     * Токен уходит ТОЛЬКО тому, кто выполнил команду. Ни broadcast, ни лога с
     * ним нет: это временный ключ от чужого аккаунта, и лишняя его копия —
     * лишний способ им воспользоваться. По той же причине его нет и в тексте
     * ошибки, когда что-то пошло не так.
     */
    private void reset(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.reset")) return;
        service.issueResetToken(name).thenAccept(token -> back(() -> {
            if (token.isEmpty()) {
                sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.noAccount", Map.of("name", name))));
                return;
            }
            long minutes = Math.max(1, Duration.between(Instant.now(), token.get().expiresAt()).toMinutes());
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.reset.token", Map.of(
                            "player", token.get().username(), "token", token.get().token()))));
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.reset.valid", Map.of(
                            "minutes", String.valueOf(minutes), "token", token.get().token()))));
        }));
    }

    private void info(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.info")) return;
        service.lookup(name).thenAccept(found -> back(() -> {
            if (found.isEmpty()) {
                sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.noAccount", Map.of("name", name))));
                return;
            }
            AuthAccount account = found.get();
            sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.info.title", Map.of("player", account.username()))));
            sender.sendMessage(Component.text("  UUID: " + account.uuid()));
            sender.sendMessage(Component.text("  " + plugin.text("admin.info.registered",
                    Map.of("value", String.valueOf(account.registeredAt())))));
            sender.sendMessage(Component.text("  " + plugin.text("admin.info.lastLogin",
                    Map.of("value", account.lastLoginAt() == null
                            ? plugin.text("admin.info.never")
                            : String.valueOf(account.lastLoginAt())))));
            // Адрес последнего входа — сведения о человеке, а не о сервере.
            // Показываем только тому, кому отдельно разрешено их видеть.
            if (sender.hasPermission("aurumauth.admin.info.ip")) {
                sender.sendMessage(Component.text("  " + plugin.text("admin.info.lastIp",
                        Map.of("value", account.lastIp() == null
                                ? plugin.text("admin.info.noData")
                                : account.lastIp()))));
            }

            sender.sendMessage(Component.text("  " + plugin.text("admin.info.totp",
                    Map.of("value", plugin.text(account.hasTotp()
                            ? "admin.info.totpOn" : "admin.info.totpOff")))));

            Optional<AuthStatus> status = onlineStatus(account.username());
            sender.sendMessage(Component.text("  " + plugin.text("admin.info.now",
                    Map.of("value", status.map(Enum::name)
                            .orElse(plugin.text("admin.info.offline"))))));
        }));
    }

    private void unlock(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.unlock")) return;
        service.unlock(name);
        sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.unlocked", Map.of("name", name))));
    }

    private void logout(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.logout")) return;
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.notOnline", Map.of("name", name))));
            return;
        }
        boolean changed = service.forceLogout(player.getUniqueId());
        player.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.loggedOutPlayer")));
        // Возвращаем игрока в то же положение, что и сразу после захода:
        // подсказка на экране, отсчёт до кика, только команды входа.
        guard.onDeauthenticated(player);
        sender.sendMessage(AurumAuthPlugin.prefixed(changed
                ? plugin.text("admin.logout.was", Map.of("name", name))
                : plugin.text("admin.logout.wasNot", Map.of("name", name))));
    }

    /**
     * История входов за период.
     *
     * Показываются и неудачные попытки: серия отказов перед успешным входом —
     * это и есть картина «пароль подбирали, и подобрали». Одни успехи такой
     * картины не дают.
     */
    private void history(CommandSender sender, String name, String periodKey) {
        if (!allowed(sender, "aurumauth.admin.history")) return;
        Duration period = PERIODS.get(periodKey.toLowerCase(Locale.ROOT));
        if (period == null) {
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.history.periods", Map.of(
                            "list", String.join(", ", PERIODS.keySet().stream().sorted().toList())))));
            return;
        }

        service.loginHistory(name, period, HISTORY_LIMIT).thenAccept(records -> back(() -> {
            if (records.isEmpty()) {
                sender.sendMessage(AurumAuthPlugin.prefixed(
                        plugin.text("admin.history.empty", Map.of(
                                "period", periodKey, "name", name))));
                return;
            }
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.history.title", Map.of(
                            "name", name, "period", periodKey,
                            "count", String.valueOf(records.size())))));
            boolean showIp = sender.hasPermission("aurumauth.admin.info.ip");
            for (LoginRecord record : records) {
                sender.sendMessage(Component.text("  " + TIME.format(record.at())
                        + "  " + describe(record.result())
                        // Адрес — сведения о человеке, и он под тем же
                        // отдельным правом, что и в /auth info.
                        + (showIp && record.ip() != null ? "  " + record.ip() : "")
                        + (record.serverId() == null ? "" : "  [" + record.serverId() + "]")));
            }
            if (records.size() == HISTORY_LIMIT) {
                sender.sendMessage(AurumAuthPlugin.prefixed(
                        plugin.text("admin.history.trimmed", Map.of(
                                "limit", String.valueOf(HISTORY_LIMIT)))));
            }
        }));
    }

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    /** Коротко и словами: список читают глазами в чате, а не разбирают кодами. */
    private String describe(LoginRecord.Result result) {
        return switch (result) {
            case SUCCESS -> plugin.text("admin.history.success");
            case WRONG_PASSWORD -> plugin.text("admin.history.wrongPassword");
            case WRONG_CODE -> plugin.text("admin.history.wrongCode");
            case SESSION -> plugin.text("admin.history.session");
            case BYPASS -> plugin.text("admin.history.bypass");
            case RESET -> plugin.text("admin.history.reset");
        };
    }

    /**
     * Снять регистрацию.
     *
     * Требует слова confirm. Действие необратимое, а ник после него
     * освобождается — и занять его сможет уже кто угодно; об этом сказано
     * прямо, потому что как наказание это работает не так, как ожидается.
     */
    private void unregister(CommandSender sender, String name, boolean confirmed) {
        if (!allowed(sender, "aurumauth.admin.unregister")) return;
        if (!confirmed) {
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.unregister.warn", Map.of("name", name))));
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.unregister.freed")));
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    plugin.text("admin.unregister.confirm", Map.of("name", name))));
            return;
        }

        service.unregisterByAdmin(name).thenAccept(removed -> back(() -> {
            if (!removed) {
                sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.noAccount", Map.of("name", name))));
                return;
            }
            sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.unregister.done", Map.of("name", name))));
            Player player = Bukkit.getPlayerExact(name);
            if (player != null) {
                player.kick(Component.text(plugin.text("admin.unregister.kick")));
            }
        }));
    }

    /**
     * Выключить двухфакторку игроку.
     *
     * Нужно, когда телефон потерян: без этого аккаунт становится недоступен
     * навсегда. Это обход второго фактора, пусть и законный, поэтому право
     * отдельное, а сам факт пишется в лог сервера.
     */
    private void disableTotp(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.2fa")) return;
        service.disableTotpByAdmin(name).thenAccept(done -> back(() -> sender.sendMessage(
                AurumAuthPlugin.prefixed(done
                        ? plugin.text("admin.totpOff.done", Map.of("name", name))
                        : plugin.text("admin.totpOff.nothing", Map.of("name", name))))));
    }

    private Optional<AuthStatus> onlineStatus(String username) {
        Player player = Bukkit.getPlayerExact(username);
        return player == null ? Optional.empty() : service.status(player.getUniqueId());
    }

    /** Права проверяются по каждой подкоманде отдельно — они очень разные по весу. */
    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(AurumAuthPlugin.prefixed(plugin.text("admin.noPermission", Map.of("permission", permission))));
        return false;
    }

    /** Вернуться на главный поток: ответы приходят из рабочего пула. */
    private void back(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    /**
     * Справка: строка на подкоманду и что она делает.
     *
     * Раньше это была одна строка с подкомандами через вертикальную черту.
     * Разница между unlock и logout из неё не следует никак, а ошибиться тут
     * дорого: одна снимает блокировку, другая выкидывает игрока обратно на
     * экран входа.
     */
    private void usage(CommandSender sender) {
        usage(sender, "1");
    }

    private void usage(CommandSender sender, String page) {
        HelpBook book = HelpBook.titled(
                        plugin.text("help.admin.title"), "/auth help", plugin.helpLabels())
                .add(plugin.text("help.admin.info.use"), plugin.text("help.admin.info.what"))
                .add(plugin.text("help.admin.reset.use"), plugin.text("help.admin.reset.what"))
                .add(plugin.text("help.admin.unlock.use"), plugin.text("help.admin.unlock.what"))
                .add(plugin.text("help.admin.logout.use"), plugin.text("help.admin.logout.what"))
                .add(plugin.text("help.admin.history.use"), plugin.text("help.admin.history.what"))
                .add(plugin.text("help.admin.totpOff.use"), plugin.text("help.admin.totpOff.what"))
                .add(plugin.text("help.admin.unregister.use"), plugin.text("help.admin.unregister.what"))
                .add(plugin.text("help.admin.reload.use"), plugin.text("help.admin.reload.what"))
                .build();

        AurumAuthPlugin.sendLines(sender, book.page(parsePage(page)));
    }

    /** Не число — первая страница: ругаться за «/auth помощь» незачем. */
    private static int parsePage(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        return prefixed(options(args), args[args.length - 1]);
    }

    /**
     * Что можно набрать на этом месте.
     *
     * Прошлая версия на второй позиции подсказывала ники ДЛЯ ВСЕГО, кроме
     * {@code reload}, — и на {@code /auth help} предлагала выбрать игрока,
     * которого эта команда не принимает вовсе. Такое получается, когда
     * исключения перечисляют списком «кроме»: стоит добавить команду без ника,
     * и её забывают внести.
     *
     * Поэтому теперь наоборот: ники подсказываются только тем подкомандам,
     * которые их действительно принимают.
     *
     * <h2>Почему только те, кто в сети</h2>
     *
     * Половина этих команд работает и с офлайн-игроками — на то они и
     * администраторские. Но {@code getOfflinePlayers()} на живом сервере
     * возвращает всех, кто когда-либо заходил, а это тысячи имён на каждое
     * нажатие Tab в главном потоке. Ник офлайн-игрока администратор всё равно
     * откуда-то берёт — из жалобы или из панели, — и допечатать его руками
     * дешевле, чем подвесить сервер подсказкой.
     */
    private static List<String> options(String[] args) {
        if (args.length <= 1) return SUBCOMMANDS;
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2) {
            if (sub.equals("help")) return List.of("1");
            if (sub.equals("reload")) return List.of();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 3) {
            if (sub.equals("history")) return PERIODS.keySet().stream().sorted().toList();
            // confirm — не украшение: без него команда только предупредит.
            if (sub.equals("unregister")) return List.of("confirm");
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
