package ovh.aurumgg.guilds.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildActionResult;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildBankEntry;
import ovh.aurumgg.guilds.api.GuildDetail;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildMembership;
import ovh.aurumgg.guilds.api.GuildRank;
import ovh.aurumgg.guilds.api.GuildSettings;
import ovh.aurumgg.guilds.api.GuildSummary;
import ovh.aurumgg.guilds.api.JoinPolicy;

/**
 * Гильдии: вся логика в одном месте и без единой строчки Bukkit.
 *
 * <h2>Почему гильдии живут в памяти</h2>
 *
 * «В какой гильдии этот игрок» спрашивают на каждом сообщении в чат, на каждом
 * ударе (дружественный огонь) и на каждом обновлении сайдбара — то есть
 * десятки раз в секунду. Поход в базу на такой частоте убил бы главный поток
 * сервера. Поэтому база читается один раз при старте, дальше гильдии живут в
 * памяти, а в базу уходят только изменения — и уходят они в рабочем потоке.
 *
 * Цена решения честная: базу НЕ СТОИТ делить между несколькими серверами сети,
 * потому что оповестить соседа об изменении нечем. См. пояснение в
 * {@link MariaDbGuildRepository}.
 *
 * <h2>Как устроены изменения</h2>
 *
 * {@link StoredGuild} неизменяемый. Любое изменение — это сборка новой записи и
 * замена её в карте целиком. Читатель с главного потока всегда видит
 * согласованную гильдию, а не полуприменённое изменение, и никакой блокировки
 * для чтения не нужно. Пишущие операции при этом сериализованы {@code
 * synchronized}: два одновременных вступления в одну гильдию не должны
 * перезаписать друг друга.
 *
 * <h2>Что делает и чего не делает</h2>
 *
 * Здесь правила и тексты сообщений. Права Bukkit, поиск игроков в сети и
 * отправка сообщений — снаружи: этот класс не знает, что такое Player.
 */
public final class GuildService implements AutoCloseable {

    /** Приглашение в гильдию. */
    private record Invite(long guildId, UUID inviter, Instant expiresAt) {}

    /**
     * Не final: /guild admin reload подменяет его целиком.
     *
     * volatile, потому что читают его и основной поток, и рабочий пул. Замена
     * атомарна — меняется ссылка, а не поля внутри, — поэтому операция,
     * начавшаяся со старым конфигом, спокойно им и закончится.
     *
     * Размер пула отсюда берётся ровно один раз, при создании, и перезагрузке
     * не поддаётся: менять число потоков под работающими задачами — способ
     * получить незаметно потерянную операцию с чужими деньгами.
     */
    private volatile GuildsConfig config;
    private final GuildRepository repository;
    private final GuildHooks hooks;
    private final EconomyBridge economy;
    private final NameResolver names;
    private final Logger logger;
    private final Supplier<Instant> clock;
    private final ExecutorService worker;

    /**
     * Бонусы в памяти: id гильдии → её действующие бонусы.
     *
     * Тоже в памяти, и по той же причине, что и сами гильдии: величину
     * спрашивают в обработчике выпадения предметов, то есть на каждом
     * сломанном блоке и каждом убитом мобе. Поход в базу на такой частоте убил
     * бы главный поток вернее, чем что-либо ещё в этом плагине.
     */
    private final Map<Long, List<GuildBonus>> bonuses = new ConcurrentHashMap<>();

    /**
     * Регионы гильдий: id гильдии → её регионы.
     *
     * В памяти по той же причине, что и остальное: список нужен на каждом
     * вступлении и выходе, чтобы поправить состав региона.
     */
    private final Map<Long, List<GuildRegion>> regions = new ConcurrentHashMap<>();

    /** Гильдии в памяти: ключ → неизменяемая запись. */
    private final Map<Long, StoredGuild> guilds = new ConcurrentHashMap<>();
    /** Игрок → его гильдия. Игрок состоит максимум в одной. */
    private final Map<UUID, Long> memberOf = new ConcurrentHashMap<>();
    /** Приглашённый → зовущие его гильдии. */
    private final Map<UUID, List<Invite>> invites = new ConcurrentHashMap<>();

    public GuildService(
            GuildsConfig config,
            GuildRepository repository,
            GuildHooks hooks,
            EconomyBridge economy,
            NameResolver names,
            Logger logger,
            Supplier<Instant> clock) {
        this.config = config;
        this.repository = repository;
        this.hooks = hooks;
        this.economy = economy;
        this.names = names;
        this.logger = logger;
        this.clock = clock;

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "AurumGuilds-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newFixedThreadPool(Math.max(2, config.poolSize()), factory);
    }

    /** Применить перечитанный config.yml. См. поле config. */
    public void applyConfig(GuildsConfig fresh) {
        this.config = fresh;
    }

    /** Прочитать всё из базы в память. Зовётся один раз при старте. */
    public void load() throws Exception {
        for (StoredGuild guild : repository.loadAll()) {
            guilds.put(guild.id(), guild);
            for (GuildMember member : guild.members()) memberOf.put(member.uuid(), guild.id());
        }
        bonuses.putAll(repository.loadBonuses());
        for (GuildRegion region : repository.loadRegions()) {
            regions.computeIfAbsent(region.guildId(), key -> new ArrayList<>()).add(region);
        }
        logger.info("Загружено гильдий: " + guilds.size()
                + ", участников: " + memberOf.size()
                + ", бонусов: " + bonuses.values().stream().mapToInt(List::size).sum());
    }

    // ------------------------------------------------------------- чтение
    //
    // Всё синхронно и из памяти: это спрашивают на каждом тике.

