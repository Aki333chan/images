package ovh.aurumgg.guilds.paper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import ovh.aurumgg.guilds.api.AurumGuildsApi;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildActionResult;
import ovh.aurumgg.guilds.api.GuildBankEntry;
import ovh.aurumgg.guilds.api.GuildDetail;
import ovh.aurumgg.guilds.api.GuildMembership;
import ovh.aurumgg.guilds.api.GuildSummary;
import ovh.aurumgg.guilds.api.PartyView;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.PartyService;

/**
 * Реализация публичного API — то, что забирает companion из ServicesManager.
 *
 * Класс намеренно тонкий: он только сводит вместе два сервиса и ничего не
 * решает сам. Любое правило, добавленное здесь, было бы правилом, которое
 * действует из панели, но не действует в игре, — а это самый неприятный вид
 * расхождения, потому что обнаруживается он не сразу.
 */
final class BukkitGuildsApi implements AurumGuildsApi {

    private final GuildService guilds;
    private final PartyService parties;
    private final boolean suffixes;

    BukkitGuildsApi(GuildService guilds, PartyService parties, boolean suffixes) {
        this.guilds = guilds;
        this.parties = parties;
        this.suffixes = suffixes;
    }

    @Override
    public CompletableFuture<List<GuildSummary>> guilds(String query, int limit) {
        return guilds.summaries(query, limit);
    }

    @Override
    public CompletableFuture<Optional<GuildDetail>> guild(long guildId) {
        return guilds.detail(guildId);
    }

    @Override
    public Optional<GuildMembership> membership(UUID playerUuid) {
        return guilds.membership(playerUuid);
    }

    @Override
    public Optional<PartyView> party(UUID playerUuid) {
        return parties.view(playerUuid);
    }

    @Override
    public CompletableFuture<List<GuildBankEntry>> bankHistory(long guildId, int limit) {
        return guilds.bankHistory(guildId, limit);
    }

    @Override
    public CompletableFuture<GuildActionResult> adminDisband(long guildId, String actor) {
        return guilds.adminDisband(guildId, actor);
    }

    @Override
    public CompletableFuture<GuildActionResult> adminTransfer(
            long guildId, String targetName, String actor) {
        return guilds.adminTransfer(guildId, targetName, actor);
    }

    @Override
    public CompletableFuture<GuildActionResult> adminRemove(String targetName, String actor) {
        return guilds.adminRemove(targetName, actor);
    }

    // ------------------------------------------------------------ бонусы
    //
    // Просто переадресация в сервис. Он же и следит за границами величин —
    // источников выдачи три (команда, панель, чужой плагин-торговец), и
    // проверка обязана быть одна на всех.

    @Override
    public List<GuildBonus> bonuses(long guildId) {
        return guilds.bonuses(guildId);
    }

    @Override
    public Optional<GuildBonus> bonusOf(UUID playerUuid, BonusType type) {
        return guilds.bonusOf(playerUuid, type);
    }

    @Override
    public CompletableFuture<GuildActionResult> grantBonus(
            long guildId, BonusType type, double magnitude, Duration duration, String actor) {
        return guilds.grantBonus(guildId, type, magnitude, duration, actor);
    }

    @Override
    public CompletableFuture<GuildActionResult> revokeBonus(
            long guildId, BonusType type, String actor) {
        return guilds.revokeBonus(guildId, type, actor);
    }

    @Override
    public boolean bankAvailable() {
        return guilds.bankAvailable();
    }

    @Override
    public boolean suffixesAvailable() {
        return suffixes;
    }
}
