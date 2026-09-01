package ovh.aurumgg.companion.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.companion.core.model.GuildActionOutcome;
import ovh.aurumgg.companion.core.model.GuildInfo;
import ovh.aurumgg.companion.core.model.GuildMembershipInfo;
import java.time.Duration;
import ovh.aurumgg.companion.core.model.GuildBonusInfo;
import ovh.aurumgg.guilds.api.AurumGuildsApi;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildDetail;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildSummary;

/**
 * Гильдии — через их собственный API, а не через их базу.
 *
 * <h2>Почему companion работает мостом</h2>
 *
 * Управлять гильдиями нужно и из панели. Второй HTTP-сервер в плагине гильдий
 * означал бы второй порт наружу, второй секрет в конфиге и второе место, где
 * это можно настроить неправильно. У companion сервер, токен и канал к панели
 * уже есть и уже проверены, поэтому AurumGuilds регистрирует свой Java API в
 * ServicesManager, а companion берёт его оттуда — тем же приёмом, каким он уже
 * работает с Vault, LuckPerms и системой авторизации.
 *
 * Побочный выигрыш: схема таблиц гильдий остаётся внутренним делом AurumGuilds,
 * и companion не приходится держать в конфиге ещё одну пару логин-пароль от
 * чужой базы.
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Классы {@code ovh.aurumgg.guilds.api.*} упоминаются только здесь, и до них
 * дело доходит лишь после проверки {@link #installed()} через PluginManager.
 * На сервере без AurumGuilds класс не загружается, все методы отвечают «пусто»,
 * а панель по этому признаку просто не показывает раздел гильдий.
 *
 * <h2>Провайдера не кэшируем</h2>
 *
 * Плагин гильдий могут перезагрузить на живом сервере, и ссылка на прежний
 * экземпляр означала бы тихо неверные ответы про состав гильдий.
 */
final class GuildsIntegration {

    static final String PLUGIN_NAME = "AurumGuilds";

    /**
     * Сколько ждать ответа плагина гильдий.
     *
     * Ждём в потоке HTTP-сервера, а не в главном, поэтому ожидание здесь
     * безопасно — но ограниченное: подвисший сосед не должен превратить запрос
     * панели в бесконечный.
     */
    private static final long TIMEOUT_SECONDS = 5;

    private GuildsIntegration() {}

    static boolean installed() {
        return provider().isPresent();
    }

