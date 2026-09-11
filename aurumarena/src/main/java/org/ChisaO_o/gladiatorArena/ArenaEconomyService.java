package org.ChisaO_o.gladiatorArena;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

/**
 * Деньги арены через AurumCore: резервы, выплаты и восстановление.
 *
 * <h2>Почему деньги живут на счёте арены, а не «нигде»</h2>
 *
 * Раньше ставка просто исчезала со счёта игрока, а касса существовала только
 * как {@code Map} в памяти: сумма пота была числом, за которым не стояло
 * ничего. В ledger так нельзя — у каждой монеты должен быть владелец.
 * Поэтому ставки лежат на {@code ARENA_ESCROW:bet:<арена>}, а финальная касса
 * на {@code ARENA_ESCROW:final:<арена>}: это РАЗНЫЕ счета, и удержания
 * зрителей не смешиваются с призовым пулом.
 *
 * <h2>Комиссия больше не испаряется</h2>
 *
 * Раньше комиссия вычиталась из распределяемой суммы и не доставалась никому:
 * с точки зрения Vault это был невидимый сток денег. Теперь она уходит
 * отдельной проводкой в {@code TREASURY:global} категорией COMMISSION — туда
 * же, куда policy engine по умолчанию отправляет налоги и сборы, и туда, где
 * её видно в {@code /aurum treasury}. Иначе счёт арены не сошёлся бы после
 * выплат.
 *
 * <h2>Почему ставку комиссии считает арена, а не policy engine</h2>
 *
 * У policy engine есть свой тип правила COMMISSION, и общесерверные сборы
 * задаются именно им. Но ставка казино — это правило ИГРЫ, а не фискальное:
 * из неё считаются коэффициенты, которые видит игрок до ставки. Разнести их
 * по двум местам значило бы, что показанный коэффициент и реальная выплата
 * разъезжаются при первой же правке правила. Поэтому процент живёт в конфиге
 * арены, а policy engine остаётся свободен для настоящих налогов сверху.
 *
 * <h2>Порядок, на котором держится безопасность</h2>
 *
 * <ol>
 *   <li>резерв: деньги игрока заблокированы, но ещё его;</li>
 *   <li>билет в журнале — до того, как деньги двинулись;</li>
 *   <li>capture: деньги ушли на счёт арены;</li>
 *   <li>ставка записана в {@code recovery.yml} и в память арены;</li>
 *   <li>билет закрыт.</li>
 * </ol>
 *
 * Падение на любом шаге оставляет ровно одно неоднозначное состояние —
 * «билет есть, ставки нет», — и оно разрешается возвратом. См.
 * {@link #recover()}.
 */
final class ArenaEconomyService implements Listener {

    private final GladiatorArena plugin;
    private final BetJournal journal;
    /** Знает ли арена про уже учтённую ставку — см. {@link #recover()}. */
    private final BiPredicate<String, UUID> betRecorded;
    /** Билеты, по которым восстановление уже идёт: второй раз начинать нельзя. */
    private final Set<UUID> recovering = ConcurrentHashMap.newKeySet();
    /** О чём уже пожаловались в лог: иначе периодический recovery спамит. */
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
    private volatile AurumEconomyApi api;

    ArenaEconomyService(GladiatorArena plugin, BetJournal journal, BiPredicate<String, UUID> betRecorded) {
        this.plugin = plugin;
        this.journal = journal;
        this.betRecorded = betRecorded;
    }

    // ------------------------------------------------------------ Подключение

    boolean hook() {
        try {
            RegisteredServiceProvider<AurumEconomyApi> registration =
                    plugin.getServer().getServicesManager().getRegistration(AurumEconomyApi.class);
            AurumEconomyApi selected = registration == null ? null : registration.getProvider();
            api = selected != null && selected.mode() == EconomyMode.ACTIVE ? selected : null;
        } catch (LinkageError error) {
            // Плагина Core нет вовсе: его классов не будет в classpath, и это
            // нормальный режим — арена умеет работать на предметах.
            api = null;
            plugin.getLogger().warning("AurumCore API недоступен: " + error.getClass().getSimpleName());
        }
        return api != null;
    }

    boolean available() {
        return api != null;
    }

    String currencySymbol() {
        AurumEconomyApi current = api;
        return current == null ? "" : current.primaryCurrency().symbol();
    }