    public Optional<StoredGuild> guildOf(UUID player) {
        Long id = memberOf.get(player);
        return id == null ? Optional.empty() : Optional.ofNullable(guilds.get(id));
    }

    public Optional<StoredGuild> byId(long guildId) {
        return Optional.ofNullable(guilds.get(guildId));
    }

    public Optional<StoredGuild> byName(String name) {
        String key = GuildNames.uniqueKey(name);
        return guilds.values().stream()
                .filter(guild -> GuildNames.uniqueKey(guild.name()).equals(key))
                .findFirst();
    }

    public Optional<GuildMembership> membership(UUID player) {
        return guildOf(player).map(guild -> new GuildMembership(
                guild.id(),
                guild.name(),
                guild.tag(),
                rankOf(guild, player),
                memberOf(guild, player).map(GuildMember::joinedAt).orElse(guild.createdAt())));
    }

    /** Оба в одной гильдии — для дружественного огня. */
    public boolean sameGuild(UUID first, UUID second) {
        Long a = memberOf.get(first);
        Long b = memberOf.get(second);
        return a != null && a.equals(b);
    }

    /** Разрешён ли урон между участниками этой гильдии. */
    public boolean friendlyFireAllowed(UUID player) {
        return guildOf(player).map(guild -> guild.settings().friendlyFire()).orElse(true);
    }

    public List<UUID> memberUuids(long guildId) {
        StoredGuild guild = guilds.get(guildId);
        if (guild == null) return List.of();
        return guild.members().stream().map(GuildMember::uuid).toList();
    }