    private static Optional<AurumGuildsApi> provider() {
        // Проверка плагина ДО обращения к классу API: иначе на сервере без
        // AurumGuilds getRegistration уронил бы поток NoClassDefFoundError.
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) return Optional.empty();
        try {
            RegisteredServiceProvider<AurumGuildsApi> registration =
                    Bukkit.getServer().getServicesManager().getRegistration(AurumGuildsApi.class);
            return registration == null
                    ? Optional.empty()
                    : Optional.ofNullable(registration.getProvider());
        } catch (NoClassDefFoundError | Exception e) {
            return Optional.empty();
        }
    }

    static List<GuildInfo> guilds(String query, int limit) {
        return provider()
                .flatMap(api -> await(api.guilds(query, limit)))
                .map(list -> list.stream().map(GuildsIntegration::toInfo).toList())
                .orElseGet(List::of);
    }

    static Optional<GuildInfo> guild(long guildId) {
        return provider()
                .flatMap(api -> await(api.guild(guildId)))
                .flatMap(detail -> detail.map(GuildsIntegration::toInfo));
    }

    static Optional<GuildMembershipInfo> membership(UUID playerUuid) {
        // Синхронный метод API: гильдии живут в памяти плагина, и ждать нечего.
        return provider()
                .flatMap(api -> api.membership(playerUuid))
                .map(membership -> new GuildMembershipInfo(
                        membership.guildId(),
                        membership.guildName(),
                        membership.guildTag(),
                        membership.rank().storageName(),
                        membership.joinedAt().toEpochMilli()));
    }

    static Optional<GuildActionOutcome> disband(long guildId, String actor) {
        return provider().flatMap(api -> await(api.adminDisband(guildId, actor))).map(GuildsIntegration::toOutcome);
    }

    static Optional<GuildActionOutcome> transfer(long guildId, String targetName, String actor) {
        return provider()
                .flatMap(api -> await(api.adminTransfer(guildId, targetName, actor)))
                .map(GuildsIntegration::toOutcome);
    }

    static Optional<GuildActionOutcome> removeMember(String targetName, String actor) {
        return provider()
                .flatMap(api -> await(api.adminRemove(targetName, actor)))
                .map(GuildsIntegration::toOutcome);
    }

    // --------------------------------------------------------- внутреннее

    private static <T> Optional<T> await(CompletableFuture<T> future) {
        try {
            return Optional.ofNullable(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Список: состав не заполняем — в строке списка он не нужен. */
    private static GuildInfo toInfo(GuildSummary summary) {
        return new GuildInfo(
                summary.id(),
                summary.name(),
                summary.tag(),
                summary.leaderUuid().toString(),
                summary.leaderName(),
                summary.memberCount(),
                summary.bankBalance(),
                summary.createdAt().toEpochMilli(),
                List.of());
    }

    private static GuildInfo toInfo(GuildDetail detail) {
        GuildSummary summary = detail.summary();
        List<GuildInfo.Member> members = new ArrayList<>(detail.members().size());
        for (GuildMember member : detail.members()) {
            members.add(new GuildInfo.Member(
                    member.uuid().toString(),
                    member.username(),
                    member.rank().storageName(),
                    member.joinedAt().toEpochMilli()));
        }
        return new GuildInfo(
                summary.id(),
                summary.name(),
                summary.tag(),
                summary.leaderUuid().toString(),
                summary.leaderName(),
                summary.memberCount(),
                summary.bankBalance(),
                summary.createdAt().toEpochMilli(),
                List.copyOf(members));
    }

    // ------------------------------------------------------------ бонусы

    static List<GuildBonusInfo> bonuses(long guildId) {
        // Синхронный метод API: бонусы живут в памяти плагина гильдий.
        return provider()
                .map(api -> api.bonuses(guildId).stream().map(GuildsIntegration::toInfo).toList())
                .orElseGet(List::of);
    }

    static Optional<GuildActionOutcome> grantBonus(
            long guildId, String type, double magnitude, long seconds, String actor) {
        Optional<AurumGuildsApi> api = provider();
        if (api.isEmpty()) return Optional.empty();

        BonusType parsed = BonusType.parse(type);
        // Отказ, а не пустота: пустота означала бы «плагина гильдий нет», и
        // панель показала бы не ту причину. Опечатка в виде бонуса — это
        // ошибка запроса, и говорить о ней надо прямо.
        if (parsed == null) {
            return Optional.of(new GuildActionOutcome(false, "Неизвестный вид бонуса: " + type));
        }
        Duration duration = seconds > 0 ? Duration.ofSeconds(seconds) : null;
        return await(api.get().grantBonus(guildId, parsed, magnitude, duration, actor))
                .map(GuildsIntegration::toOutcome);
    }

    static Optional<GuildActionOutcome> revokeBonus(long guildId, String type, String actor) {
        Optional<AurumGuildsApi> api = provider();
        if (api.isEmpty()) return Optional.empty();

        BonusType parsed = BonusType.parse(type);
        if (parsed == null) {
            return Optional.of(new GuildActionOutcome(false, "Неизвестный вид бонуса: " + type));
        }
        return await(api.get().revokeBonus(guildId, parsed, actor)).map(GuildsIntegration::toOutcome);
    }

    private static GuildBonusInfo toInfo(GuildBonus bonus) {
        return new GuildBonusInfo(
                // Вид в нижнем регистре: панель показывает его как есть, а
                // MINING_SPEED в интерфейсе выглядит криком.
                bonus.type().name().toLowerCase(java.util.Locale.ROOT),
                bonus.type().title(),
                bonus.magnitude(),
                bonus.type().kind() == BonusType.Kind.MULTIPLIER,
                // Ноль вместо null: постоянный бонус в JSON проще отличать по
                // нулю, чем по отсутствию поля.
                bonus.expiresAt() == null ? 0L : bonus.expiresAt().toEpochMilli(),
                bonus.grantedBy(),
                bonus.grantedAt().toEpochMilli());
    }

    private static GuildActionOutcome toOutcome(ovh.aurumgg.guilds.api.GuildActionResult result) {
        return new GuildActionOutcome(result.ok(), result.message());
    }
}