    /** Сумма в точности валюты Core: денежные величины нельзя округлять на глаз. */
    BigDecimal amount(double value) {
        AurumEconomyApi current = api;
        int scale = current == null ? 2 : current.primaryCurrency().scale();
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- Резервы

    /**
     * Зарезервировать списание с игрока.
     *
     * Именно резерв, а не списание: пока арена не записала ставку, деньги
     * должны оставаться игрока. TTL короткий — резерв живёт секунды, а не
     * между перезапусками.
     */
    CompletionStage<HoldResult> reserve(UUID operation, UUID playerId, String arena,
                                        BetTicket.Purpose purpose, double value) {
        AurumEconomyApi current = api;
        if (current == null) return unavailableHold();
        long configured = plugin.getConfig().getLong("economy.hold-ttl-seconds", 120L);
        long ttl = Math.max(10L, Math.min(3600L, configured));
        String key = "arena-" + purpose.name().toLowerCase(Locale.ROOT) + ":" + operation;
        try {
            HoldRequest request = new HoldRequest(key, AccountId.player(playerId), escrow(arena, purpose),
                    current.primaryCurrency().id(), amount(value), TransactionCategory.ARENA_BET,
                    purpose == BetTicket.Purpose.BET ? "arena-bet" : "arena-final", arena,
                    Instant.now().plusSeconds(ttl),
                    // Те же метаданные, что уйдут в capture: Core сверяет их
                    // на полное равенство и иначе откажет.
                    BetTicket.metadata(operation, arena, purpose));
            return current.createHold(request);
        } catch (IllegalArgumentException error) {
            return CompletableFuture.completedFuture(new HoldResult(HoldResult.Status.REJECTED,
                    Optional.empty(), "Недопустимая точность суммы"));
        }
    }

    CompletionStage<HoldResult> capture(BetTicket ticket) {
        AurumEconomyApi current = api;
        return current == null ? unavailableHold() : current.captureHold(ticket.holdId(), ticket.captureRequest());
    }

    CompletionStage<HoldResult> release(BetTicket ticket) {
        AurumEconomyApi current = api;
        return current == null ? unavailableHold() : current.releaseHold(ticket.holdId());
    }

    // --------------------------------------------------------------- Движения

    /**
     * Выплата из кассы арены игроку.
     *
     * Ключ строится из раунда, а не из момента времени: повтор после сбоя
     * обязан попасть в ту же операцию, иначе победителю заплатят дважды.
     */
    CompletionStage<TransactionResult> payout(String arena, UUID round, UUID playerId,
                                              BetTicket.Purpose source, double value) {
        return move(escrow(arena, source), AccountId.player(playerId), value,
                TransactionCategory.ARENA_PAYOUT,
                "arena-payout:" + source.name().toLowerCase(Locale.ROOT) + ":" + round + ":" + playerId,
                arena);
    }

    /** Возврат накопленной ставки: отмена игроком, ничья, остановка арены. */
    CompletionStage<TransactionResult> refundStake(String arena, UUID round, UUID playerId, double value) {
        return move(escrow(arena, BetTicket.Purpose.BET), AccountId.player(playerId), value,
                TransactionCategory.REFUND, "arena-refund:" + round + ":" + playerId, arena);
    }

    /** Возврат одного незавершённого списания — по билету, а не по ставке. */
    CompletionStage<TransactionResult> refundTicket(BetTicket ticket) {
        AurumEconomyApi current = api;
        if (current == null) return unavailableTransaction(ticket.refundKey(), ticket.reservedDebit());
        // Возвращаем полное списание, а не саму ставку: надбавки policy тоже
        // ушли со счёта игрока, и оставить их себе было бы кражей.
        return current.transfer(new TransactionRequest(ticket.refundKey(), ticket.escrow(),
                AccountId.player(ticket.playerId()), ticket.currencyId(), ticket.reservedDebit(),
                TransactionCategory.REFUND, ticket.metadata()));
    }

    /** Комиссия казино — реальной проводкой в казну, а не вычитанием из выплат. */
    CompletionStage<TransactionResult> commission(String arena, UUID round, double value) {
        return move(escrow(arena, BetTicket.Purpose.BET), AccountId.globalTreasury(), value,
                TransactionCategory.COMMISSION, "arena-commission:" + round, arena);
    }

    /**
     * Повторить отложенную выплату ровно тем же переводом.
     *
     * Тот же ключ, та же касса, та же сумма. Если операция уже проводилась,
     * Core вернёт DUPLICATE и второй раз не заплатит; если нет — заплатит
     * сейчас. Категория берётся из назначения: возврат остаётся возвратом,
     * выплата — выплатой, и в аудите это видно раздельно.
     */
    CompletionStage<TransactionResult> repeat(String key, UUID playerId, double value,
                                              String arena, BetTicket.Purpose source) {
        TransactionCategory category = key.startsWith("arena-refund:")
                ? TransactionCategory.REFUND : TransactionCategory.ARENA_PAYOUT;
        return move(escrow(arena, source), AccountId.player(playerId), value, category, key, arena);
    }

    private CompletionStage<TransactionResult> move(AccountId from, AccountId to, double value,
                                                    TransactionCategory category, String key, String arena) {
        AurumEconomyApi current = api;
        BigDecimal sum = amount(value);
        if (current == null) return unavailableTransaction(key, sum);
        if (sum.signum() <= 0) {
            return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.SUCCESS,
                    key, sum, sum, BigDecimal.ZERO.setScale(sum.scale()), "Нулевая сумма"));
        }
        return current.transfer(new TransactionRequest(key, from, to, current.primaryCurrency().id(), sum,
                category, Map.of("plugin", "AurumArena", "arena", arena)));
    }

    private static AccountId escrow(String arena, BetTicket.Purpose purpose) {
        return new AccountId(AccountType.ARENA_ESCROW,
                purpose.name().toLowerCase(Locale.ROOT) + ":" + arena);
    }

    // ---------------------------------------------------------- Восстановление

    /**
     * Разобрать билеты, пережившие сбой.
     *
     * Билет означает «списание начали, ставку не учли». Что с ним делать,
     * решает СОСТОЯНИЕ РЕЗЕРВА в Core, а не догадки:
     *
     * <ul>
     *   <li>резерв ещё держится — снять его, деньги никуда не уходили;</li>
     *   <li>резерв зафиксирован — деньги на счёте арены, вернуть игроку;</li>
     *   <li>резерва нет вовсе (истёк) — закрыть билет.</li>
     * </ul>
     *
     * Отдельная проверка на уже учтённую ставку обязательна. Между записью
     * ставки в {@code recovery.yml} и закрытием билета есть окно; попав в
     * него, мы увидели бы и билет, и ставку — и вернули бы деньги дважды:
     * один раз по билету, другой при возврате накопленной ставки на старте.
     */
    void recover() {
        if (!available() && !hook()) return;
        for (BetTicket ticket : journal.all()) {
            if (!recovering.add(ticket.operation())) continue;
            if (ticket.purpose() == BetTicket.Purpose.BET
                    && betRecorded.test(ticket.arena(), ticket.playerId())) {
                journal.close(ticket);
                recovering.remove(ticket.operation());
                continue;
            }
            AurumEconomyApi current = api;
            if (current == null) {
                recovering.remove(ticket.operation());
                continue;
            }
            current.hold(ticket.holdKey()).whenComplete((hold, error) -> {
                if (error != null || hold == null) {
                    recovering.remove(ticket.operation());
                    return;
                }
                if (hold.isEmpty()) {
                    journal.close(ticket);
                    recovering.remove(ticket.operation());
                    warned.remove(ticket.operation());
                    return;
                }
                HoldSnapshot snapshot = hold.get();
                if (snapshot.status() == HoldSnapshot.Status.CAPTURED) {
                    refundTicket(ticket).whenComplete((result, failure) -> finishTransfer(ticket, result, failure));
                } else if (snapshot.status() == HoldSnapshot.Status.HELD) {
                    release(ticket).whenComplete((result, failure) -> finishRelease(ticket, result, failure));
                } else {
                    journal.close(ticket);
                    recovering.remove(ticket.operation());
                    warned.remove(ticket.operation());
                }
            });
        }
    }

    private void finishTransfer(BetTicket ticket, TransactionResult result, Throwable error) {
        recovering.remove(ticket.operation());
        if (error == null && result != null && (result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE)) {
            journal.close(ticket);
            warned.remove(ticket.operation());
            return;
        }
        if (warned.add(ticket.operation())) {
            String status = result == null ? "нет ответа" : result.status() + ": " + result.message();
            plugin.getLogger().log(Level.SEVERE,
                    "Не удалось вернуть незавершённое списание " + ticket.operation() + " (" + status + ")", error);
        }
    }

    private void finishRelease(BetTicket ticket, HoldResult result, Throwable error) {
        recovering.remove(ticket.operation());
        if (error == null && result != null && result.status() != HoldResult.Status.UNAVAILABLE) {
            journal.close(ticket);
            warned.remove(ticket.operation());
        }
    }

    // ------------------------------------------------------- События сервисов

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (available() || event.getProvider().getService() != AurumEconomyApi.class) return;
        if (hook()) {
            plugin.getLogger().info("Экономика AurumCore доступна: денежный режим арены включён.");
            recover();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (!available() || event.getProvider().getService() != AurumEconomyApi.class
                || api != event.getProvider().getProvider()) return;
        plugin.getLogger().warning("Экономика AurumCore отключена: ставки и восстановление приостановлены.");
        api = null;
    }

    private static CompletionStage<HoldResult> unavailableHold() {
        return CompletableFuture.completedFuture(new HoldResult(HoldResult.Status.UNAVAILABLE,
                Optional.empty(), "Резервы AurumCore недоступны"));
    }

    private static CompletionStage<TransactionResult> unavailableTransaction(String key, BigDecimal amount) {
        return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.UNAVAILABLE,
                key, amount, amount, BigDecimal.ZERO.setScale(amount.scale()), "AurumCore недоступен"));
    }
}