    /**
     * Имена всех гильдий — для автодополнения команд.
     *
     * Синхронно и из памяти. Асинхронный summaries() здесь не годится:
     * автодополнение вызывается в главном потоке, и join() на его future
     * подвесил бы сервер ровно на время похода в рабочий поток.
     */
    public List<String> guildNames() {
        return guilds.values().stream()
                .map(StoredGuild::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public boolean bankAvailable() {
        return config.bankEnabled() && economy.available();
    }

    public GuildsConfig config() {
        return config;
    }

    public EconomyBridge economy() {
        return economy;
    }

    // ------------------------------------------------- чтение для панели

    public CompletableFuture<List<GuildSummary>> summaries(String query, int limit) {
        // Из памяти, но future — потому что снаружи это часть асинхронного API
        // и вызывающему не должно быть видно, что здесь поход в базу не нужен.
        return CompletableFuture.supplyAsync(() -> {
            String key = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            return guilds.values().stream()
                    .filter(guild -> key.isEmpty()
                            || guild.name().toLowerCase(Locale.ROOT).contains(key)
                            || guild.tag().toLowerCase(Locale.ROOT).contains(key))
                    .sorted(Comparator.comparing(StoredGuild::name, String.CASE_INSENSITIVE_ORDER))
                    .limit(Math.max(1, limit))
                    .map(this::toSummary)
                    .toList();
        }, worker);
    }

    public CompletableFuture<Optional<GuildDetail>> detail(long guildId) {
        return CompletableFuture.supplyAsync(() -> byId(guildId).map(guild ->
                new GuildDetail(toSummary(guild), sortedMembers(guild), guild.settings())), worker);
    }

    public CompletableFuture<List<GuildBankEntry>> bankHistory(long guildId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.bankHistory(guildId, limit);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Не прочитать историю банка гильдии " + guildId, e);
                return List.<GuildBankEntry>of();
            }
        }, worker);
    }

    // ------------------------------------------------------------ команды

    /**
     * Создать гильдию.
     *
     * Тег обязателен уже здесь, а не «потом настроите». Тег — это суффикс,
     * который видят все, и гильдия без него полдня существует безымянной
     * строкой в списке.
     */
    public CompletableFuture<GuildActionResult> create(UUID player, String name, String tag) {
        return async(() -> {
            if (memberOf.containsKey(player)) {
                return GuildActionResult.fail("Сначала выйдите из текущей гильдии: /guild leave");
            }

            GuildNames.Verdict nameCheck = GuildNames.checkName(name, config.maxNameLength());
            if (!nameCheck.ok()) return GuildActionResult.fail(nameCheck.message());
            GuildNames.Verdict tagCheck = GuildNames.checkTag(tag, config.maxTagLength());
            if (!tagCheck.ok()) return GuildActionResult.fail(tagCheck.message());

            if (byName(name).isPresent()) return GuildActionResult.fail("Такое имя уже занято");
            if (byTag(tag).isPresent()) return GuildActionResult.fail("Такой тег уже занят");

            Instant now = clock.get();
            String leaderName = names.nameOf(player);
            GuildSettings settings = GuildSettings.defaults();
            long id;
            try {
                id = repository.createGuild(name, tag, player, leaderName, now, settings);
            } catch (Exception e) {
                // Уникальные ключи в базе ловят гонку двух одновременных
                // созданий, которую проверка выше пропустила бы.
                logger.log(Level.WARNING, "Не создать гильдию «" + name + "»", e);
                return GuildActionResult.fail("Не удалось создать гильдию: имя или тег уже заняты");
            }

            StoredGuild guild = new StoredGuild(id, name, tag, player, 0, now, settings,
                    List.of(new GuildMember(player, leaderName, GuildRank.LEADER, now)));
            guilds.put(id, guild);
            memberOf.put(player, id);
            invites.remove(player);

            hooks.guildCreated(id, tag);
            hooks.memberJoined(id, player);

            logger.info("Гильдия «" + name + "» [" + tag + "] создана игроком " + leaderName);
            return GuildActionResult.ok("Гильдия «" + name + "» [" + tag + "] создана");
        });
    }

    public CompletableFuture<GuildActionResult> invite(UUID actor, UUID target) {
        return async(() -> {
            if (actor.equals(target)) return GuildActionResult.fail("Себя звать некуда");

            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!rankOf(guild, actor).canManageMembers()) {
                return GuildActionResult.fail("Приглашать могут лидер и офицеры");
            }
            if (guild.settings().joinPolicy() == JoinPolicy.CLOSED) {
                return GuildActionResult.fail("Гильдия закрыта — сначала поменяйте это в /guild settings");
            }
            if (memberOf.containsKey(target)) {
                return GuildActionResult.fail(names.nameOf(target) + " уже состоит в гильдии");
            }
            if (guild.members().size() >= config.maxGuildMembers()) {
                return GuildActionResult.fail("В гильдии уже " + config.maxGuildMembers()
                        + " человек — больше не помещается");
            }

            Instant now = clock.get();
            List<Invite> pending = new ArrayList<>(activeInvites(target, now));
            if (pending.stream().anyMatch(invite -> invite.guildId() == guild.id())) {
                return GuildActionResult.fail(names.nameOf(target) + " уже приглашён");
            }
            pending.add(new Invite(guild.id(), actor, now.plus(config.guildInviteTtl())));
            invites.put(target, pending);

            return GuildActionResult.ok(names.nameOf(target) + " приглашён в гильдию");
        });
    }

    /**
     * Вступить.
     *
     * Одна команда на два случая. В открытую гильдию заходят по имени, без
     * приглашения; в остальные — по приглашению, и тогда имя можно не писать.
     * Разводить это на две команды значило бы заставить игрока сначала выяснить,
     * какая из них ему сейчас подходит.
     *
     * @param guildName имя гильдии; null — принять приглашение
     */
    public CompletableFuture<GuildActionResult> join(UUID player, String guildName) {
        return async(() -> {
            if (memberOf.containsKey(player)) {
                return GuildActionResult.fail("Вы уже состоите в гильдии");
            }

            Instant now = clock.get();
            StoredGuild guild;
            if (guildName != null && !guildName.isBlank()) {
                Optional<StoredGuild> found = byName(guildName);
                if (found.isEmpty()) return GuildActionResult.fail("Гильдии с таким именем нет");
                guild = found.get();
                boolean invited = activeInvites(player, now).stream()
                        .anyMatch(invite -> invite.guildId() == guild.id());
                if (guild.settings().joinPolicy() != JoinPolicy.OPEN && !invited) {
                    // Один и тот же текст на «закрыта» и «нужно приглашение»:
                    // разница ничего не даёт тому, кого не позвали.
                    return GuildActionResult.fail("В эту гильдию вступают по приглашению");
                }
            } else {
                List<Invite> pending = activeInvites(player, now);
                if (pending.isEmpty()) return GuildActionResult.fail("Вас никто не звал в гильдию");
                StoredGuild invited = guilds.get(pending.get(pending.size() - 1).guildId());
                if (invited == null) return GuildActionResult.fail("Этой гильдии больше нет");
                guild = invited;
            }

            if (guild.members().size() >= config.maxGuildMembers()) {
                return GuildActionResult.fail("В гильдии нет мест");
            }

            String username = names.nameOf(player);
            GuildMember member = new GuildMember(player, username, GuildRank.MEMBER, now);
            write(() -> repository.addMember(guild.id(), player, username, GuildRank.MEMBER, now),
                    "добавить участника в гильдию " + guild.name());

            List<GuildMember> updated = new ArrayList<>(guild.members());
            updated.add(member);
            replace(guild, withMembers(guild, updated));
            memberOf.put(player, guild.id());
            invites.remove(player);

            hooks.memberJoined(guild.id(), player);
            return GuildActionResult.ok("Вы вступили в гильдию «" + guild.name() + "»");
        });
    }

    public CompletableFuture<GuildActionResult> leave(UUID player) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(player));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (guild.leader().equals(player)) {
                if (guild.members().size() > 1) {
                    // Лидер не уходит, бросив гильдию: у неё остался бы
                    // указатель на человека, которого в ней нет. Выбор между
                    // «передать» и «распустить» — его решение, а не наше.
                    return GuildActionResult.fail("Сначала передайте лидерство "
                            + "(/guild transfer <ник>) или распустите гильдию (/guild disband)");
                }
                // Последний участник он же лидер: гильдия из нуля человек
                // существовать не может, и просить его отдельно её распустить
                // значит требовать лишнюю команду ради очевидного.
                deleteGuild(guild, "последний участник вышел");
                return GuildActionResult.ok("Вы вышли, и гильдия распущена — в ней не осталось никого");
            }

            removeMember(guild, player, "вышел из гильдии");
            return GuildActionResult.ok("Вы вышли из гильдии");
        });
    }

    public CompletableFuture<GuildActionResult> kick(UUID actor, UUID target) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (actor.equals(target)) return GuildActionResult.fail("Чтобы уйти самому, есть /guild leave");
            if (!rankOf(guild, actor).canManageMembers()) {
                return GuildActionResult.fail("Выгонять могут лидер и офицеры");
            }
            if (memberOf(guild, target).isEmpty()) {
                return GuildActionResult.fail(names.nameOf(target) + " не в вашей гильдии");
            }
            // Строго выше по рангу, а не «не ниже»: офицер не выгоняет
            // офицера и тем более лидера. Иначе один поссорившийся офицер за
            // минуту разбирает гильдию по кускам.
            if (rankOf(guild, actor).weight() <= rankOf(guild, target).weight()) {
                return GuildActionResult.fail("Выгнать можно только того, кто ниже вас по рангу");
            }

            removeMember(guild, target, "исключён из гильдии");
            return GuildActionResult.ok(names.nameOf(target) + " исключён из гильдии");
        });
    }

    public CompletableFuture<GuildActionResult> setRank(UUID actor, UUID target, GuildRank rank) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!guild.leader().equals(actor)) {
                return GuildActionResult.fail("Менять ранги может только лидер");
            }
            if (rank == GuildRank.LEADER) {
                return GuildActionResult.fail("Лидерство передаётся отдельно: /guild transfer <ник>");
            }
            if (actor.equals(target)) return GuildActionResult.fail("Свой ранг менять нельзя");
            if (memberOf(guild, target).isEmpty()) {
                return GuildActionResult.fail(names.nameOf(target) + " не в вашей гильдии");
            }
            if (rankOf(guild, target) == rank) {
                return GuildActionResult.fail(names.nameOf(target) + " и так " + rank.title());
            }

            write(() -> repository.updateRank(guild.id(), target, rank),
                    "сменить ранг участника гильдии " + guild.name());
            replace(guild, withMembers(guild, guild.members().stream()
                    .map(member -> member.uuid().equals(target) ? member.withRank(rank) : member)
                    .toList()));
            return GuildActionResult.ok(names.nameOf(target) + " теперь " + rank.title());
        });
    }

    public CompletableFuture<GuildActionResult> transfer(UUID actor, UUID target) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!guild.leader().equals(actor)) {
                return GuildActionResult.fail("Передавать лидерство может только лидер");
            }
            if (actor.equals(target)) return GuildActionResult.fail("Вы и так лидер");
            if (memberOf(guild, target).isEmpty()) {
                return GuildActionResult.fail(names.nameOf(target) + " не в вашей гильдии");
            }

            applyLeader(guild, target);
            return GuildActionResult.ok(names.nameOf(target) + " теперь лидер гильдии");
        });
    }

    public CompletableFuture<GuildActionResult> disband(UUID actor) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!rankOf(guild, actor).canDisband()) {
                return GuildActionResult.fail("Распустить гильдию может только лидер");
            }

            deleteGuild(guild, "распущена лидером " + names.nameOf(actor));
            return GuildActionResult.ok("Гильдия «" + guild.name() + "» распущена");
        });
    }

    // ----------------------------------------------------------- настройки

    public CompletableFuture<GuildActionResult> updateSettings(
            UUID actor, java.util.function.UnaryOperator<GuildSettings> change) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!guild.leader().equals(actor)) {
                return GuildActionResult.fail("Менять настройки гильдии может только лидер");
            }

            GuildSettings updated = change.apply(guild.settings());
            write(() -> repository.updateSettings(guild.id(), updated),
                    "сохранить настройки гильдии " + guild.name());
            replace(guild, new StoredGuild(guild.id(), guild.name(), guild.tag(), guild.leader(),
                    guild.bank(), guild.createdAt(), updated, guild.members()));
            return GuildActionResult.ok("Настройки сохранены");
        });
    }

    /**
     * Сменить тег.
     *
     * Отдельно от остальных настроек: тег уникален среди гильдий и попадает в
     * суффикс, поэтому его нужно и проверить, и не забыть обновить на группе
     * LuckPerms. Обновление идёт ОДИН РАЗ, на группе — участникам оно приходит
     * само, через наследование.
     */
    public CompletableFuture<GuildActionResult> changeTag(UUID actor, String tag) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(actor));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!guild.leader().equals(actor)) {
                return GuildActionResult.fail("Менять тег может только лидер");
            }

            GuildNames.Verdict check = GuildNames.checkTag(tag, config.maxTagLength());
            if (!check.ok()) return GuildActionResult.fail(check.message());
            Optional<StoredGuild> owner = byTag(tag);
            if (owner.isPresent() && owner.get().id() != guild.id()) {
                return GuildActionResult.fail("Такой тег уже занят");
            }

            write(() -> repository.updateTag(guild.id(), tag), "сменить тег гильдии " + guild.name());
            replace(guild, new StoredGuild(guild.id(), guild.name(), tag, guild.leader(),
                    guild.bank(), guild.createdAt(), guild.settings(), guild.members()));
            hooks.tagChanged(guild.id(), tag);
            return GuildActionResult.ok("Тег гильдии теперь [" + tag + "]");
        });
    }

    // ---------------------------------------------------------------- банк

    public CompletableFuture<GuildActionResult> deposit(UUID player, double amount) {
        return async(() -> {
            if (!bankAvailable()) return GuildActionResult.fail("Банк гильдий на сервере недоступен");
            if (!(amount > 0)) return GuildActionResult.fail("Сумма должна быть больше нуля");

            StoredGuild guild = guilds.get(memberOf.get(player));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");

            // Вкладывать может любой участник: это его собственные деньги.
            if (!economy.withdraw(player, amount)) {
                return GuildActionResult.fail("Недостаточно средств");
            }

            double balance = guild.bank() + amount;
            applyBank(guild, balance, player, true, amount);
            return GuildActionResult.ok("В банк гильдии внесено " + economy.format(amount)
                    + ", теперь там " + economy.format(balance));
        });
    }

    public CompletableFuture<GuildActionResult> withdraw(UUID player, double amount) {
        return async(() -> {
            if (!bankAvailable()) return GuildActionResult.fail("Банк гильдий на сервере недоступен");
            if (!(amount > 0)) return GuildActionResult.fail("Сумма должна быть больше нуля");

            StoredGuild guild = guilds.get(memberOf.get(player));
            if (guild == null) return GuildActionResult.fail("Вы не состоите в гильдии");
            if (!guild.settings().bankAccess().allows(rankOf(guild, player))) {
                return GuildActionResult.fail("Снимать из банка может "
                        + guild.settings().bankAccess().title());
            }
            if (guild.bank() < amount) {
                return GuildActionResult.fail("В банке гильдии только " + economy.format(guild.bank()));
            }

            // Сначала списываем с банка и только потом выдаём: обратный порядок
            // при отказе выдачи оставил бы деньги и там, и там.
            double balance = guild.bank() - amount;
            applyBank(guild, balance, player, false, amount);
            if (!economy.deposit(player, amount)) {
                // Выдача не прошла — возвращаем сумму в банк ОБРАТНОЙ ЗАПИСЬЮ,
                // а не правкой прежней. В логе банка должны остаться оба
                // движения: «снял» и «вернулось», иначе разбор через неделю
                // упрётся в снятие, которого на самом деле не было.
                applyBank(guilds.get(guild.id()), guild.bank(), player, true, amount);
                return GuildActionResult.fail("Плагин экономики отказал, деньги остались в банке");
            }
            return GuildActionResult.ok("Снято " + economy.format(amount)
                    + ", в банке осталось " + economy.format(balance));
        });
    }

    // ---------------------------------------------- вмешательство извне

    public CompletableFuture<GuildActionResult> adminDisband(long guildId, String actor) {
        return async(() -> {
            StoredGuild guild = guilds.get(guildId);
            if (guild == null) return GuildActionResult.fail("Такой гильдии нет");
            deleteGuild(guild, "распущена администратором " + actor);
            return GuildActionResult.ok("Гильдия «" + guild.name() + "» распущена");
        });
    }

    public CompletableFuture<GuildActionResult> adminTransfer(
            long guildId, String targetName, String actor) {
        return async(() -> {
            StoredGuild guild = guilds.get(guildId);
            if (guild == null) return GuildActionResult.fail("Такой гильдии нет");
            Optional<GuildMember> target = byUsername(guild, targetName);
            if (target.isEmpty()) {
                return GuildActionResult.fail("В гильдии «" + guild.name() + "» нет игрока " + targetName);
            }
            if (guild.leader().equals(target.get().uuid())) {
                return GuildActionResult.fail(targetName + " и так лидер");
            }

            applyLeader(guild, target.get().uuid());
            logger.info("Лидерство в гильдии «" + guild.name() + "» передано игроку "
                    + target.get().username() + " администратором " + actor);
            return GuildActionResult.ok(target.get().username() + " назначен лидером гильдии «"
                    + guild.name() + "»");
        });
    }

    public CompletableFuture<GuildActionResult> adminRemove(String targetName, String actor) {
        return async(() -> {
            Optional<StoredGuild> found = guilds.values().stream()
                    .filter(guild -> byUsername(guild, targetName).isPresent())
                    .findFirst();
            if (found.isEmpty()) return GuildActionResult.fail(targetName + " не состоит в гильдии");

            StoredGuild guild = found.get();
            UUID target = byUsername(guild, targetName).orElseThrow().uuid();
            String guildName = guild.name();
            forceRemove(guild, target, "исключён администратором " + actor);
            return GuildActionResult.ok(targetName + " исключён из гильдии «" + guildName + "»");
        });
    }

    /**
     * Аккаунт игрока удалён системой авторизации.
     *
     * Работает так же, как административное исключение: если удалённый был
     * лидером, лидерство переходит следующему по старшинству, и только если
     * после этого не осталось никого — гильдия распускается.
     *
     * Плагина авторизации на сервере может не быть; тогда сюда просто никто не
     * зовёт, а убирать таких игроков остаётся администраторским командам. Это
     * рабочее положение дел, а не деградация.
     */
    public CompletableFuture<GuildActionResult> onAccountDeleted(UUID player, String username) {
        return async(() -> {
            StoredGuild guild = guilds.get(memberOf.get(player));
            if (guild == null) return GuildActionResult.ok("Игрок не состоял в гильдии");
            String guildName = guild.name();
            forceRemove(guild, player, "аккаунт " + username + " удалён");
            return GuildActionResult.ok(username + " убран из гильдии «" + guildName + "»");
        });
    }

    /** Обновить ник участника — он мог смениться между заходами. */
    public void touchUsername(UUID player, String username) {
        StoredGuild guild = guilds.get(memberOf.get(player));
        if (guild == null) return;
        Optional<GuildMember> member = memberOf(guild, player);
        if (member.isEmpty() || member.get().username().equals(username)) return;

        worker.execute(() -> {
            synchronized (this) {
                StoredGuild current = guilds.get(guild.id());
                if (current == null) return;
                write(() -> repository.updateUsername(player, username), "обновить ник участника");
                replace(current, withMembers(current, current.members().stream()
                        .map(m -> m.uuid().equals(player) ? m.withUsername(username) : m)
                        .toList()));
            }
        });
    }

    /** Выбросить истёкшие приглашения. */
    // --------------------------------------------------- регионы WorldGuard
    //
    // Здесь только СВЯЗЬ гильдии с регионом. Сам WorldGuard живёт в модуле
    // paper: core о нём не знает и знать не должен — тогда и вся логика
    // гильдий по-прежнему проверяется тестами без единого плагина рядом.

    /** Регионы гильдии. Пусто — ни одного не привязано. */
    public List<GuildRegion> regions(long guildId) {
        return List.copyOf(regions.getOrDefault(guildId, List.of()));
    }

    /** Привязан ли уже этот регион к какой-нибудь гильдии — и к какой. */
    public Optional<Long> regionOwner(String world, String regionId) {
        return regions.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(region -> region.world().equals(world)
                                && region.regionId().equals(regionId)))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Запомнить, что регион принадлежит гильдии.
     *
     * Проверку прав на регион делает вызывающий: кто владелец региона, знает
     * WorldGuard, а core о нём не знает. Здесь только связь.
     */
    public synchronized boolean attachRegion(long guildId, String world, String regionId) {
        if (!guilds.containsKey(guildId)) return false;
        GuildRegion region = new GuildRegion(guildId, world, regionId);
        List<GuildRegion> current = new ArrayList<>(regions.getOrDefault(guildId, List.of()));
        if (current.contains(region)) return true;
        current.add(region);
        regions.put(guildId, List.copyOf(current));
        write(() -> repository.addRegion(region), "привязать регион к гильдии");
        return true;
    }

    /** Забыть привязку. false — такой привязки не было. */
    public synchronized boolean detachRegion(long guildId, String world, String regionId) {
        List<GuildRegion> current = new ArrayList<>(regions.getOrDefault(guildId, List.of()));
        GuildRegion region = new GuildRegion(guildId, world, regionId);
        if (!current.remove(region)) return false;
        regions.put(guildId, List.copyOf(current));
        write(() -> repository.removeRegion(region), "отвязать регион от гильдии");
        return true;
    }

    // ------------------------------------------------------------ бонусы

    /**
     * Действующие бонусы гильдии. Истёкшие сюда не попадают.
     *
     * Фильтр по времени здесь, а не только в уборщике: уборщик ходит раз в
     * минуту, и без проверки бонус продолжал бы действовать до его прихода —
     * то есть срок «до 20:00» на деле означал бы «примерно до 20:01». За
     * деньги это уже обман.
     */
    public List<GuildBonus> bonuses(long guildId) {
        List<GuildBonus> all = bonuses.get(guildId);
        if (all == null || all.isEmpty()) return List.of();
        Instant now = clock.get();
        return all.stream().filter(bonus -> !bonus.expired(now)).toList();
    }

    /** Бонус игрока по его гильдии. Пусто — нет гильдии, нет бонуса или истёк. */
    public Optional<GuildBonus> bonusOf(UUID player, BonusType type) {
        Long guildId = memberOf.get(player);
        if (guildId == null) return Optional.empty();
        Instant now = clock.get();
        List<GuildBonus> all = bonuses.get(guildId);
        if (all == null) return Optional.empty();
        return all.stream()
                .filter(bonus -> bonus.type() == type && !bonus.expired(now))
                .findFirst();
    }

    /**
     * Множитель бонуса или 1.0, если бонуса нет.
     *
     * Отдельным методом ради обработчиков дропа: там нужен именно множитель, и
     * «нет бонуса» обязано означать «умножить на единицу», а не ветку if в
     * каждом слушателе.
     */
    public double multiplier(UUID player, BonusType type) {
        return bonusOf(player, type).map(GuildBonus::magnitude).orElse(1.0);
    }

    /**
     * Выдать или продлить бонус.
     *
     * Величина зажимается в границы вида, а не отвергается: и панель, и
     * торговец-NPC, и команда — три независимых источника, и требовать от
     * каждого одинаковой проверки значит однажды получить «Спешку X» из того,
     * кто проверку забыл.
     */
    public CompletableFuture<GuildActionResult> grantBonus(
            long guildId, BonusType type, double magnitude, Duration duration, String actor) {
        return async(() -> {
            StoredGuild guild = guilds.get(guildId);
            if (guild == null) return GuildActionResult.fail("Такой гильдии нет");
            if (type == null) return GuildActionResult.fail("Не указан вид бонуса");

            double value = Math.max(type.min(), Math.min(type.max(), magnitude));
            Instant now = clock.get();
            Instant expires = duration == null || duration.isZero() || duration.isNegative()
                    ? null
                    : now.plus(duration);
            GuildBonus bonus = new GuildBonus(type, value, expires, actor, now);

            write(() -> repository.saveBonus(guildId, bonus), "сохранить бонус гильдии");
            replaceBonus(guildId, bonus);

            // В лог сервера, а не только в ответ: бонус — это выданное
            // преимущество, и вопрос «откуда у них это» возникает не в момент
            // выдачи, а через неделю.
            logger.info("Бонус " + type.name() + " " + value
                    + (expires == null ? " (постоянно)" : " до " + expires)
                    + " гильдии «" + guild.name() + "» — выдал " + actor);

            return GuildActionResult.ok("Гильдии «" + guild.name() + "» выдан бонус «"
                    + type.title() + "» " + describe(type, value)
                    + (expires == null ? " навсегда" : " на " + humanDuration(duration)));
        });
    }

    /** Снять бонус досрочно. */
    public CompletableFuture<GuildActionResult> revokeBonus(
            long guildId, BonusType type, String actor) {
        return async(() -> {
            StoredGuild guild = guilds.get(guildId);
            if (guild == null) return GuildActionResult.fail("Такой гильдии нет");
            if (type == null) return GuildActionResult.fail("Не указан вид бонуса");
            if (bonuses(guildId).stream().noneMatch(bonus -> bonus.type() == type)) {
                return GuildActionResult.fail("У гильдии нет такого бонуса");
            }

            write(() -> repository.deleteBonus(guildId, type), "снять бонус гильдии");
            List<GuildBonus> left = new ArrayList<>(bonuses.getOrDefault(guildId, List.of()));
            left.removeIf(bonus -> bonus.type() == type);
            bonuses.put(guildId, List.copyOf(left));

            logger.info("Бонус " + type.name() + " снят у гильдии «" + guild.name()
                    + "» — " + actor);
            return GuildActionResult.ok("Бонус «" + type.title() + "» снят");
        });
    }

    /** Величина словами — «×2», «уровень 2». */
    public static String describe(BonusType type, double magnitude) {
        if (type.kind() == BonusType.Kind.EFFECT_LEVEL) {
            return "уровень " + (int) Math.round(magnitude);
        }
        return "×" + HudLines.money(magnitude);
    }

    /** Срок словами. Часы и минуты: секунды в таком сроке никому не нужны. */
    public static String humanDuration(Duration duration) {
        if (duration == null) return "навсегда";
        long minutes = Math.max(1, duration.toMinutes());
        if (minutes < 60) return minutes + " мин";
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours < 24) return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours == 0 ? days + " д" : days + " д " + restHours + " ч";
    }

    private void replaceBonus(long guildId, GuildBonus bonus) {
        List<GuildBonus> current = new ArrayList<>(bonuses.getOrDefault(guildId, List.of()));
        current.removeIf(existing -> existing.type() == bonus.type());
        current.add(bonus);
        bonuses.put(guildId, List.copyOf(current));
    }

    /**
     * Выбросить истёкшие бонусы из памяти и из базы.
     *
     * Само действие бонуса истечение и без уборки прекращает — чтение
     * фильтрует по времени. Уборка нужна, чтобы таблица и память не росли
     * вечно записями, которые уже ничего не значат.
     */
    public void purgeExpiredBonuses() {
        Instant now = clock.get();
        for (Map.Entry<Long, List<GuildBonus>> entry : bonuses.entrySet()) {
            List<GuildBonus> alive = entry.getValue().stream()
                    .filter(bonus -> !bonus.expired(now))
                    .toList();
            if (alive.size() == entry.getValue().size()) continue;

            for (GuildBonus gone : entry.getValue()) {
                if (!gone.expired(now)) continue;
                // Не беда, если не выйдет: из памяти он уже ушёл и действовать
                // перестал, а строка в базе отфильтруется при загрузке по сроку.
                write(() -> repository.deleteBonus(entry.getKey(), gone.type()),
                        "удалить истёкший бонус");
            }
            entry.setValue(alive);
        }
    }

    public synchronized void purgeInvites() {
        Instant now = clock.get();
        invites.entrySet().removeIf(entry -> {
            entry.setValue(activeInvites(entry.getKey(), now));
            return entry.getValue().isEmpty();
        });
    }

    @Override
    public void close() {
        worker.shutdown();
        repository.close();
    }

    // --------------------------------------------------------- внутреннее

    /**
     * Убрать участника, разобравшись с лидерством и роспуском.
     *
     * Единственное место, где гильдия теряет человека помимо его воли — и
     * потому единственное, где записано правило наследования. Оно одинаково и
     * для административного исключения, и для удалённого аккаунта.
     */
    private void forceRemove(StoredGuild guild, UUID player, String reason) {
        boolean wasLeader = guild.leader().equals(player);
        if (!wasLeader) {
            removeMember(guild, player, reason);
            return;
        }

        Optional<GuildMember> heir = successor(guild, player);
        if (heir.isEmpty()) {
            // Лидер был единственным — распускать больше нечего.
            deleteGuild(guild, reason + ", участников не осталось");
            return;
        }
        applyLeader(guild, heir.get().uuid());
        removeMember(guilds.get(guild.id()), player, reason);
    }

    /**
     * Кто наследует лидерство.
     *
     * Сначала старшинство ранга, потом время вступления: офицер важнее
     * участника, а среди равных наследует тот, кто в гильдии дольше. Правило
     * должно быть однозначным — иначе при каждом удалении лидера начинается
     * спор о том, почему выбрали именно этого.
     */
    private Optional<GuildMember> successor(StoredGuild guild, UUID excluding) {
        return guild.members().stream()
                .filter(member -> !member.uuid().equals(excluding))
                .min(Comparator
                        .comparingInt((GuildMember member) -> -member.rank().weight())
                        .thenComparing(GuildMember::joinedAt));
    }

    private void removeMember(StoredGuild guild, UUID player, String reason) {
        write(() -> repository.removeMember(guild.id(), player),
                "убрать участника из гильдии " + guild.name());
        replace(guild, withMembers(guild, guild.members().stream()
                .filter(member -> !member.uuid().equals(player))
                .toList()));
        memberOf.remove(player, guild.id());
        hooks.memberLeft(guild.id(), player);
        logger.info("Гильдия «" + guild.name() + "»: " + names.nameOf(player) + " — " + reason);
    }

    private void applyLeader(StoredGuild guild, UUID newLeader) {
        write(() -> {
            repository.updateLeader(guild.id(), newLeader);
            repository.updateRank(guild.id(), newLeader, GuildRank.LEADER);
            repository.updateRank(guild.id(), guild.leader(), GuildRank.OFFICER);
        }, "передать лидерство в гильдии " + guild.name());

        // Прежний лидер становится офицером, а не участником: он только что
        // управлял гильдией, и понижать его сразу в рядовые — лишняя обида.
        List<GuildMember> updated = guild.members().stream()
                .map(member -> {
                    if (member.uuid().equals(newLeader)) return member.withRank(GuildRank.LEADER);
                    if (member.uuid().equals(guild.leader())) return member.withRank(GuildRank.OFFICER);
                    return member;
                })
                .toList();
        replace(guild, new StoredGuild(guild.id(), guild.name(), guild.tag(), newLeader,
                guild.bank(), guild.createdAt(), guild.settings(), updated));
    }

    private void deleteGuild(StoredGuild guild, String reason) {
        write(() -> repository.deleteGuild(guild.id()), "удалить гильдию " + guild.name());
        for (GuildMember member : guild.members()) {
            memberOf.remove(member.uuid(), guild.id());
            hooks.memberLeft(guild.id(), member.uuid());
        }
        guilds.remove(guild.id());
        // Группу удаляем после того, как убрали из неё всех: LuckPerms не
        // возражает против удаления группы с наследниками, но оставшиеся ноды
        // указывали бы на несуществующую группу.
        hooks.guildDeleted(guild.id());

        // Деньги банка при роспуске никуда не переводятся, и это осознанно:
        // делить общак между бывшими участниками пришлось бы по правилу,
        // которого никто не задавал. Запись в логе остаётся.
        logger.info("Гильдия «" + guild.name() + "» удалена: " + reason);
    }

    private void applyBank(
            StoredGuild guild, double balance, UUID actor, boolean deposit, double amount) {
        String actorName = names.nameOf(actor);
        GuildBankEntry entry = new GuildBankEntry(
                clock.get(), guild.id(), actor, actorName, deposit, amount, balance);
        write(() -> {
            repository.updateBank(guild.id(), balance);
            // Лог пишется той же операцией, что и баланс: запись без лога — это
            // ровно тот случай, ради которого лог и заводят.
            repository.logBank(entry);
        }, "сохранить операцию с банком гильдии " + guild.name());
        replace(guild, new StoredGuild(guild.id(), guild.name(), guild.tag(), guild.leader(),
                balance, guild.createdAt(), guild.settings(), guild.members()));
    }

    private void replace(StoredGuild previous, StoredGuild updated) {
        guilds.put(previous.id(), updated);
        for (GuildMember member : updated.members()) memberOf.put(member.uuid(), updated.id());
    }

    private static StoredGuild withMembers(StoredGuild guild, List<GuildMember> members) {
        return new StoredGuild(guild.id(), guild.name(), guild.tag(), guild.leader(), guild.bank(),
                guild.createdAt(), guild.settings(), List.copyOf(members));
    }

    private Optional<StoredGuild> byTag(String tag) {
        String key = GuildNames.uniqueKey(tag);
        return guilds.values().stream()
                .filter(guild -> GuildNames.uniqueKey(guild.tag()).equals(key))
                .findFirst();
    }

    private static Optional<GuildMember> memberOf(StoredGuild guild, UUID player) {
        return guild.members().stream().filter(member -> member.uuid().equals(player)).findFirst();
    }

    private static Optional<GuildMember> byUsername(StoredGuild guild, String username) {
        return guild.members().stream()
                .filter(member -> member.username().equalsIgnoreCase(username))
                .findFirst();
    }

    private static GuildRank rankOf(StoredGuild guild, UUID player) {
        return memberOf(guild, player).map(GuildMember::rank).orElse(GuildRank.MEMBER);
    }

    private List<Invite> activeInvites(UUID target, Instant now) {
        List<Invite> pending = invites.get(target);
        if (pending == null) return List.of();
        List<Invite> alive = new ArrayList<>(pending.size());
        for (Invite invite : pending) {
            if (invite.expiresAt().isAfter(now) && guilds.containsKey(invite.guildId())) {
                alive.add(invite);
            }
        }
        return alive;
    }

    private GuildSummary toSummary(StoredGuild guild) {
        return new GuildSummary(
                guild.id(),
                guild.name(),
                guild.tag(),
                guild.leader(),
                memberOf(guild, guild.leader()).map(GuildMember::username)
                        .orElseGet(() -> names.nameOf(guild.leader())),
                guild.members().size(),
                guild.bank(),
                guild.createdAt());
    }

    /** Лидер первым, дальше по старшинству и времени вступления. */
    private static List<GuildMember> sortedMembers(StoredGuild guild) {
        return guild.members().stream()
                .sorted(Comparator
                        .comparingInt((GuildMember member) -> -member.rank().weight())
                        .thenComparing(GuildMember::joinedAt))
                .toList();
    }

    /** Операция, которая может бросить проверяемое исключение. */
    @FunctionalInterface
    private interface Change {
        void run() throws Exception;
    }

    /**
     * Записать изменение в базу.
     *
     * Ошибка базы НЕ отменяет изменение в памяти: гильдия уже поменялась для
     * всех, кто её видит, и откатывать это на глазах у игроков хуже, чем
     * разойтись с базой до перезапуска. Ошибка при этом громко пишется в лог —
     * молча расходиться нельзя.
     */
    private void write(Change change, String what) {
        try {
            change.run();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Не удалось " + what + " — в памяти изменение применено, "
                    + "в базе нет. Понадобится перезапуск, чтобы состояния сошлись", e);
        }
    }

    private CompletableFuture<GuildActionResult> async(Supplier<GuildActionResult> action) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try {
                    return action.get();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Ошибка в операции с гильдией", e);
                    return GuildActionResult.fail("Внутренняя ошибка, попробуйте позже");
                }
            }
        }, worker);
    }

    /** Сколько живёт приглашение — для текстов подсказок. */
    public Duration inviteTtl() {
        return config.guildInviteTtl();
    }
}
