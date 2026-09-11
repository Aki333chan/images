package org.ChisaO_o.gladiatorArena;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.*;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Transformation;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

public final class GladiatorArena extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private String PREFIX = "§6[Arena] §r";
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, Arena> pendingRespawns = new HashMap<>();
    private final Map<UUID, Arena> spectators = new HashMap<>();
    private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
    private final Map<UUID, Scoreboard> arenaScoreboards = new HashMap<>();
    private final Map<UUID, List<String>> arenaScoreboardLines = new HashMap<>();
    private final Map<UUID, String> arenaUiOwners = new HashMap<>();
    /**
     * Денежный режим ставок.
     *
     * Ключ {@code economy.use_vault} остался прежним ради старых конфигов, но
     * Vault за ним больше не стоит: деньги идут через AurumCore. Переименовать
     * ключ значило бы сломать все существующие настройки ради названия.
     */
    private boolean useVault;
    private BetJournal betJournal;
    private ArenaEconomyService money;
    /** Кто уже жмёт кнопку ставки: резерв в Core не мгновенный. */
    private final Set<UUID> pendingWagers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String vaultSymbol;
    private double vaultBetStep;
    private Material mainCurrency;
    private Material subCurrency;
    private int bettingSeconds;
    private int countdownSeconds;
    private boolean combatLogLoss;
    private boolean boundaryLoss;
    private double commissionPercent;
    private double minBet;
    private double maxBet;
    private int maxTeamLimit;
    private int defaultWinnerExperience;
    private int defaultFinalWinnerExperience;
    private ExperienceMode defaultExperienceMode;
    private boolean spectatorsEnabled;
    private boolean sidebarHideScores;
    private boolean sidebarFormatWarningLogged;
    private boolean hologramsSeeThrough;
    private float hologramViewDistanceBlocks;
    private float bettingHologramScale;
    private String finalStatsFormat;
    private File kitsFile;
    private YamlConfiguration kits;
    private RecoveryStore recovery;
    private DatabaseManager database;
    private NamespacedKey hologramKey;
    private NamespacedKey guiActionKey;
    private ArenaLocale locales;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("GladiatorArena") != null) {
            getLogger().severe("Remove the old GladiatorArena JAR before enabling AurumArena.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            if (LegacyDataMigration.migrate(new File(getDataFolder().getParentFile(), "GladiatorArena").toPath(), getDataFolder().toPath())) {
                getLogger().info("Imported GladiatorArena data into AurumArena; the old folder was preserved.");
            }
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Data migration failed; AurumArena will not start with empty data.", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        locales = new ArenaLocale(this);
        getDataFolder().mkdirs();
        // Keep persisted entity identifiers compatible with existing worlds.
        hologramKey = Objects.requireNonNull(NamespacedKey.fromString("gladiatorarena:hologram"));
        guiActionKey = new NamespacedKey(this, "gui_action");
        recovery = new RecoveryStore(this);
        betJournal = new BetJournal(this);
        // Предикат нужен журналу, чтобы не вернуть деньги дважды: билет, чья
        // ставка уже в recovery.yml, закрывается молча.
        money = new ArenaEconomyService(this, betJournal, recovery::hasBet);
        Bukkit.getPluginManager().registerEvents(money, this);
        loadSettings();
        loadKits();
        connectEconomy();
        database = new DatabaseManager(this);
        database.start();
        loadArenas();
        recoverInterruptedBets();
        PluginCommand arenaCommand = Objects.requireNonNull(getCommand("arena"), "arena command missing");
        arenaCommand.setExecutor(this);
        arenaCommand.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new GladiatorPlaceholders(this, "aurumarena").register();
            new GladiatorPlaceholders(this, "gladiatorarena").register();
            getLogger().info("PlaceholderAPI подключён.");
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 10L, 10L);
        getLogger().info("AurumArena " + getDescription().getVersion() + " enabled. API 26.2, Java "
                + Runtime.version().feature() + ".");
    }

    @Override
    public void onDisable() {
        if (recovery == null) return;
        for (Arena arena : new ArrayList<>(arenas.values())) arena.shutdown();
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) leaveSpectator(player, false);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreParticipantInventory(player, "выключение плагина");
            restoreScoreboard(player);
            player.removeMetadata("aurumui.arena", this);
        }
        arenaUiOwners.clear();
        if (database != null) database.close();
    }

    private void loadSettings() {
        reloadConfig();
        if (locales != null) locales.reload();
        PREFIX = locales.text("messages.prefix");
        useVault = getConfig().getBoolean("economy.use_vault", false);
        vaultSymbol = getConfig().getString("economy.vault_symbol", "$");
        vaultBetStep = finite(getConfig().getDouble("economy.min_vault_bet", 3.0), 3.0, 0.01, 1_000_000_000.0);
        mainCurrency = material(getConfig().getString("economy.main_currency"), Material.GOLD_INGOT);
        subCurrency = material(getConfig().getString("economy.sub_currency"), Material.GOLD_NUGGET);
        bettingSeconds = clamp(getConfig().getInt("settings.betting_seconds", 45), 5, 3600);
        countdownSeconds = clamp(getConfig().getInt("settings.countdown_seconds", 5), 1, 60);
        combatLogLoss = getConfig().getBoolean("settings.combat_log_is_loss", true);
        boundaryLoss = getConfig().getBoolean("settings.boundary_leave_is_loss", true);
        commissionPercent = finite(getConfig().getDouble("settings.casino_commission_percent", 0.0), 0.0, 0.0, 90.0);
        minBet = finite(getConfig().getDouble("settings.min_bet", 0.1), 0.1, 0.01, 1_000_000_000.0);
        maxBet = finite(getConfig().getDouble("settings.max_bet_per_player", 1_000_000.0), 1_000_000.0, minBet, 1_000_000_000.0);
        maxTeamLimit = clamp(getConfig().getInt("settings.max_team_size_limit", 20), 1, 100);
        defaultWinnerExperience = clamp(getConfig().getInt("settings.rewards.winner_experience", 0), 0, 1_000_000);
        defaultFinalWinnerExperience = clamp(getConfig().getInt("settings.rewards.final_winner_experience", 0), 0, 1_000_000);
        defaultExperienceMode = ExperienceMode.parse(getConfig().getString("settings.rewards.experience_mode", "points"));
        sidebarHideScores = getConfig().getBoolean("settings.sidebar.hide_scores", true);
        hologramsSeeThrough = getConfig().getBoolean("settings.holograms.see_through_walls", false);
        hologramViewDistanceBlocks = (float) finite(getConfig().getDouble("settings.holograms.view_distance_blocks", 32.0), 32.0, 1.0, 256.0);
        bettingHologramScale = (float) finite(getConfig().getDouble("settings.holograms.betting_scale", 0.65), 0.65, 0.1, 5.0);
        spectatorsEnabled = getConfig().getBoolean("settings.spectators.enabled", true);
        finalStatsFormat = locales.text("messages.final-stats");
    }

    private void loadKits() {
        kitsFile = new File(getDataFolder(), "kits.yml");
        kits = YamlConfiguration.loadConfiguration(kitsFile);
        if (!kitsFile.exists()) {
            try { kits.save(kitsFile); }
            catch (IOException exception) { getLogger().log(Level.SEVERE, "Не удалось создать kits.yml", exception); }
        }
    }

    /**
     * Подключиться к денежному движку.
     *
     * Молчаливого перехода на предметы нет и не будет: если админ включил
     * денежный режим, а Core недоступен, ставки обязаны быть заблокированы.
     * Тихо начать принимать золотые слитки вместо денег значило бы изменить
     * правила игры за спиной у игроков.
     */
    private void connectEconomy() {
        if (!useVault || money == null) return;
        if (money.hook()) {
            getLogger().info("Экономика AurumCore подключена: ставки идут через ledger.");
            money.recover();
        } else {
            getLogger().severe("Включён денежный режим, но AurumCore недоступен или не в активном режиме. "
                    + "Денежные операции заблокированы; перехода на предметы нет.");
        }
    }

    /** Деньгами можно распоряжаться: режим включён и движок отвечает. */
    boolean moneyMode() {
        return useVault && money != null && money.available();
    }

    /** Выполнить в основном потоке: ответы Core приходят на чужих потоках. */
    void onMain(Runnable task) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(this, task);
    }

    private void loadArenas() {
        for (Arena old : arenas.values()) old.removeHolograms();
        arenas.clear();
        ConfigurationSection root = getConfig().getConfigurationSection("arenas");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            String base = "arenas." + key;
            Location center = getConfig().getLocation(base + ".center");
            if (center == null || center.getWorld() == null) {
                getLogger().warning("Арена " + key + " пропущена: отсутствует корректный center.");
                continue;
            }
            Arena arena = new Arena(key.toLowerCase(Locale.ROOT), center);
            arena.radius = clamp(getConfig().getInt(base + ".radius", 100), 10, 500);
            arena.redBtn = getConfig().getLocation(base + ".redBtn");
            arena.blueBtn = getConfig().getLocation(base + ".blueBtn");
            arena.hostBtn = getConfig().getLocation(base + ".hostBtn");
            arena.redHopper = getConfig().getLocation(base + ".redHopper");
            arena.blueHopper = getConfig().getLocation(base + ".blueHopper");
            arena.finalHopper = getConfig().getLocation(base + ".finalHopper");
            arena.bankomat = getConfig().getLocation(base + ".bankomat");
            arena.resetBtn = getConfig().getLocation(base + ".resetBtn");
            arena.specSpawn = getConfig().getLocation(base + ".specSpawn");
            arena.spawnRed1 = getConfig().getLocation(base + ".spawnRed1");
            arena.spawnRed2 = getConfig().getLocation(base + ".spawnRed2");
            arena.spawnBlue1 = getConfig().getLocation(base + ".spawnBlue1");
            arena.spawnBlue2 = getConfig().getLocation(base + ".spawnBlue2");
            arena.automatic = getConfig().getBoolean(base + ".isAuto", false);
            arena.maxPlayers = clamp(getConfig().getInt(base + ".maxPlayers", 2), 1, maxTeamLimit);
            arena.bettingEnabled = getConfig().getBoolean(base + ".isBettingEnabled", true);
            arena.kitEnabled = getConfig().getBoolean(base + ".isKitEnabled", false);
            arena.friendlyFire = getConfig().getBoolean(base + ".friendlyFire", false);
            arena.showBar = normalizeShowBar(getConfig().getString(base + ".showBar", "spectators"));
            arena.winnerExperience = clamp(getConfig().getInt(base + ".winnerExperience", defaultWinnerExperience), 0, 1_000_000);
            arena.finalWinnerExperience = clamp(getConfig().getInt(base + ".finalWinnerExperience", defaultFinalWinnerExperience), 0, 1_000_000);
            arena.experienceMode = ExperienceMode.parse(getConfig().getString(base + ".experienceMode", defaultExperienceMode.configValue));
            arena.finalPool = finite(getConfig().getDouble(base + ".finalPool", 0.0), 0.0, 0.0, 1_000_000_000_000.0);
            arena.lastChampions.addAll(getConfig().getStringList(base + ".lastChampions"));
            List<String> storedFinalStats = getConfig().getStringList(base + ".finalStats");
            for (String encoded : storedFinalStats) {
                FinalStatHolo holo = FinalStatHolo.parse(encoded);
                if (holo != null) {
                    arena.finalStats.add(holo);
                    arena.legacyHologramLocations.add(holo.location);
                }
            }
            int duplicateFinalStats = keepNewestByKey(arena.finalStats, GladiatorArena::hologramBlockKey);
            arenas.put(arena.name, arena);
            arena.updateHolograms();
            if (duplicateFinalStats > 0 || storedFinalStats.size() != arena.finalStats.size()) {
                saveArena(arena);
                getLogger().warning("Арена " + arena.name + ": удалено повреждённых или совпадающих в одном блоке финальных голограмм: " + (storedFinalStats.size() - arena.finalStats.size()) + ".");
            }
        }
        warnOverlaps();
    }

    private void saveArena(Arena arena) {
        String base = "arenas." + arena.name;
        getConfig().set(base + ".center", arena.center);
        getConfig().set(base + ".radius", arena.radius);
        getConfig().set(base + ".redBtn", arena.redBtn);
        getConfig().set(base + ".blueBtn", arena.blueBtn);
        getConfig().set(base + ".hostBtn", arena.hostBtn);
        getConfig().set(base + ".redHopper", arena.redHopper);
        getConfig().set(base + ".blueHopper", arena.blueHopper);
        getConfig().set(base + ".finalHopper", arena.finalHopper);
        getConfig().set(base + ".bankomat", arena.bankomat);
        getConfig().set(base + ".resetBtn", arena.resetBtn);
        getConfig().set(base + ".specSpawn", arena.specSpawn);
        getConfig().set(base + ".spawnRed1", arena.spawnRed1);
        getConfig().set(base + ".spawnRed2", arena.spawnRed2);
        getConfig().set(base + ".spawnBlue1", arena.spawnBlue1);
        getConfig().set(base + ".spawnBlue2", arena.spawnBlue2);
        getConfig().set(base + ".isAuto", arena.automatic);
        getConfig().set(base + ".maxPlayers", arena.maxPlayers);
        getConfig().set(base + ".isBettingEnabled", arena.bettingEnabled);
        getConfig().set(base + ".isKitEnabled", arena.kitEnabled);
        getConfig().set(base + ".friendlyFire", arena.friendlyFire);
        getConfig().set(base + ".showBar", arena.showBar);
        getConfig().set(base + ".winnerExperience", arena.winnerExperience);
        getConfig().set(base + ".finalWinnerExperience", arena.finalWinnerExperience);
        getConfig().set(base + ".experienceMode", arena.experienceMode.configValue);
        getConfig().set(base + ".finalPool", arena.finalPool);
        getConfig().set(base + ".lastChampions", arena.lastChampions);
        getConfig().set(base + ".finalStats", arena.finalStats.stream().map(FinalStatHolo::encode).toList());
        saveConfig();
    }

    /**
     * Вернуть ставки, пережившие перезапуск.
     *
     * Арена не восстанавливает бои: круг ставок, прерванный рестартом, не
     * продолжается, и деньги возвращаются владельцам. Ключ возврата строится
     * из НОВОГО roundId арены — прежний не пережил перезапуск, а повторный
     * запуск этой же процедуры на тех же записях невозможен: запись
     * удаляется сразу.
     */
    private void recoverInterruptedBets() {
        List<RecoveryStore.StoredBet> bets = recovery.allBets();
        List<RecoveryStore.PendingPayout> pending = recovery.allMoneyPayouts();
        if (bets.isEmpty() && pending.isEmpty()) return;
        if (!getConfig().getBoolean("settings.refund_interrupted_bets", true)) {
            getLogger().warning("В recovery.yml осталось " + bets.size() + " незавершённых ставок; автовозврат отключён.");
        } else {
            for (RecoveryStore.StoredBet bet : bets) {
                if (bet.vault()) {
                    Arena arena = arenas.get(bet.arena());
                    UUID round = arena == null ? UUID.randomUUID() : arena.roundId;
                    refundMoney(bet.arena(), round, bet.player(), bet.playerName(), bet.amount());
                } else {
                    payOrQueue(bet.player(), bet.playerName(), bet.amount(), false);
                }
                recovery.removeBet(bet.arena(), bet.team(), bet.player());
            }
            if (!bets.isEmpty()) getLogger().warning("Возвращено незавершённых ставок: " + bets.size());
        }
        // Выплаты, по которым в прошлый раз не пришёл ответ. Повтор идёт с тем
        // же ключом, поэтому уже проведённая операция вторично не заплатит.
        for (RecoveryStore.PendingPayout payout : pending) retryMoneyPayout(payout, null);
        reportLegacyDebts();
    }

    /**
     * Сообщить о долгах, оставшихся от Vault-версии.
     *
     * Их не выплачивает никто автоматически — и это осознанно. За такой
     * записью не стоит счёт в ledger: в эпоху Vault деньги просто появлялись
     * на балансе игрока. Выдать их сейчас значило бы создать валюту мимо
     * проводки; рассчитаться должен администратор командой /aurum economy
     * give, и тогда операция попадёт в аудит как положено.
     */
    private void reportLegacyDebts() {
        Map<UUID, RecoveryStore.LegacyDebt> debts = recovery.legacyMoneyDebts();
        if (debts.isEmpty()) return;
        getLogger().warning("В recovery.yml остались денежные выплаты старого формата (версия 1.4.0). "
                + "Автоматически они не выдаются: за ними нет проводки в ledger. "
                + "Рассчитайтесь вручную через /aurum economy give и удалите записи:");
        for (RecoveryStore.LegacyDebt debt : debts.values()) {
            getLogger().warning("  " + debt.playerName() + " (" + debt.player() + "): " + money(debt.amount()));
        }
    }

    /** Догнать отложенные денежные выплаты конкретного игрока. */
    private void drainMoneyPayouts(UUID uuid, String name) {
        if (!moneyMode()) return;
        for (RecoveryStore.PendingPayout payout : recovery.moneyPayouts(uuid)) retryMoneyPayout(payout, name);
    }

    /**
     * Повторить отложенную выплату её собственным ключом.
     *
     * Ключ хранится вместе с суммой именно ради этого: «ответа не было» не
     * значит «не проведено», и новый ключ означал бы вторую выплату.
     */
    private void retryMoneyPayout(RecoveryStore.PendingPayout payout, String name) {
        if (!moneyMode()) return;
        BetTicket.Purpose source = "FINAL".equals(payout.purpose())
                ? BetTicket.Purpose.FINAL : BetTicket.Purpose.BET;
        money.repeat(payout.key(), payout.player(), payout.amount(), payout.arena(), source)
                .whenComplete((result, error) -> onMain(() -> {
            if (!succeeded(result, error)) return;
            recovery.clearMoneyPayout(payout);
            Player online = Bukkit.getPlayer(payout.player());
            if (online != null) send(online, "§aПолучена отложенная выплата: " + money(payout.amount()) + currency());
        }));
    }

    /** Резерв получен и держится. */
    private static boolean heldOk(ovh.aurumgg.core.api.HoldResult result, Throwable error) {
        return error == null && result != null && result.hold().isPresent()
                && (result.status() == ovh.aurumgg.core.api.HoldResult.Status.SUCCESS
                || result.status() == ovh.aurumgg.core.api.HoldResult.Status.DUPLICATE)
                && result.hold().get().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.HELD;
    }

    /** Резерв зафиксирован: деньги действительно перешли арене. */
    private static boolean capturedOk(ovh.aurumgg.core.api.HoldResult result, Throwable error) {
        return error == null && result != null && result.hold().isPresent()
                && (result.status() == ovh.aurumgg.core.api.HoldResult.Status.SUCCESS
                || result.status() == ovh.aurumgg.core.api.HoldResult.Status.DUPLICATE)
                && result.hold().get().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.CAPTURED;
    }

    Arena arenaAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return arenas.values().stream().filter(a -> a.contains(location))
            .min(Comparator.comparingDouble(a -> a.center.distanceSquared(location))).orElse(null);
    }

    Arena arenaByName(String name) { return name == null ? null : arenas.get(name.toLowerCase(Locale.ROOT)); }
    DatabaseManager.PlayerStats stats(UUID uuid) { return database == null ? DatabaseManager.PlayerStats.EMPTY : database.get(uuid); }

    /** Typed, permission-checked data source for AurumCompanion/AurumUI. */
    public List<Map<String, String>> aurumAdminSnapshot(Player viewer, String scope) {
        if (!viewer.hasPermission("arena.admin") || !"arena".equals(scope)) return List.of();
        return arenas.values().stream().map(arena -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("kind", "arena");
            value.put("id", arena.name);
            value.put("title", arena.name);
            value.put("state", arena.state.name());
            value.put("world", arena.center.getWorld().getName());
            value.put("location", formatLocation(arena.center));
            value.put("radius", String.valueOf(arena.radius));
            value.put("maxPlayers", String.valueOf(arena.maxPlayers));
            value.put("automatic", String.valueOf(arena.automatic));
            value.put("betting", String.valueOf(arena.bettingEnabled));
            value.put("kit", String.valueOf(arena.kitEnabled));
            value.put("finalMode", String.valueOf(arena.finalMode));
            value.put("friendlyFire", String.valueOf(arena.friendlyFire));
            value.put("showBar", arena.showBar);
            value.put("winnerXp", String.valueOf(arena.winnerExperience));
            value.put("finalWinnerXp", String.valueOf(arena.finalWinnerExperience));
            value.put("xpMode", arena.experienceMode.configValue);
            value.put("finalPool", money(arena.finalPool));
            value.put("redPlayers", String.valueOf(arena.red.size()));
            value.put("bluePlayers", String.valueOf(arena.blue.size()));
            value.put("redBets", money(arena.redBets.values().stream().mapToDouble(Double::doubleValue).sum()));
            value.put("blueBets", money(arena.blueBets.values().stream().mapToDouble(Double::doubleValue).sum()));
            return Map.copyOf(value);
        }).toList();
    }

    /** Executes only named actions; the client can never pass an arbitrary server command. */
    public String aurumAdminAction(Player actor, String id, String action, Map<String, String> arguments) {
        if (!actor.hasPermission("arena.admin")) return "error.permission";
        Arena arena = arenaByName(id);
        if (arena == null) return "error.not_found";
        try {
            switch (action) {
                case "teleport" -> actor.teleport(arena.center.clone().add(0.5, 0.0, 0.5));
                case "toggle_auto" -> arena.automatic = !arena.automatic;
                case "toggle_betting" -> arena.bettingEnabled = !arena.bettingEnabled;
                case "toggle_friendly_fire" -> arena.friendlyFire = !arena.friendlyFire;
                case "toggle_kit" -> {
                    if (!arena.canChangeKit()) return "error.arena_not_empty";
                    arena.kitEnabled = !arena.kitEnabled;
                }
                case "cycle_show_bar" -> arena.showBar = cycle(arena.showBar, List.of("spectators", "all", "false"));
                case "cycle_xp_mode" -> arena.experienceMode = arena.experienceMode == ExperienceMode.POINTS
                        ? ExperienceMode.LEVELS : ExperienceMode.POINTS;
                case "set_final_pool" -> arena.finalPool = decimal(arguments, "value", 0.0, 1_000_000_000_000.0);
                case "set_radius" -> arena.radius = integer(arguments, "value", 10, 500);
                case "set_max_players" -> arena.maxPlayers = integer(arguments, "value", 1, maxTeamLimit);
                case "set_winner_xp" -> arena.winnerExperience = integer(arguments, "value", 0, 1_000_000);
                case "set_final_winner_xp" -> arena.finalWinnerExperience = integer(arguments, "value", 0, 1_000_000);
                case "validate" -> arena.validate(actor);
                case "odds" -> arena.sendOdds(actor);
                case "toggle_final" -> arena.toggleFinal(actor);
                case "start" -> arena.beginCountdown(actor);
                case "stop" -> arena.stop("stopped from AurumUI", true);
                default -> { return "error.unknown_action"; }
            }
            saveArena(arena);
            arena.updateHolograms();
            return "ok.saved";
        } catch (IllegalArgumentException exception) {
            return "error.invalid_value";
        }
    }

    private static int integer(Map<String, String> arguments, String key, int minimum, int maximum) {
        int value = Integer.parseInt(arguments.getOrDefault(key, ""));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key);
        return value;
    }

    private static double decimal(Map<String, String> arguments, String key, double minimum, double maximum) {
        double value = Double.parseDouble(arguments.getOrDefault(key, ""));
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(key);
        return roundTenth(value);
    }

    String placeholder(Player player, String identifier) {
        Arena arena = arenaAt(player.getLocation());
        DatabaseManager.PlayerStats stats = stats(player.getUniqueId());
        return switch (identifier.toLowerCase(Locale.ROOT)) {
            case "arena" -> arena == null ? "" : arena.name;
            case "state" -> arena == null ? "NONE" : arena.state.name();
            case "red_players" -> arena == null ? "0" : String.valueOf(arena.red.size());
            case "blue_players" -> arena == null ? "0" : String.valueOf(arena.blue.size());
            case "red_bets" -> arena == null ? "0.0" : money(arena.total(arena.redBets));
            case "blue_bets" -> arena == null ? "0.0" : money(arena.total(arena.blueBets));
            case "total_bets" -> arena == null ? "0.0" : money(arena.total(arena.redBets) + arena.total(arena.blueBets));
            case "wins" -> String.valueOf(stats.wins());
            case "losses" -> String.valueOf(stats.losses());
            case "streak" -> String.valueOf(stats.streak());
            case "best_streak" -> String.valueOf(stats.bestStreak());
            case "earnings" -> money(stats.earnings());
            default -> null;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) { sendHelp(sender); return true; }
        if (action.equals("list")) {
            send(sender, "Арены: §e" + (arenas.isEmpty() ? "нет" : String.join("§7, §e", arenas.keySet()))); return true;
        }
        if (action.equals("stats")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
            if (target == null) send(sender, "§cИгрок не найден."); else showStats(sender, target);
            return true;
        }
        if (action.equals("odds")) {
            Arena arena = args.length >= 2 ? arenaByName(args[1]) : sender instanceof Player p ? arenaAt(p.getLocation()) : null;
            if (arena == null) send(sender, "§cУкажите арену: /arena odds <арена>"); else arena.sendOdds(sender);
            return true;
        }
        if (!(sender instanceof Player player)) { send(sender, "§cЭта команда доступна только игроку."); return true; }
        if (action.equals("spectate")) {
            if (!player.hasPermission("arena.spectate")) return noPermission(player);
            startSpectating(player, args.length >= 2 ? arenaByName(args[1]) : arenaAt(player.getLocation())); return true;
        }
        if (action.equals("leave")) {
            if (!leaveSpectator(player, true)) {
                Arena participant = participantArena(player.getUniqueId());
                if (participant != null) participant.voluntaryLeave(player); else send(player, "§eВы не участвуете в арене.");
            }
            return true;
        }
        if (!player.hasPermission("arena.admin")) return noPermission(player);
        if (action.equals("recover")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : player;
            if (target == null) { send(player, "§cИгрок не найден."); return true; }
            if (participantArena(target.getUniqueId()) != null) {
                send(player, "§cНельзя восстанавливать инвентарь активному участнику арены.");
                return true;
            }
            if (!recovery.hasInventory(target.getUniqueId())) {
                send(player, "§eДля " + target.getName() + " нет сохранённой резервной копии.");
                return true;
            }
            if (restoreParticipantInventory(target, "ручное восстановление администратором")) {
                send(player, "§aИнвентарь " + target.getName() + " восстановлен.");
                if (target != player) send(target, "§aВаш инвентарь восстановлен администратором.");
            }
            return true;
        }
        if (action.equals("create")) return createArena(player, args);
        if (action.equals("delete")) return deleteArena(player, args);
        if (action.equals("reload") || action.equals("restart")) {
            for (Arena arena : arenas.values()) arena.shutdown();
            loadSettings(); loadKits(); connectEconomy();
            if (database != null) database.close();
            database = new DatabaseManager(this); database.start(); loadArenas();
            send(player, "§aКонфигурация, экономика и база данных перезагружены."); return true;
        }
        Arena arena = args.length >= 2 && Set.of("status", "validate", "gui").contains(action) ? arenaByName(args[1]) : arenaAt(player.getLocation());
        if (arena == null) { send(player, "§cВы не в радиусе арены."); return true; }
        switch (action) {
            case "status" -> arena.sendStatus(player);
            case "validate" -> arena.validate(player);
            case "gui" -> openAdminGui(player, arena);
            case "start" -> arena.beginCountdown(player);
            case "stop" -> arena.stop("остановлена администратором", true);
            case "final" -> arena.toggleFinal(player);
            case "showbar" -> { arena.showBar = args.length >= 2 ? normalizeShowBar(args[1]) : cycle(arena.showBar, List.of("spectators", "all", "false")); changed(player, arena, "BossBar: " + arena.showBar); }
            case "friendlyfire" -> { arena.friendlyFire = parseToggle(args, 1, arena.friendlyFire); changed(player, arena, "Friendly fire: " + arena.friendlyFire); }
            case "betting" -> { arena.bettingEnabled = parseToggle(args, 1, arena.bettingEnabled); changed(player, arena, "Ставки: " + arena.bettingEnabled); }
            case "kit" -> {
                if (!arena.canChangeKit()) send(player, "§cРежим комплекта меняется только на пустой ожидающей арене.");
                else { arena.kitEnabled = parseToggle(args, 1, arena.kitEnabled); changed(player, arena, "Комплект: " + arena.kitEnabled); }
            }
            case "winxp" -> setExperienceReward(player, arena, args, false);
            case "finalxp" -> setExperienceReward(player, arena, args, true);
            case "xpmode" -> setExperienceMode(player, arena, args);
            case "auto" -> { arena.automatic = true; changed(player, arena, "Автостарт включён"); }
            case "manual" -> { arena.automatic = false; arena.timerTicks = -1; changed(player, arena, "Ручной старт включён"); }
            case "maxplayers" -> setNumber(player, arena, args, true);
            case "radius" -> setNumber(player, arena, args, false);
            case "setspawn" -> setLocation(player, arena, "specSpawn", player.getLocation());
            case "spawnred1" -> setLocation(player, arena, "spawnRed1", player.getLocation());
            case "spawnred2" -> setLocation(player, arena, "spawnRed2", player.getLocation());
            case "spawnblue1" -> setLocation(player, arena, "spawnBlue1", player.getLocation());
            case "spawnblue2" -> setLocation(player, arena, "spawnBlue2", player.getLocation());
            case "unhopred" -> setLocation(player, arena, "redHopper", null);
            case "unhopblue" -> setLocation(player, arena, "blueHopper", null);
            case "unfhop" -> setLocation(player, arena, "finalHopper", null);
            case "finalstats" -> addFinalHolo(player, arena);
            case "fstatsremove" -> removeFinalHolo(player, arena, args);
            case "fstatsscale" -> scaleFinalHolo(player, arena, args);
            case "debug" -> debug(player, arena, args);
            default -> setTargetBlock(player, arena, action);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : locales.lines("help.player")) sender.sendMessage(line);
        if (!sender.hasPermission("arena.admin")) return;
        for (String line : locales.lines("help.admin")) sender.sendMessage(line);
    }

    private boolean createArena(Player player, String[] args) {
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9_-]{1,32}")) { send(player, "§cИспользование: /arena create <имя>"); return true; }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (arenas.containsKey(name)) { send(player, "§cАрена уже существует."); return true; }
        for (Arena other : arenas.values()) if (other.center.getWorld().equals(player.getWorld()) && other.center.distance(player.getLocation()) < other.radius + 100.0) {
            send(player, "§cНовая арена пересечётся с " + other.name + "."); return true;
        }
        Arena arena = new Arena(name, player.getLocation()); arenas.put(name, arena); saveArena(arena);
        send(player, "§aАрена " + name + " создана."); return true;
    }

    private boolean deleteArena(Player player, String[] args) {
        if (args.length < 2) { send(player, "§cИспользование: /arena delete <арена>"); return true; }
        Arena arena = arenaByName(args[1]);
        if (arena == null) { send(player, "§cАрена не найдена."); return true; }
        arena.shutdown(); arenas.remove(arena.name); getConfig().set("arenas." + arena.name, null); saveConfig();
        send(player, "§aАрена удалена вместе с её голограммами."); return true;
    }

    private void setNumber(Player player, Arena arena, String[] args, boolean players) {
        if (args.length < 2) { send(player, "§cУкажите число."); return; }
        try {
            int value = Integer.parseInt(args[1]);
            if (players) arena.maxPlayers = clamp(value, 1, maxTeamLimit);
            else {
                int old = arena.radius; arena.radius = clamp(value, 10, 500);
                if (hasOverlap(arena)) { arena.radius = old; send(player, "§cТакой радиус пересекает другую арену."); return; }
            }
            changed(player, arena, (players ? "Игроков в команде: " : "Радиус: ") + (players ? arena.maxPlayers : arena.radius));
        } catch (NumberFormatException exception) { send(player, "§cНужно целое число."); }
    }

    private void setExperienceReward(Player player, Arena arena, String[] args, boolean finalReward) {
        if (args.length < 2) {
            send(player, "§cИспользование: /arena " + (finalReward ? "finalxp" : "winxp") + " <0..1000000>");
            return;
        }
        try {
            int value = Integer.parseInt(args[1]);
            if (value < 0 || value > 1_000_000) {
                send(player, "§cОпыт должен быть от 0 до 1000000.");
                return;
            }
            if (finalReward) arena.finalWinnerExperience = value; else arena.winnerExperience = value;
            changed(player, arena, (finalReward ? "Опыт за финал: " : "Опыт за бой: ") + value + " " + arena.experienceMode.displayName(value));
        } catch (NumberFormatException exception) {
            send(player, "§cНужно целое число.");
        }
    }

    private void setExperienceMode(Player player, Arena arena, String[] args) {
        if (args.length < 2 || !ExperienceMode.isValid(args[1])) {
            send(player, "§cИспользование: /arena xpmode <points|levels>");
            return;
        }
        arena.experienceMode = ExperienceMode.parse(args[1]);
        changed(player, arena, "Режим опыта: " + arena.experienceMode.configValue);
    }

    private void setTargetBlock(Player player, Arena arena, String action) {
        Set<String> supported = Set.of("setred", "setblue", "sethost", "setreset", "sethopred", "sethopblue", "setfhop", "bankomat");
        if (!supported.contains(action)) { send(player, "§cНеизвестная команда. /arena help"); return; }
        Block target = player.getTargetBlockExact(6);
        if (target == null) { send(player, "§cСмотрите на блок не дальше 6 блоков."); return; }
        Location location = target.getLocation();
        switch (action) {
            case "setred" -> arena.redBtn = location; case "setblue" -> arena.blueBtn = location; case "sethost" -> arena.hostBtn = location;
            case "setreset" -> arena.resetBtn = location; case "sethopred" -> arena.redHopper = location; case "sethopblue" -> arena.blueHopper = location;
            case "setfhop" -> arena.finalHopper = location; case "bankomat" -> arena.bankomat = location;
        }
        saveArena(arena); arena.updateHolograms(); send(player, "§aТочка " + action + " установлена: " + formatLocation(location));
    }

    private void setLocation(Player player, Arena arena, String field, Location location) {
        switch (field) {
            case "specSpawn" -> arena.specSpawn = location; case "spawnRed1" -> arena.spawnRed1 = location; case "spawnRed2" -> arena.spawnRed2 = location;
            case "spawnBlue1" -> arena.spawnBlue1 = location; case "spawnBlue2" -> arena.spawnBlue2 = location; case "redHopper" -> arena.redHopper = location;
            case "blueHopper" -> arena.blueHopper = location; case "finalHopper" -> arena.finalHopper = location;
        }
        saveArena(arena); arena.updateHolograms(); send(player, "§a" + field + (location == null ? " удалена." : " установлена."));
    }

    private void addFinalHolo(Player player, Arena arena) {
        Location location = player.getLocation().clone().add(0, 1.5, 0); location.setYaw(location.getYaw() + 180f); location.setPitch(0f);
        if (arena.finalStats.stream().anyMatch(holo -> sameBlock(holo.location, location))) {
            arena.updateFinalHolograms();
            send(player, "§eВ этом блоке уже настроена финальная голограмма; физические дубликаты очищены.");
            return;
        }
        arena.finalStats.add(new FinalStatHolo(location, 1f));
        saveArena(arena); arena.updateFinalHolograms();
        send(player, "§aФинальная голограмма добавлена. Всего: " + arena.finalStats.size() + ".");
    }

    private void removeFinalHolo(Player player, Arena arena, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            int removed = arena.finalStats.size();
            arena.finalStats.forEach(holo -> arena.legacyHologramLocations.add(holo.location));
            arena.finalStats.clear();
            saveArena(arena); arena.updateFinalHolograms();
            send(player, "§aУдалены все финальные голограммы: " + removed + ".");
            return;
        }
        FinalStatHolo nearest = arena.finalStats.stream().filter(holo -> holo.location.getWorld().equals(player.getWorld()))
            .min(Comparator.comparingDouble(holo -> holo.location.distanceSquared(player.getLocation()))).orElse(null);
        if (nearest == null || nearest.location.distanceSquared(player.getLocation()) > 25) {
            send(player, "§cНет настроенной финальной голограммы в радиусе 5 блоков. Для удаления всех: /arena fstatsremove all");
            return;
        }
        arena.legacyHologramLocations.add(nearest.location); arena.finalStats.remove(nearest);
        saveArena(arena); arena.updateFinalHolograms();
        send(player, "§aБлижайшая финальная голограмма удалена. Осталось: " + arena.finalStats.size() + ".");
    }

    private void scaleFinalHolo(Player player, Arena arena, String[] args) {
        if (args.length < 2) { send(player, "§cИспользование: /arena fstatsscale <0.1..5>"); return; }
        try {
            float scale = (float) finite(Double.parseDouble(args[1]), 1.0, 0.1, 5.0);
            FinalStatHolo nearest = arena.finalStats.stream().filter(h -> h.location.getWorld().equals(player.getWorld()))
                .min(Comparator.comparingDouble(h -> h.location.distanceSquared(player.getLocation()))).orElse(null);
            if (nearest == null || nearest.location.distanceSquared(player.getLocation()) > 25) { send(player, "§cНет голограммы в радиусе 5 блоков."); return; }
            nearest.scale = scale; saveArena(arena); arena.updateFinalHolograms(); send(player, "§aМасштаб: " + scale);
        } catch (NumberFormatException exception) { send(player, "§cНекорректное число."); }
    }

    private void debug(Player player, Arena arena, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("hologram")) { send(player, "§e/arena debug hologram"); return; }
        TextDisplay nearest = player.getWorld().getNearbyEntities(player.getLocation(), 8.0, 8.0, 8.0).stream()
            .filter(TextDisplay.class::isInstance).map(TextDisplay.class::cast)
            .filter(entity -> entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.STRING))
            .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation()))).orElse(null);
        if (nearest == null) send(player, "§cГолограмма AurumArena не найдена в радиусе 8 блоков.");
        else { nearest.remove(); send(player, "§aБлижайшая голограмма удалена."); }
    }

    private void changed(Player player, Arena arena, String text) { saveArena(arena); arena.updateHolograms(); send(player, "§a" + text); }
    private boolean noPermission(CommandSender sender) { sender.sendMessage(locales.text("messages.no-permission")); return true; }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(locales.translate(PREFIX + text));
    }

    private void sendBare(CommandSender sender, String text) {
        sender.sendMessage(locales.translate(text));
    }

    private void showStats(CommandSender sender, Player target) {
        DatabaseManager.PlayerStats value = stats(target.getUniqueId());
        send(sender, "§eСтатистика " + target.getName() + ": §a" + value.wins() + " побед§7, §c" + value.losses()
            + " поражений§7, серия §f" + value.streak() + "§7, заработано §6" + money(value.earnings()));
        if (!database.isReady()) sender.sendMessage(locales.text("messages.database-unavailable"));
    }

    private void startSpectating(Player player, Arena arena) {
        if (!spectatorsEnabled) { send(player, "§cРежим наблюдателя отключён."); return; }
        if (arena == null) { send(player, "§cУкажите арену: /arena spectate <арена>"); return; }
        if (participantArena(player.getUniqueId()) != null) { send(player, "§cУчастник боя не может стать наблюдателем."); return; }
        if (spectators.containsKey(player.getUniqueId())) leaveSpectator(player, false);
        recovery.saveSpectator(player, arena.name); spectators.put(player.getUniqueId(), arena); player.setGameMode(GameMode.SPECTATOR);
        player.teleport(arena.specSpawn != null ? arena.specSpawn : arena.center.clone().add(0, 3, 0));
        send(player, "§aВы наблюдаете за ареной " + arena.name + ". Выход за границу вернёт вас назад.");
    }

    private boolean leaveSpectator(Player player, boolean message) {
        Arena removed = spectators.remove(player.getUniqueId()); boolean restored = recovery.restoreSpectator(player);
        if ((removed != null || restored) && message) send(player, "§eНаблюдение завершено, исходное состояние восстановлено.");
        return removed != null || restored;
    }

    private Arena participantArena(UUID uuid) { for (Arena arena : arenas.values()) if (arena.isParticipant(uuid)) return arena; return null; }

    private void tickAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Arena spectatorArena = spectators.get(player.getUniqueId());
            if (spectatorArena != null && (player.getGameMode() != GameMode.SPECTATOR || !spectatorArena.contains(player.getLocation()))) {
                leaveSpectator(player, true); send(player, "§cВы покинули границу арены; наблюдение автоматически завершено."); continue;
            }
            if (arenaAt(player.getLocation()) == null && participantArena(player.getUniqueId()) == null) restoreScoreboard(player);
        }
        for (Arena arena : arenas.values()) arena.tick();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer(); if (database != null) database.load(player.getUniqueId());
        if (getConfig().getBoolean("settings.spectators.restore_on_join", true) && recovery.hasSpectator(player.getUniqueId())) Bukkit.getScheduler().runTask(this, () -> leaveSpectator(player, true));
        if (getConfig().getBoolean("settings.recover_inventories_on_join", true) && recovery.hasInventory(player.getUniqueId())) Bukkit.getScheduler().runTaskLater(this, () -> {
            if (restoreParticipantInventory(player, "вход после незавершённого матча")) send(player, "§aИнвентарь восстановлен после незавершённого матча.");
        }, 2L);
        Bukkit.getScheduler().runTaskLater(this, () -> claimPending(player), 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Location clicked = event.getClickedBlock().getLocation(); for (Arena arena : arenas.values()) if (arena.handleInteract(event.getPlayer(), clicked, event)) return;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Arena arena = participantArena(victim.getUniqueId());
        if (arena == null) { if (spectators.containsKey(victim.getUniqueId())) event.setCancelled(true); return; }
        if (arena.state != GameState.FIGHTING) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        Arena arena = participantArena(victim.getUniqueId());
        Arena attackerArena = attacker == null ? null : participantArena(attacker.getUniqueId());
        if (arena == null && attackerArena == null) return;
        if (arena == null || attackerArena != arena || arena.state != GameState.FIGHTING) { event.setCancelled(true); return; }
        boolean sameTeam = arena.red.contains(victim.getUniqueId()) == arena.red.contains(attacker.getUniqueId());
        if (sameTeam && !arena.friendlyFire) event.setCancelled(true);
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity(); Arena arena = participantArena(player.getUniqueId()); if (arena == null) return;
        if (arena.kitEnabled) event.getDrops().clear(); pendingRespawns.put(player.getUniqueId(), arena); arena.eliminate(player.getUniqueId(), "погиб");
        Bukkit.getScheduler().runTaskLater(this, () -> { if (player.isOnline() && player.isDead()) player.spigot().respawn(); }, 5L);
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Arena arena = pendingRespawns.remove(event.getPlayer().getUniqueId()); if (arena == null) return;
        if (arena.specSpawn != null) event.setRespawnLocation(arena.specSpawn);
        Bukkit.getScheduler().runTaskLater(this, () -> restoreParticipantInventory(event.getPlayer(), "респавн после боя"), 2L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer(); pendingRespawns.remove(player.getUniqueId());
        if (spectators.containsKey(player.getUniqueId()) || recovery.hasSpectator(player.getUniqueId())) leaveSpectator(player, false);
        Arena arena = participantArena(player.getUniqueId());
        if (arena != null) {
            boolean technical = arena.state == GameState.FIGHTING && combatLogLoss;
            arena.removeParticipant(player.getUniqueId(), technical ? "вышел с сервера — техническое поражение" : "вышел");
            if (!player.isDead()) restoreParticipantInventory(player, "выход с сервера");
        }
        restoreScoreboard(player);
        if (database != null) database.unload(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) {
        Arena arena = spectators.get(event.getPlayer().getUniqueId());
        if (arena == null || event.getTo() == null || arena.contains(event.getTo())) return;
        Bukkit.getScheduler().runTask(this, () -> { if (spectators.containsKey(event.getPlayer().getUniqueId())) {
            leaveSpectator(event.getPlayer(), true); send(event.getPlayer(), "§cТелепортация за арену завершила наблюдение.");
        }});
    }

    @EventHandler(ignoreCancelled = true) public void onDrop(PlayerDropItemEvent event) {
        Arena arena = participantArena(event.getPlayer().getUniqueId()); if (arena != null && arena.kitEnabled) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isProtectedHopper(event.getSource().getHolder()) || isProtectedHopper(event.getDestination().getHolder())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isProtectedHopper(event.getInventory().getHolder())) event.setCancelled(true);
    }

    @EventHandler public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ArenaGui holder)) return; event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("arena.admin")) return;
        ItemStack item = event.getCurrentItem(); if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(guiActionKey, PersistentDataType.STRING);
        Arena arena = arenaByName(holder.arenaName); if (action == null || arena == null) return;
        switch (action) {
            case "auto" -> arena.automatic = !arena.automatic; case "betting" -> arena.bettingEnabled = !arena.bettingEnabled;
            case "kit" -> {
                if (!arena.canChangeKit()) {
                    send(player, "§cРежим комплекта меняется только на пустой ожидающей арене.");
                    openAdminGui(player, arena);
                    return;
                }
                arena.kitEnabled = !arena.kitEnabled;
            }
            case "friendly" -> arena.friendlyFire = !arena.friendlyFire;
            case "bar" -> arena.showBar = cycle(arena.showBar, List.of("spectators", "all", "false"));
            case "start" -> arena.beginCountdown(player); case "stop" -> arena.stop("остановлена из GUI", true); case "validate" -> arena.validate(player);
        }
        saveArena(arena); openAdminGui(player, arena);
    }

    private boolean isProtectedHopper(InventoryHolder holder) {
        if (!(holder instanceof Hopper hopper)) return false; Location location = hopper.getLocation();
        for (Arena arena : arenas.values()) if (sameBlock(location, arena.redHopper) || sameBlock(location, arena.blueHopper) || sameBlock(location, arena.finalHopper)) return true;
        return false;
    }

    private void openAdminGui(Player player, Arena arena) {
        ArenaGui holder = new ArenaGui(arena.name); Inventory gui = Bukkit.createInventory(holder, 27, locales.translate("§8Arena: " + arena.name)); holder.inventory = gui;
        gui.setItem(10, guiItem(Material.CLOCK, "§eАвтостарт: " + arena.automatic, "auto"));
        gui.setItem(11, guiItem(Material.GOLD_INGOT, "§eСтавки: " + arena.bettingEnabled, "betting"));
        gui.setItem(12, guiItem(Material.IRON_CHESTPLATE, "§eКомплект: " + arena.kitEnabled, "kit"));
        gui.setItem(13, guiItem(Material.IRON_SWORD, "§eFriendly fire: " + arena.friendlyFire, "friendly"));
        gui.setItem(14, guiItem(Material.SPAWNER, "§eBossBar: " + arena.showBar, "bar"));
        gui.setItem(15, guiItem(Material.LIME_DYE, "§aСтарт", "start")); gui.setItem(16, guiItem(Material.RED_DYE, "§cСтоп", "stop"));
        gui.setItem(22, guiItem(Material.WRITABLE_BOOK, "§bПроверить настройку", "validate")); player.openInventory(gui);
    }

    private ItemStack guiItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(locales.translate(name));
        meta.getPersistentDataContainer().set(guiActionKey, PersistentDataType.STRING, action); item.setItemMeta(meta); return item;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("help", "list", "odds", "stats", "spectate", "leave"));
            if (sender.hasPermission("arena.admin")) base.addAll(List.of("create", "delete", "status", "validate", "gui", "start", "stop", "reload", "recover",
                "setred", "setblue", "sethost", "setreset", "sethopred", "sethopblue", "setfhop", "bankomat", "unhopred", "unhopblue", "unfhop",
                "setspawn", "spawnred1", "spawnred2", "spawnblue1", "spawnblue2", "showbar", "friendlyfire", "maxplayers", "auto", "manual",
                "betting", "kit", "winxp", "finalxp", "xpmode", "final", "finalstats", "fstatsremove", "fstatsscale", "radius", "debug"));
            return filter(base, args[0]);
        }
        if (args.length == 2) return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "delete", "status", "validate", "gui", "odds", "spectate" -> filter(arenas.keySet(), args[1]);
            case "stats", "recover" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            case "showbar" -> filter(List.of("spectators", "all", "false"), args[1]);
            case "friendlyfire", "betting", "kit" -> filter(List.of("true", "false", "toggle"), args[1]);
            case "winxp", "finalxp" -> filter(List.of("0", "10", "50", "100", "500"), args[1]);
            case "xpmode" -> filter(List.of("points", "levels"), args[1]);
            case "maxplayers" -> filter(List.of("1", "2", "3", "4", "5", "10", "20"), args[1]);
            case "radius" -> filter(List.of("25", "50", "75", "100", "150", "200"), args[1]);
            case "fstatsscale" -> filter(List.of("0.5", "0.75", "1.0", "1.5", "2.0"), args[1]);
            case "fstatsremove" -> filter(List.of("all"), args[1]);
            case "debug" -> filter(List.of("hologram"), args[1]); default -> List.of();
        };
        return List.of();
    }

    private final class Arena {
        final String name; final Location center;
        int radius = 100, maxPlayers = 2; Location redBtn, blueBtn, hostBtn, resetBtn, redHopper, blueHopper, finalHopper, bankomat;
        Location specSpawn, spawnRed1, spawnRed2, spawnBlue1, spawnBlue2;
        boolean automatic, bettingEnabled = true, kitEnabled, friendlyFire, finalMode; String showBar = "spectators"; double finalPool;
        int winnerExperience, finalWinnerExperience; ExperienceMode experienceMode;
        final List<String> lastChampions = new ArrayList<>(); final List<FinalStatHolo> finalStats = new ArrayList<>();
        final List<Location> legacyHologramLocations = new ArrayList<>();
        final Map<String, Set<UUID>> holograms = new HashMap<>(); boolean hologramsDiscovered;
        final Set<UUID> red = new LinkedHashSet<>(), blue = new LinkedHashSet<>(), originalRed = new LinkedHashSet<>(), originalBlue = new LinkedHashSet<>();
        final Map<UUID, Double> redBets = new HashMap<>(), blueBets = new HashMap<>(); final Set<UUID> usedResets = new HashSet<>();
        /**
         * Идентификатор текущего круга ставок.
         *
         * Из него строятся ключи идемпотентности выплат и возвратов: повтор
         * после сбоя обязан попасть в ТУ ЖЕ операцию, иначе победителю
         * заплатят дважды. Меняется при открытии ставок — с этого момента
         * начинаются новые деньги, и старые ключи к ним отношения не имеют.
         */
        UUID roundId = UUID.randomUUID();
        final Map<UUID, BossBar> bars = new HashMap<>(); GameState state = GameState.WAITING; int timerTicks = -1, fightTicks, tickCounter;
        Arena(String name, Location center) {
            this.name = name;
            this.center = center.clone();
            this.winnerExperience = defaultWinnerExperience;
            this.finalWinnerExperience = defaultFinalWinnerExperience;
            this.experienceMode = defaultExperienceMode;
        }
        boolean contains(Location location) { return location != null && location.getWorld() != null && center.getWorld().equals(location.getWorld()) && center.distanceSquared(location) <= (double) radius * radius; }
        boolean isParticipant(UUID uuid) { return red.contains(uuid) || blue.contains(uuid); }

        boolean handleInteract(Player player, Location location, PlayerInteractEvent event) {
            boolean arenaControl = sameBlock(location, finalHopper) || sameBlock(location, bankomat)
                    || sameBlock(location, resetBtn) || sameBlock(location, hostBtn)
                    || sameBlock(location, redBtn) || sameBlock(location, blueBtn)
                    || sameBlock(location, redHopper) || sameBlock(location, blueHopper);
            if (!arenaControl) return false;
            // WorldGuard may already have cancelled interaction with a button in
            // a protected arena. Registered arena controls are virtual controls,
            // so handle only those here and let every unrelated block stay denied.
            event.setCancelled(true);
            if (!player.hasPermission("arena.use")) {
                noPermission(player);
                return true;
            }
            if (sameBlock(location, finalHopper)) { event.setCancelled(true); contributeFinal(player); return true; }
            if (sameBlock(location, bankomat)) { event.setCancelled(true); exchange(player); return true; }
            if (sameBlock(location, resetBtn)) { event.setCancelled(true); cancelBet(player); return true; }
            if (sameBlock(location, hostBtn)) { event.setCancelled(true); beginCountdown(player); return true; }
            if (sameBlock(location, redBtn)) { event.setCancelled(true); toggleTeam(player, true); return true; }
            if (sameBlock(location, blueBtn)) { event.setCancelled(true); toggleTeam(player, false); return true; }
            if (sameBlock(location, redHopper)) { event.setCancelled(true); placeBet(player, true); return true; }
            if (sameBlock(location, blueHopper)) { event.setCancelled(true); placeBet(player, false); return true; }
            return false;
        }

        void toggleTeam(Player player, boolean redTeam) {
            if (state == GameState.FIGHTING || state == GameState.COUNTDOWN) { send(player, "§cРегистрация закрыта."); return; }
            if (spectators.containsKey(player.getUniqueId())) leaveSpectator(player, false);
            Arena existingArena = participantArena(player.getUniqueId());
            if (existingArena != null && existingArena != this) {
                send(player, "§cВы уже участвуете в арене " + existingArena.name + ". Сначала используйте /arena leave.");
                return;
            }
            Set<UUID> own = redTeam ? red : blue, other = redTeam ? blue : red;
            if (other.contains(player.getUniqueId())) { send(player, "§cСначала выйдите из другой команды."); return; }
            if (own.remove(player.getUniqueId())) { removeBar(player.getUniqueId()); restoreParticipantInventory(player, "выход из команды"); send(player, "§eВы вышли из команды."); updatePhase(); return; }
            if (own.size() >= maxPlayers) { send(player, "§cКоманда заполнена."); return; }
            if (kitEnabled && !applyKit(player)) return;
            own.add(player.getUniqueId()); BossBar bar = Bukkit.createBossBar(locales.translate((redTeam ? "§cКрасные: " : "§9Синие: ") + player.getName()), redTeam ? BarColor.RED : BarColor.BLUE, BarStyle.SOLID);
            bars.put(player.getUniqueId(), bar); send(player, "§aВы вступили в " + (redTeam ? "красную" : "синюю") + " команду."); updatePhase();
        }

        boolean applyKit(Player player) {
            if (recovery.hasInventory(player.getUniqueId())) {
                if (!restoreParticipantInventory(player, "повторный вход в команду")) {
                    send(player, "§cСначала не удалось восстановить предыдущую резервную копию; комплект не выдан.");
                    return false;
                }
                send(player, "§eПредыдущая резервная копия инвентаря восстановлена перед выдачей нового комплекта.");
            }
            if (!recovery.saveInventory(player, name)) { send(player, "§cНе удалось создать резервную копию инвентаря."); return false; }
            player.getInventory().clear(); ConfigurationSection items = kits.getConfigurationSection(name + ".items");
            if (items == null) { send(player, "§eВ kits.yml нет комплекта; выдан пустой комплект."); return true; }
            for (String key : items.getKeys(false)) {
                Material material = Material.matchMaterial(items.getString(key + ".type", "AIR"));
                if (material == null || material.isAir()) { getLogger().warning("Неизвестный материал комплекта " + name + ": " + items.getString(key + ".type")); continue; }
                int amount = clamp(items.getInt(key + ".amount", 1), 1, material.getMaxStackSize()); ItemStack item = new ItemStack(material, amount);
                ConfigurationSection enchants = items.getConfigurationSection(key + ".enchants");
                if (enchants != null) for (String enchantName : enchants.getKeys(false)) {
                    Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantName.toLowerCase(Locale.ROOT)));
                    if (enchantment != null) item.addUnsafeEnchantment(enchantment, Math.max(1, enchants.getInt(enchantName)));
                }
                if (item.getItemMeta() instanceof PotionMeta potion) {
                    ConfigurationSection effects = items.getConfigurationSection(key + ".effects");
                    if (effects != null) for (String effectName : effects.getKeys(false)) {
                        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(effectName.toLowerCase(Locale.ROOT)));
                        if (type != null) potion.addCustomEffect(new PotionEffect(type, Math.max(1, effects.getInt(effectName + ".duration", 1200)), Math.max(0, effects.getInt(effectName + ".amplifier", 0))), true);
                    }
                    item.setItemMeta(potion);
                }
                switch (key.toLowerCase(Locale.ROOT)) {
                    case "helmet" -> player.getInventory().setHelmet(item); case "chestplate" -> player.getInventory().setChestplate(item);
                    case "leggings" -> player.getInventory().setLeggings(item); case "boots" -> player.getInventory().setBoots(item);
                    case "offhand" -> player.getInventory().setItemInOffHand(item); default -> player.getInventory().addItem(item);
                }
            }
            return true;
        }

        boolean canChangeKit() { return state == GameState.WAITING && red.isEmpty() && blue.isEmpty(); }

        void updatePhase() {
            boolean ready = !red.isEmpty() && !blue.isEmpty();
            if (ready && state == GameState.WAITING) {
                state = GameState.BETTING; usedResets.clear(); roundId = UUID.randomUUID();
                timerTicks = automatic && !finalMode ? bettingSeconds * 2 : -1;
                broadcast(automatic && !finalMode ? "§eСтавки открыты. Автостарт через " + bettingSeconds + " с." : "§eКоманды готовы. Ожидаем ручного старта.");
            } else if (!ready && (state == GameState.BETTING || state == GameState.COUNTDOWN)) {
                state = GameState.WAITING; timerTicks = -1; broadcast("§eОжидание обеих команд.");
            }
            updateHolograms();
        }

        void beginCountdown(Player initiator) {
            if (state != GameState.BETTING || red.isEmpty() || blue.isEmpty()) { if (initiator != null) send(initiator, "§cДля старта нужны обе команды."); return; }
            List<String> errors = validationErrors();
            if (!errors.isEmpty()) { if (initiator != null) { send(initiator, "§cАрена не готова:"); errors.forEach(error -> sendBare(initiator, "§c- " + error)); } return; }
            state = GameState.COUNTDOWN; timerTicks = countdownSeconds * 2; originalRed.clear(); originalRed.addAll(red); originalBlue.clear(); originalBlue.addAll(blue);
            teleportTeam(red, spawnRed1, spawnRed2); teleportTeam(blue, spawnBlue1, spawnBlue2); broadcast("§eБой начнётся через " + countdownSeconds + " с. Урон заблокирован.");
        }

        void startFight() {
            if (state != GameState.COUNTDOWN) return; state = GameState.FIGHTING; timerTicks = -1; fightTicks = 0;
            broadcast("§c§l⚔ БОЙ! ⚔ §eСтавки закрыты."); title("§c§l⚔ БОЙ! ⚔", bettingEnabled ? "§eСтавки закрыты" : ""); sound(Sound.EVENT_RAID_HORN);
        }

        void teleportTeam(Set<UUID> team, Location first, Location second) {
            int index = 0, size = team.size();
            for (UUID uuid : team) { Player player = Bukkit.getPlayer(uuid); if (player == null) continue; Location target = distributedSpawn(first, second, index++, size); if (target != null) player.teleport(target); }
        }

        void voluntaryLeave(Player player) { removeParticipant(player.getUniqueId(), state == GameState.FIGHTING ? "сдался — техническое поражение" : "вышел из команды"); if (!player.isDead()) restoreParticipantInventory(player, "команда /arena leave"); }
        void removeParticipant(UUID uuid, String reason) {
            boolean existed = red.remove(uuid) | blue.remove(uuid); if (!existed) return; removeBar(uuid); Player player = Bukkit.getPlayer(uuid);
            if (player != null) send(player, "§eВы покинули арену: " + reason + "."); if (state == GameState.FIGHTING) checkWinner(); else updatePhase();
        }
        void eliminate(UUID uuid, String reason) { removeParticipant(uuid, reason); }
        void checkWinner() {
            if (state != GameState.FIGHTING) return;
            if (red.isEmpty() && blue.isEmpty()) endDuel(null, null); else if (red.isEmpty()) endDuel(originalBlue, originalRed); else if (blue.isEmpty()) endDuel(originalRed, originalBlue);
        }

        void endDuel(Set<UUID> winners, Set<UUID> losers) {
            state = GameState.ENDING; boolean wasFinal = finalMode; Set<UUID> all = new HashSet<>(); all.addAll(originalRed); all.addAll(originalBlue);
            for (UUID uuid : all) { Player online = Bukkit.getPlayer(uuid); if (online != null && !online.isDead()) restoreParticipantInventory(online, "завершение боя"); }
            double winTotal = winners == null ? 0.0 : (winners.equals(originalRed) ? total(redBets) : total(blueBets));
            Map<UUID, Double> winningBets = winners == null ? Map.of() : (winners.equals(originalRed) ? redBets : blueBets);
            double pot = total(redBets) + total(blueBets), distributable = pot * (1.0 - commissionPercent / 100.0);
            UUID round = roundId;
            double paidOut = 0.0;
            if (winTotal > 0.0) for (Map.Entry<UUID, Double> entry : winningBets.entrySet()) {
                double payout = roundTenth(entry.getValue() / winTotal * distributable); String playerName = playerName(entry.getKey());
                if (useVault) payMoney(name, round, entry.getKey(), playerName, payout, BetTicket.Purpose.BET);
                else payOrQueue(entry.getKey(), playerName, payout, false);
                paidOut += payout;
                database.recordBet(entry.getKey(), playerName, true, Math.max(0.0, payout - entry.getValue()));
            }
            // Всё, что осталось в кассе после выплат, — комиссия казино. В
            // Vault она просто исчезала; в ledger у денег обязан быть
            // владелец, поэтому она уходит в казну сервера отдельной
            // проводкой. Считаем остатком, а не процентом от пота: так
            // копейки округления выплат не повисают на счёте арены навсегда.
            double rake = roundTenth(pot - paidOut);
            if (useVault && rake > 0.0) {
                money.commission(name, round, rake).whenComplete((result, error) -> onMain(() -> {
                    if (!succeeded(result, error)) {
                        getLogger().warning("Комиссия " + money(rake) + " осталась на счёте арены " + name
                                + ": " + describe(result, error));
                    }
                }));
            }
            Map<UUID, Double> losingBets = winners == null ? Map.of() : (winners.equals(originalRed) ? blueBets : redBets);
            for (UUID uuid : losingBets.keySet()) database.recordBet(uuid, playerName(uuid), false, 0.0);
            double championShare = 0.0;
            if (finalMode && winners != null && !winners.isEmpty()) {
                championShare = roundTenth(finalPool / winners.size()); lastChampions.clear();
                for (UUID uuid : winners) {
                    String playerName = playerName(uuid); lastChampions.add(playerName);
                    if (useVault) payMoney(name, round, uuid, playerName, championShare, BetTicket.Purpose.FINAL);
                    else payOrQueue(uuid, playerName, championShare, false);
                }
                finalPool = 0.0; finalMode = false; saveArena(this);
            }
            if (winners == null) refundAllBets(); else {
                for (UUID uuid : winners) database.recordMatch(uuid, playerName(uuid), true, championShare);
                for (UUID uuid : losers) database.recordMatch(uuid, playerName(uuid), false, 0.0);
                awardWinnerExperience(winners, wasFinal);
            }
            recovery.clearArenaBets(name); redBets.clear(); blueBets.clear(); usedResets.clear();
            String winnerName = winners == null ? "§eНичья" : winners.equals(originalRed) ? "§cКрасные" : "§9Синие";
            broadcast("§6Победитель: " + winnerName + " §7(" + duration() + ")"); title(winnerName + " §fпобедили!", "§eРезультаты и выплаты сохранены");
            Bukkit.getScheduler().runTaskLater(GladiatorArena.this, () -> resetAfterEnd(all), 100L); updateHolograms();
        }

        void resetAfterEnd(Set<UUID> all) {
            for (UUID uuid : all) { Player player = Bukkit.getPlayer(uuid); if (player != null && specSpawn != null) player.teleport(specSpawn); removeBar(uuid); }
            red.clear(); blue.clear(); originalRed.clear(); originalBlue.clear(); state = GameState.WAITING; fightTicks = 0;
        }

        void awardWinnerExperience(Set<UUID> winners, boolean finalFight) {
            int amount = experienceReward(finalFight, winnerExperience, finalWinnerExperience);
            if (amount <= 0) return;
            for (UUID uuid : winners) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && !player.isDead() && !recovery.hasInventory(uuid)) {
                    experienceMode.give(player, amount);
                    send(player, "§aНаграда за " + (finalFight ? "финал" : "победу") + ": §e" + amount + " " + experienceMode.displayName(amount) + ".");
                } else {
                    recovery.queueExperience(uuid, amount, experienceMode == ExperienceMode.LEVELS);
                }
            }
        }

        void stop(String reason, boolean refund) {
            if (state == GameState.WAITING && red.isEmpty() && blue.isEmpty() && redBets.isEmpty() && blueBets.isEmpty()) return;
            broadcast("§cМатч " + reason + "."); if (refund) refundAllBets(); Set<UUID> participants = new HashSet<>(); participants.addAll(red); participants.addAll(blue);
            for (UUID uuid : participants) { Player player = Bukkit.getPlayer(uuid); if (player != null && !player.isDead()) restoreParticipantInventory(player, "остановка матча"); removeBar(uuid); }
            recovery.clearArenaBets(name); red.clear(); blue.clear(); originalRed.clear(); originalBlue.clear(); redBets.clear(); blueBets.clear(); state = GameState.WAITING; timerTicks = -1; fightTicks = 0; updateHolograms();
        }

        void shutdown() {
            stop("остановлен при выключении плагина", true);
            for (UUID uuid : new ArrayList<>(spectators.keySet())) if (spectators.get(uuid) == this) { Player player = Bukkit.getPlayer(uuid); if (player != null) leaveSpectator(player, false); }
            removeHolograms();
        }

        /**
         * Принять ставку.
         *
         * Предметный режим остаётся мгновенным: слиток изымается из руки, и
         * ответа ждать не от кого. Денежный уходит в Core и возвращается
         * асинхронно — MariaDB нельзя трогать из основного потока, — поэтому
         * ставка учитывается только после подтверждённой фиксации.
         */
        void placeBet(Player player, boolean onRed) {
            if (!bettingEnabled || state != GameState.BETTING) { send(player, "§cСтавки сейчас закрыты."); return; }
            Map<UUID, Double> team = onRed ? redBets : blueBets, other = onRed ? blueBets : redBets;
            if (other.getOrDefault(player.getUniqueId(), 0.0) > 0.0) { send(player, "§cНельзя ставить на обе команды."); return; }
            double value = useVault ? vaultBetStep : heldCurrencyValue(player), current = team.getOrDefault(player.getUniqueId(), 0.0);
            if (value < minBet || current + value > maxBet) { send(player, "§cСтавка должна быть от " + money(minBet) + " до " + money(maxBet) + "."); return; }

            if (!useVault) {
                if (!takeCurrencyItem(player)) return;
                acceptBet(player, onRed, value, roundTenth(current + value));
                return;
            }
            if (!moneyMode()) { player.sendMessage(locales.text("messages.vault-unavailable")); return; }
            // Резерв идёт не мгновенно, а кнопка нажимается быстрее ответа.
            // Без этой отметки два клика дали бы два списания под одну ставку.
            if (!pendingWagers.add(player.getUniqueId())) { send(player, "§eСтавка уже обрабатывается."); return; }

            UUID operation = UUID.randomUUID();
            UUID round = roundId;
            money.reserve(operation, player.getUniqueId(), name, BetTicket.Purpose.BET, value)
                    .whenComplete((result, error) -> onMain(() ->
                            onWagerReserved(player, onRed, value, operation, round, result, error)));
        }

        /** Резерв получен: записать билет и только потом двигать деньги. */
        private void onWagerReserved(Player player, boolean onRed, double value, UUID operation,
                                     UUID round, ovh.aurumgg.core.api.HoldResult result, Throwable error) {
            if (!heldOk(result, error)) {
                pendingWagers.remove(player.getUniqueId());
                if (result != null && result.status() == ovh.aurumgg.core.api.HoldResult.Status.INSUFFICIENT_FUNDS) {
                    send(player, "§cНедостаточно средств: нужно " + money(value) + currency());
                } else {
                    send(player, "§cПлатёж отклонён.");
                    if (error != null) getLogger().log(java.util.logging.Level.WARNING, "Резерв ставки не удался", error);
                }
                return;
            }
            BetTicket ticket = BetTicket.of(operation, player.getUniqueId(), name,
                    BetTicket.Purpose.BET, result.hold().orElseThrow());
            // Ставки уже закрылись или раунд сменился, пока шёл резерв —
            // отпускаем деньги, не тронув их.
            if (state != GameState.BETTING || !round.equals(roundId) || !player.isOnline()) {
                pendingWagers.remove(player.getUniqueId());
                money.release(ticket);
                send(player, "§cСтавки закрылись раньше, чем прошёл платёж.");
                return;
            }
            betJournal.open(ticket);
            money.capture(ticket).whenComplete((captured, failure) -> onMain(() ->
                    onWagerCaptured(player, onRed, value, ticket, round, captured, failure)));
        }

        /** Деньги на счёте арены: учесть ставку и закрыть билет. */
        private void onWagerCaptured(Player player, boolean onRed, double value, BetTicket ticket,
                                     UUID round, ovh.aurumgg.core.api.HoldResult result, Throwable error) {
            pendingWagers.remove(player.getUniqueId());
            if (!capturedOk(result, error)) {
                money.release(ticket).whenComplete((released, ignored) -> onMain(() -> betJournal.close(ticket)));
                send(player, "§cПлатёж отклонён.");
                if (error != null) getLogger().log(java.util.logging.Level.WARNING, "Фиксация ставки не удалась", error);
                return;
            }
            if (!round.equals(roundId)) {
                // Раунд сменился между фиксацией и учётом: деньги уже у арены,
                // и вернуть их надо явно — ставка в этот бой не попадёт.
                money.refundTicket(ticket).whenComplete((refund, ignored) -> onMain(() -> betJournal.close(ticket)));
                send(player, "§cСтавки закрылись раньше, чем прошёл платёж. Деньги возвращены.");
                return;
            }
            Map<UUID, Double> team = onRed ? redBets : blueBets;
            double newValue = roundTenth(team.getOrDefault(player.getUniqueId(), 0.0) + value);
            acceptBet(player, onRed, value, newValue);
            betJournal.close(ticket);
        }

        /** Общий хвост обоих режимов: учесть ставку и сказать об этом. */
        private void acceptBet(Player player, boolean onRed, double value, double newValue) {
            (onRed ? redBets : blueBets).put(player.getUniqueId(), newValue);
            recovery.saveBet(name, onRed ? "red" : "blue", player.getUniqueId(), player.getName(), newValue, useVault);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            send(player, "§aСтавка на " + (onRed ? "§cкрасных" : "§9синих") + "§a принята: §f" + money(value) + currency() + "§a. Всего поставлено: §f" + money(newValue) + currency() + "§a.");
            updateHolograms();
        }

        void cancelBet(Player player) {
            if (state != GameState.BETTING) { send(player, "§cОтмена доступна только на этапе ставок."); return; }
            UUID uuid = player.getUniqueId(); if (usedResets.contains(uuid)) { send(player, "§cВы уже отменяли ставку."); return; }
            double amount = redBets.getOrDefault(uuid, 0.0) + blueBets.getOrDefault(uuid, 0.0);
            if (amount <= 0.0) { send(player, "§eУ вас нет ставки."); return; }
            if (useVault && !moneyMode()) { player.sendMessage(locales.text("messages.vault-unavailable")); return; }

            // Ставку снимаем ДО выплаты и сразу помечаем отмену использованной:
            // иначе второй клик успел бы попросить возврат ещё раз, пока идёт
            // первый. Если перевод не пройдёт, сумма ляжет в очередь выплат —
            // она всё равно достанется игроку, просто позже.
            redBets.remove(uuid); blueBets.remove(uuid); usedResets.add(uuid);
            recovery.removeBet(name, "red", uuid); recovery.removeBet(name, "blue", uuid);
            if (useVault) refundMoney(name, roundId, uuid, player.getName(), amount);
            else RecoveryStore.giveItems(player, amount, mainCurrency, subCurrency);
            send(player, "§aСтавка возвращена: " + money(amount) + currency()); updateHolograms();
        }

        void refundAllBets() {
            Map<UUID, Double> all = new HashMap<>(redBets); blueBets.forEach((uuid, amount) -> all.merge(uuid, amount, Double::sum));
            UUID round = roundId;
            all.forEach((uuid, amount) -> {
                if (useVault) refundMoney(name, round, uuid, playerName(uuid), amount);
                else payOrQueue(uuid, playerName(uuid), amount, false);
            });
            redBets.clear(); blueBets.clear(); recovery.clearArenaBets(name);
        }

        /**
         * Взнос в финальную кассу.
         *
         * Деньги ложатся на ОТДЕЛЬНЫЙ счёт {@code ARENA_ESCROW:final:<арена>}:
         * призовой пул не должен смешиваться с удержаниями зрителей, иначе
         * возврат ставок при ничьей залез бы в чужие деньги.
         */
        void contributeFinal(Player player) {
            if (state != GameState.WAITING) { send(player, "§cФинальная касса заблокирована."); return; }
            double value = useVault ? vaultBetStep : heldCurrencyValue(player);
            if (!useVault) {
                if (!takeCurrencyItem(player)) return;
                acceptContribution(value, player);
                return;
            }
            if (!moneyMode()) { player.sendMessage(locales.text("messages.vault-unavailable")); return; }
            if (!pendingWagers.add(player.getUniqueId())) { send(player, "§eВзнос уже обрабатывается."); return; }

            UUID operation = UUID.randomUUID();
            money.reserve(operation, player.getUniqueId(), name, BetTicket.Purpose.FINAL, value)
                    .whenComplete((result, error) -> onMain(() -> {
                        if (!heldOk(result, error)) {
                            pendingWagers.remove(player.getUniqueId());
                            send(player, result != null && result.status() == ovh.aurumgg.core.api.HoldResult.Status.INSUFFICIENT_FUNDS
                                    ? "§cНедостаточно средств: нужно " + money(value) + currency()
                                    : "§cПлатёж отклонён.");
                            return;
                        }
                        BetTicket ticket = BetTicket.of(operation, player.getUniqueId(), name,
                                BetTicket.Purpose.FINAL, result.hold().orElseThrow());
                        betJournal.open(ticket);
                        money.capture(ticket).whenComplete((captured, failure) -> onMain(() -> {
                            pendingWagers.remove(player.getUniqueId());
                            if (!capturedOk(captured, failure)) {
                                money.release(ticket).whenComplete((released, ignored) ->
                                        onMain(() -> betJournal.close(ticket)));
                                send(player, "§cПлатёж отклонён.");
                                return;
                            }
                            acceptContribution(value, player);
                            betJournal.close(ticket);
                        }));
                    }));
        }

        private void acceptContribution(double value, Player player) {
            finalPool = roundTenth(finalPool + value); saveArena(this); updateHolograms();
            send(player, "§aВзнос принят. Пул: " + money(finalPool) + currency());
        }

        void exchange(Player player) {
            if (useVault) { send(player, "§eПри Vault размен не нужен."); return; }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == mainCurrency && hand.getAmount() >= 1) { hand.setAmount(hand.getAmount() - 1); giveMaterial(player, subCurrency, 10); }
            else if (hand.getType() == subCurrency && hand.getAmount() >= 10) { hand.setAmount(hand.getAmount() - 10); giveMaterial(player, mainCurrency, 1); }
            else send(player, "§cНужен 1 основной или 10 мелких предметов валюты.");
        }

        void tick() {
            tickCounter++; if (state == GameState.FIGHTING) fightTicks++;
            if ((state == GameState.BETTING || state == GameState.COUNTDOWN) && timerTicks > 0) {
                timerTicks--; if (timerTicks % 2 == 0 && timerTicks <= 20) broadcast("§e" + (state == GameState.BETTING ? "До закрытия ставок: " : "До боя: ") + timerTicks / 2 + " с.");
                if (timerTicks == 0) { if (state == GameState.BETTING) beginCountdown(null); else startFight(); }
            }
            for (UUID uuid : new ArrayList<>(red)) checkBoundary(uuid); for (UUID uuid : new ArrayList<>(blue)) checkBoundary(uuid);
            updateBossBars(); if (tickCounter % 2 == 0) updateScoreboards();
        }

        void checkBoundary(UUID uuid) {
            Player player = Bukkit.getPlayer(uuid); if (player != null && contains(player.getLocation())) return;
            boolean technical = state == GameState.FIGHTING && boundaryLoss; removeParticipant(uuid, technical ? "покинул границу — техническое поражение" : "покинул арену");
            if (player != null && !player.isDead()) restoreParticipantInventory(player, "выход за границу арены");
        }

        void updateBossBars() {
            List<Player> viewers = playersInRadius();
            for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
                Player fighter = Bukkit.getPlayer(entry.getKey()); BossBar bar = entry.getValue();
                if (fighter == null) { bar.removeAll(); continue; }
                double max = Objects.requireNonNull(fighter.getAttribute(Attribute.MAX_HEALTH)).getValue(); bar.setProgress(Math.max(0.0, Math.min(1.0, fighter.getHealth() / max)));
                Set<UUID> wanted = new HashSet<>();
                for (Player viewer : viewers) { boolean participant = isParticipant(viewer.getUniqueId());
                    if (showBar.equals("all") || showBar.equals("spectators") && !participant || showBar.equals("false") && viewer.hasPermission("arena.admin")) wanted.add(viewer.getUniqueId()); }
                for (Player current : new ArrayList<>(bar.getPlayers())) if (!wanted.contains(current.getUniqueId())) bar.removePlayer(current);
                for (Player viewer : viewers) if (wanted.contains(viewer.getUniqueId()) && !bar.getPlayers().contains(viewer)) bar.addPlayer(viewer);
            }
        }

        void updateScoreboards() {
            List<Player> viewers = playersInRadius();
            Set<UUID> currentViewers = new HashSet<>();
            for (Player player : viewers) {
                UUID playerUuid = player.getUniqueId();
                currentViewers.add(playerUuid);
                List<String> lines = sidebarLines(player);

                if (player.hasMetadata("aurumui.client")) {
                    publishArenaUi(player, name, lines);
                    // Вернуть доску, которая была у игрока до арены: мод
                    // рисует нашу панель сам, но TAB и чужие HUD трогать нельзя.
                    restoreScoreboard(player);
                    continue;
                }
                clearArenaUi(player, name);
                previousScoreboards.putIfAbsent(playerUuid, player.getScoreboard());
                Scoreboard scoreboard = arenaScoreboards.get(playerUuid);
                Objective objective;
                if (scoreboard == null) {
                    scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
                    objective = scoreboard.registerNewObjective("arena_bets", "dummy", locales.translate("§6§lАРЕНА: " + name.toUpperCase(Locale.ROOT)));
                    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                    if (sidebarHideScores && !ScoreboardNumberFormatter.hideScores(objective) && !sidebarFormatWarningLogged) {
                        sidebarFormatWarningLogged = true;
                        getLogger().warning("Сервер не поддерживает скрытие номеров строк sidebar. На Paper 26.2 они скрываются автоматически; Spigot 26.2 не предоставляет такой API.");
                    }
                    arenaScoreboards.put(playerUuid, scoreboard);
                } else {
                    objective = Objects.requireNonNull(scoreboard.getObjective("arena_bets"));
                    objective.setDisplayName(locales.translate("§6§lАРЕНА: " + name.toUpperCase(Locale.ROOT)));
                }
                if (!lines.equals(arenaScoreboardLines.get(playerUuid))) {
                    for (String entry : new HashSet<>(scoreboard.getEntries())) scoreboard.resetScores(entry);
                    int score = lines.size(); Set<String> unique = new HashSet<>(); for (String line : lines) { while (!unique.add(line)) line += "§r"; objective.getScore(line).setScore(score--); }
                    arenaScoreboardLines.put(playerUuid, List.copyOf(lines));
                }
                if (player.getScoreboard() != scoreboard) player.setScoreboard(scoreboard);
            }

            // Игрок отошёл от этой арены — убрать только её панель. Проверка
            // владельца не даёт одной арене стереть панель соседней.
            for (Map.Entry<UUID, String> entry : new ArrayList<>(arenaUiOwners.entrySet())) {
                if (!name.equals(entry.getValue()) || currentViewers.contains(entry.getKey())) continue;
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) clearArenaUi(player, name);
                else arenaUiOwners.remove(entry.getKey());
            }
        }

        List<String> sidebarLines(Player player) {
            List<String> lines = new ArrayList<>(List.of(
                    stateLine(), " ", "§cКрасные: §f" + red.size() + "/" + maxPlayers,
                    "§9Синие: §f" + blue.size() + "/" + maxPlayers));
            if (bettingEnabled) {
                double r = total(redBets), b = total(blueBets), sum = r + b;
                lines.add("  ");
                lines.add("§eБанк: §f" + money(sum) + currency());
                lines.add("§cК: §f" + odds(sum, r) + "x");
                lines.add("§9С: §f" + odds(sum, b) + "x");
                String personalBet = personalBetLine(player.getUniqueId());
                if (personalBet != null) lines.add(personalBet);
            }
            lines.replaceAll(locales::translate);
            return lines;
        }

        String stateLine() { return switch (state) {
            case WAITING -> "§7Ожидание игроков"; case BETTING -> timerTicks < 0 ? "§eОжидание ведущего" : "§eСтавки: " + timerTicks / 2 + " с";
            case COUNTDOWN -> "§cСтарт: " + timerTicks / 2 + " с"; case FIGHTING -> "§cБой: " + duration(); case ENDING -> "§6Матч завершён";
        }; }

        void updateHolograms() {
            loadHologramChunks();
            removeHolograms(); spawnBetHolo(redHopper, "red", "§cКрасные", total(redBets)); spawnBetHolo(blueHopper, "blue", "§9Синие", total(blueBets));
            spawnFinalHolograms();
            legacyHologramLocations.clear();
        }

        void updateFinalHolograms() {
            loadFinalHologramChunks();
            removeHolograms("final:");
            spawnFinalHolograms();
            legacyHologramLocations.clear();
        }

        void spawnFinalHolograms() {
            String champions = lastChampions.isEmpty() ? "§7Нет данных" : "§f" + String.join(", ", lastChampions);
            String text = finalStatsFormat.replace("%pool%", money(finalPool)).replace("%currency%", currency()).replace("%champions%", champions);
            for (int i = 0; i < finalStats.size(); i++) { FinalStatHolo holo = finalStats.get(i); spawnHologram(holo.location, text, "final:" + i, holo.scale, Display.Billboard.FIXED); }
        }

        void loadHologramChunks() {
            List<Location> locations = new ArrayList<>(legacyHologramLocations);
            locations.add(redHopper); locations.add(blueHopper);
            finalStats.forEach(holo -> locations.add(holo.location));
            loadChunks(locations);
        }

        void loadFinalHologramChunks() {
            List<Location> locations = new ArrayList<>(legacyHologramLocations);
            finalStats.forEach(holo -> locations.add(holo.location));
            loadChunks(locations);
        }

        void loadChunks(List<Location> locations) {
            Set<String> loaded = new HashSet<>();
            for (Location location : locations) {
                if (location == null || location.getWorld() == null) continue;
                int chunkX = location.getBlockX() >> 4, chunkZ = location.getBlockZ() >> 4;
                String key = location.getWorld().getUID() + ":" + chunkX + ":" + chunkZ;
                if (loaded.add(key)) location.getWorld().getChunkAt(chunkX, chunkZ).load();
            }
        }

        void spawnBetHolo(Location hopper, String team, String title, double amount) {
            if (hopper == null) return; double sum = total(redBets) + total(blueBets);
            spawnHologram(hopper.clone().add(0.5, 1.25, 0.5), title + "\n§f" + money(amount) + currency() + " §7(" + odds(sum, amount) + "x)", "bet:" + team, bettingHologramScale, Display.Billboard.CENTER);
        }

        void spawnHologram(Location location, String text, String id, float scale, Display.Billboard billboard) {
            if (location == null || location.getWorld() == null) return; TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
            display.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, name + ":" + id); display.setBillboard(billboard); display.setText(locales.translate(text));
            display.setSeeThrough(hologramsSeeThrough); display.setViewRange(hologramViewRange(hologramViewDistanceBlocks));
            Transformation transform = display.getTransformation(); transform.getScale().set(scale, scale, scale); display.setTransformation(transform);
            holograms.computeIfAbsent(id, ignored -> new HashSet<>()).add(display.getUniqueId());
        }

        void removeHolograms() {
            removeHolograms(null);
        }

        void removeHolograms(String typePrefix) {
            discoverHolograms();
            Iterator<Map.Entry<String, Set<UUID>>> iterator = holograms.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Set<UUID>> entry = iterator.next();
                if (typePrefix != null && !entry.getKey().startsWith(typePrefix)) continue;
                for (UUID uuid : entry.getValue()) {
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity != null) entity.remove();
                }
                iterator.remove();
            }
        }

        void discoverHolograms() {
            if (hologramsDiscovered) return;
            hologramsDiscovered = true;
            String ownedPrefix = name + ":";
            for (World world : Bukkit.getWorlds()) for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                String tag = display.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
                if (tag != null && tag.startsWith(ownedPrefix)) {
                    holograms.computeIfAbsent(tag.substring(ownedPrefix.length()), ignored -> new HashSet<>())
                            .add(display.getUniqueId());
                }
            }
        }

        void sendOdds(CommandSender sender) {
            double r = total(redBets), b = total(blueBets), sum = r + b;
            send(sender, "§e" + name + ": банк §f" + money(sum) + currency() + "§7, §cКрасные " + odds(sum, r) + "x§7, §9Синие " + odds(sum, b) + "x§7, комиссия " + money(commissionPercent) + "%");
            if (sender instanceof Player player) {
                double personalRed = redBets.getOrDefault(player.getUniqueId(), 0.0), personalBlue = blueBets.getOrDefault(player.getUniqueId(), 0.0);
                if (personalRed > 0.0) send(sender, "§7Ваша ставка: §cкрасные §f" + money(personalRed) + currency() + "§7, текущий коэффициент §f" + odds(sum, r) + "x§7.");
                else if (personalBlue > 0.0) send(sender, "§7Ваша ставка: §9синие §f" + money(personalBlue) + currency() + "§7, текущий коэффициент §f" + odds(sum, b) + "x§7.");
            }
        }
        String personalBetLine(UUID uuid) {
            double onRed = redBets.getOrDefault(uuid, 0.0), onBlue = blueBets.getOrDefault(uuid, 0.0);
            if (onRed > 0.0) return "§7Ваша: §c" + money(onRed) + currency();
            if (onBlue > 0.0) return "§7Ваша: §9" + money(onBlue) + currency();
            return null;
        }
        void sendStatus(CommandSender sender) {
            send(sender, "§e" + name + " §7— " + state + ", §c" + red.size() + "§7:§9" + blue.size() + "§7, банк §f" + money(total(redBets) + total(blueBets)) + currency() + "§7, radius " + radius);
            send(sender, "§7Опыт каждому победителю: бой §e" + winnerExperience + "§7, финал §6" + finalWinnerExperience + "§7 (" + experienceMode.configValue + ").");
        }
        void validate(CommandSender sender) { List<String> errors = validationErrors(); if (errors.isEmpty()) send(sender, "§aАрена полностью готова."); else { send(sender, "§cПроблемы настройки:"); errors.forEach(error -> sendBare(sender, "§c- " + error)); } }

        List<String> validationErrors() {
            List<String> errors = new ArrayList<>(); if (redBtn == null) errors.add("не задана кнопка красных"); if (blueBtn == null) errors.add("не задана кнопка синих");
            if (spawnRed1 == null) errors.add("не задан spawnred1"); if (spawnBlue1 == null) errors.add("не задан spawnblue1");
            if (maxPlayers > 1 && spawnRed2 == null) errors.add("не задан spawnred2"); if (maxPlayers > 1 && spawnBlue2 == null) errors.add("не задан spawnblue2");
            if (specSpawn == null) errors.add("не задан setspawn"); if (bettingEnabled && (redHopper == null || blueHopper == null)) errors.add("не заданы обе воронки ставок");
            if (hasOverlap(this)) errors.add("радиус пересекает другую арену"); if (useVault && !moneyMode()) errors.add("экономика AurumCore недоступна"); return errors;
        }

        void toggleFinal(Player player) {
            if (state != GameState.WAITING || !red.isEmpty() || !blue.isEmpty()) { send(player, "§cФинал переключается только на пустой арене."); return; }
            finalMode = !finalMode; send(player, (finalMode ? "§6Финальный режим включён." : "§eФинальный режим выключен."));
        }
        List<Player> playersInRadius() { return center.getWorld().getPlayers().stream().filter(player -> contains(player.getLocation())).toList(); }
        double total(Map<UUID, Double> bets) { return bets.values().stream().mapToDouble(Double::doubleValue).sum(); }
        void broadcast(String message) { playersInRadius().forEach(player -> send(player, message)); }
        void title(String title, String subtitle) { playersInRadius().forEach(player -> player.sendTitle(locales.translate(title), locales.translate(subtitle), 5, 40, 10)); }
        void sound(Sound sound) { playersInRadius().forEach(player -> player.playSound(player.getLocation(), sound, 1f, 1f)); }
        String duration() { int seconds = fightTicks / 2; return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }
        void removeBar(UUID uuid) { BossBar bar = bars.remove(uuid); if (bar != null) bar.removeAll(); }
    }

    /**
     * Забрать предмет-валюту из руки. Только для предметного режима.
     *
     * Денежный режим сюда не заходит вовсе: там списание идёт резервом в Core
     * и завершается асинхронно, а вернуть из такого вызова boolean нельзя, не
     * соврав вызывающему.
     */
    private boolean takeCurrencyItem(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if ((hand.getType() != mainCurrency && hand.getType() != subCurrency) || hand.getAmount() < 1) {
            send(player, "§cВозьмите валюту в основную руку."); return false;
        }
        hand.setAmount(hand.getAmount() - 1); return true;
    }

    /**
     * Выдать игроку выигрыш или возврат.
     *
     * Предметный режим отдаёт слитки на месте. Денежный уходит в ledger со
     * СТАБИЛЬНЫМ ключом: при потере ответа выплата ляжет в очередь и будет
     * повторена с тем же ключом, а Core распознает повтор и не заплатит
     * дважды. Офлайн-игрок здесь ничем не отличается от онлайнового — у счёта
     * в ledger нет требования, чтобы владелец был в сети.
     */
    private void payOrQueue(UUID uuid, String name, double amount, boolean vault) {
        if (!Double.isFinite(amount) || amount <= 0.0) return;
        if (!vault) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) RecoveryStore.giveItems(online, amount, mainCurrency, subCurrency);
            else recovery.queuePayout(uuid, name, amount, false);
            return;
        }
        getLogger().warning("Денежная выплата без ключа идемпотентности для " + name + " отложена.");
        recovery.queuePayout(uuid, name, amount, true);
    }

    /**
     * Денежная выплата из кассы арены с ключом, по которому повтор безопасен.
     *
     * Неудача — не потеря: сумма ложится в очередь вместе с тем же ключом и
     * повторяется при входе игрока и при следующем старте сервера.
     */
    void payMoney(String arena, UUID round, UUID uuid, String name, double amount,
                  BetTicket.Purpose source) {
        if (!Double.isFinite(amount) || amount <= 0.0) return;
        String key = "arena-payout:" + source.name().toLowerCase(Locale.ROOT) + ":" + round + ":" + uuid;
        money.payout(arena, round, uuid, source, amount).whenComplete((result, error) -> onMain(() -> {
            if (succeeded(result, error)) return;
            recovery.queueMoneyPayout(uuid, name, amount, key, arena, source.name());
            getLogger().warning("Выплата " + money(amount) + " игроку " + name + " отложена: "
                    + describe(result, error));
        }));
    }

    /** Возврат накопленной ставки — тот же приём, свой ключ. */
    void refundMoney(String arena, UUID round, UUID uuid, String name, double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) return;
        String key = "arena-refund:" + round + ":" + uuid;
        money.refundStake(arena, round, uuid, amount).whenComplete((result, error) -> onMain(() -> {
            if (succeeded(result, error)) return;
            recovery.queueMoneyPayout(uuid, name, amount, key, arena, BetTicket.Purpose.BET.name());
            getLogger().warning("Возврат " + money(amount) + " игроку " + name + " отложен: "
                    + describe(result, error));
        }));
    }

    private static boolean succeeded(ovh.aurumgg.core.api.TransactionResult result, Throwable error) {
        return error == null && result != null
                && (result.status() == ovh.aurumgg.core.api.TransactionResult.Status.SUCCESS
                || result.status() == ovh.aurumgg.core.api.TransactionResult.Status.DUPLICATE);
    }

    private static String describe(ovh.aurumgg.core.api.TransactionResult result, Throwable error) {
        if (error != null) return error.getClass().getSimpleName() + ": " + error.getMessage();
        return result == null ? "нет ответа" : result.status() + " " + result.message();
    }

    private void claimPending(Player player) {
        double items = recovery.claimItemPayout(player, mainCurrency, subCurrency);
        if (items > 0.0) send(player, "§aПолучена отложенная выплата: " + money(items) + currency());
        drainMoneyPayouts(player.getUniqueId(), player.getName());
        RecoveryStore.StoredExperience experience = recovery.claimExperience(player);
        if (!experience.isEmpty()) {
            send(player, "§aПолучена отложенная награда: §e" + experience.points() + " очков опыта§a, §e" + experience.levels() + " уровней§a.");
        }
    }

    private boolean restoreParticipantInventory(Player player, String context) {
        if (!recovery.hasInventory(player.getUniqueId())) return false;
        if (player.isDead()) return false;
        if (recovery.restoreInventory(player)) return true;
        send(player, "§cНе удалось восстановить инвентарь. Резервная копия сохранена; обратитесь к администратору.");
        getLogger().warning("Инвентарь " + player.getName() + " не восстановлен (" + context + "); запись оставлена в recovery.yml.");
        return false;
    }

    private double heldCurrencyValue(Player player) { Material type = player.getInventory().getItemInMainHand().getType(); return type == mainCurrency ? 1.0 : type == subCurrency ? 0.1 : 0.0; }
    private String currency() { return useVault ? vaultSymbol : " зол."; }
    private void restoreScoreboard(Player player) {
        UUID playerUuid = player.getUniqueId();
        Scoreboard previous = previousScoreboards.remove(playerUuid);
        arenaScoreboards.remove(playerUuid);
        arenaScoreboardLines.remove(playerUuid);
        if (previous != null) player.setScoreboard(previous);
    }
    private void publishArenaUi(Player player, String arenaName, List<String> lines) {
        arenaUiOwners.put(player.getUniqueId(), arenaName);
        player.setMetadata("aurumui.arena", new FixedMetadataValue(this, Map.of(
                "id", "arena:" + arenaName.toLowerCase(Locale.ROOT),
                "priority", 100,
                "title", locales.translate("§6§lАРЕНА: " + arenaName.toUpperCase(Locale.ROOT)),
                "lines", List.copyOf(lines))));
    }
    private void clearArenaUi(Player player, String arenaName) {
        if (!arenaName.equals(arenaUiOwners.get(player.getUniqueId()))) return;
        arenaUiOwners.remove(player.getUniqueId());
        player.removeMetadata("aurumui.arena", this);
    }
    private boolean hasOverlap(Arena arena) {
        for (Arena other : arenas.values()) { if (other == arena || !other.center.getWorld().equals(arena.center.getWorld())) continue; double limit = other.radius + arena.radius; if (other.center.distanceSquared(arena.center) < limit * limit) return true; }
        return false;
    }
    private void warnOverlaps() { for (Arena arena : arenas.values()) if (hasOverlap(arena)) getLogger().warning("Арена " + arena.name + " пересекает другую арену."); }
    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) { ProjectileSource source = projectile.getShooter(); if (source instanceof Player player) return player; }
        return null;
    }
    private static Location distributedSpawn(Location first, Location second, int index, int size) {
        if (first == null) return null; if (index == 0 || second == null) return first.clone(); if (index == 1) return second.clone();
        Location middle = first.clone().add(second).multiply(0.5); double angle = 2.0 * Math.PI * (index - 2) / Math.max(1, size - 2); return middle.add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
    }
    private static boolean sameBlock(Location first, Location second) {
        return first != null && second != null && first.getWorld() != null && first.getWorld().equals(second.getWorld()) && first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY() && first.getBlockZ() == second.getBlockZ();
    }
    private static String normalizeShowBar(String value) { return Set.of("all", "spectators", "false").contains(value == null ? "" : value.toLowerCase(Locale.ROOT)) ? value.toLowerCase(Locale.ROOT) : "spectators"; }
    private static boolean parseToggle(String[] args, int index, boolean current) { if (args.length <= index || args[index].equalsIgnoreCase("toggle")) return !current; return Boolean.parseBoolean(args[index]); }
    private static String cycle(String current, List<String> values) { int index = values.indexOf(current); return values.get((index + 1) % values.size()); }
    private static Material material(String name, Material fallback) { Material result = name == null ? null : Material.matchMaterial(name); return result == null || result.isAir() ? fallback : result; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double finite(double value, double fallback, double min, double max) { if (!Double.isFinite(value)) return fallback; return Math.max(min, Math.min(max, value)); }
    private static String money(double value) { return String.format(Locale.US, "%.1f", value); }
    private static double roundTenth(double value) { return Math.floor(value * 10.0 + 1.0E-9) / 10.0; }
    private String odds(double total, double side) { return formatOdds(total, side, commissionPercent); }
    static String formatOdds(double total, double side, double commission) { return side <= 0.0 ? "—" : money(total * (1.0 - commission / 100.0) / side); }
    static int experienceReward(boolean finalFight, int regularReward, int finalReward) { return finalFight ? finalReward : regularReward; }
    static float hologramViewRange(float blocks) { return blocks / 64.0f; }
    static boolean matchesHologramType(String id, String arenaName, String typePrefix) {
        if (id == null) return false;
        String ownedPrefix = arenaName + ":";
        return id.startsWith(ownedPrefix) && (typePrefix == null || id.startsWith(ownedPrefix + typePrefix));
    }
    static <T, K> int keepNewestByKey(List<T> values, java.util.function.Function<T, K> keyFunction) {
        Set<K> keys = new HashSet<>(); List<T> retained = new ArrayList<>();
        for (int index = values.size() - 1; index >= 0; index--) {
            T value = values.get(index); if (keys.add(keyFunction.apply(value))) retained.add(value);
        }
        Collections.reverse(retained); int removed = values.size() - retained.size();
        values.clear(); values.addAll(retained); return removed;
    }
    private static String hologramBlockKey(FinalStatHolo holo) {
        Location location = holo.location;
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }
    private static String playerName(UUID uuid) { String name = Bukkit.getOfflinePlayer(uuid).getName(); return name == null ? uuid.toString().substring(0, 8) : name; }
    private static String formatLocation(Location location) { return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ(); }
    private static <T> List<String> filter(Collection<T> values, String prefix) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().map(String::valueOf).filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList(); }
    private static void giveMaterial(Player player, Material material, int amount) { Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, amount)); overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item)); }

    enum ExperienceMode {
        POINTS("points"), LEVELS("levels");

        final String configValue;
        ExperienceMode(String configValue) { this.configValue = configValue; }
        void give(Player player, int amount) { if (this == LEVELS) player.giveExpLevels(amount); else player.giveExp(amount); }
        String displayName(int amount) { return this == LEVELS ? "уровней" : "очков опыта"; }
        static boolean isValid(String value) { return value != null && (value.equalsIgnoreCase("points") || value.equalsIgnoreCase("levels")); }
        static ExperienceMode parse(String value) { return value != null && value.equalsIgnoreCase("levels") ? LEVELS : POINTS; }
    }

    private enum GameState { WAITING, BETTING, COUNTDOWN, FIGHTING, ENDING }
    private static final class ArenaGui implements InventoryHolder {
        final String arenaName; Inventory inventory; ArenaGui(String arenaName) { this.arenaName = arenaName; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class FinalStatHolo {
        final Location location; float scale; FinalStatHolo(Location location, float scale) { this.location = location; this.scale = scale; }
        String encode() { return location.getWorld().getName() + ";" + location.getX() + ";" + location.getY() + ";" + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch() + ";" + scale; }
        static FinalStatHolo parse(String encoded) {
            try { String[] p = encoded.split(";"); if (p.length < 7 || Bukkit.getWorld(p[0]) == null) return null;
                Location location = new Location(Bukkit.getWorld(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
                return new FinalStatHolo(location, (float) finite(Double.parseDouble(p[6]), 1.0, 0.1, 5.0));
            } catch (RuntimeException exception) { return null; }
        }
    }
}
