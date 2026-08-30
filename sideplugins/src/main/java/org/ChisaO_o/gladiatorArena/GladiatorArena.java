package org.ChisaO_o.gladiatorArena;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
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
import org.bukkit.potion.*;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Transformation;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

public final class GladiatorArena extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private String PREFIX = "§6[Арена] §r";
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, Arena> pendingRespawns = new HashMap<>();
    private final Map<UUID, Arena> spectators = new HashMap<>();
    private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
    private Economy economy;
    private boolean useVault;
    private boolean vaultReady;
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
    private boolean spectatorsEnabled;
    private String finalStatsFormat;
    private File kitsFile;
    private YamlConfiguration kits;
    private RecoveryStore recovery;
    private DatabaseManager database;
    private NamespacedKey hologramKey;
    private NamespacedKey guiActionKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        getDataFolder().mkdirs();
        hologramKey = new NamespacedKey(this, "hologram");
        guiActionKey = new NamespacedKey(this, "gui_action");
        recovery = new RecoveryStore(this);
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
            new GladiatorPlaceholders(this).register();
            getLogger().info("PlaceholderAPI подключён.");
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 10L, 10L);
        getLogger().info("GladiatorArena 1.1.0 включён. API 26.2, Java " + Runtime.version().feature() + ".");
    }

    @Override
    public void onDisable() {
        for (Arena arena : new ArrayList<>(arenas.values())) arena.shutdown();
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) leaveSpectator(player, false);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (recovery.hasInventory(player.getUniqueId())) recovery.restoreInventory(player);
            restoreScoreboard(player);
        }
        if (database != null) database.close();
    }

    private void loadSettings() {
        reloadConfig();
        PREFIX = getConfig().getString("messages.prefix", "§6[Арена] §r");
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
        spectatorsEnabled = getConfig().getBoolean("settings.spectators.enabled", true);
        finalStatsFormat = getConfig().getString("messages.final_stats", "§6§lЧЕМПИОНСКИЙ ПУЛ\n§eФонд: §f%pool% %currency%\n%champions%");
    }

    private void loadKits() {
        kitsFile = new File(getDataFolder(), "kits.yml");
        kits = YamlConfiguration.loadConfiguration(kitsFile);
        if (!kitsFile.exists()) {
            try { kits.save(kitsFile); }
            catch (IOException exception) { getLogger().log(Level.SEVERE, "Не удалось создать kits.yml", exception); }
        }
    }

    private void connectEconomy() {
        economy = null;
        vaultReady = false;
        if (!useVault) return;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null && Bukkit.getPluginManager().getPlugin("VaultUnlocked") == null) {
            getLogger().severe("В конфиге включён Vault, но Vault/VaultUnlocked не установлен. Денежные операции заблокированы.");
            return;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration != null) economy = registration.getProvider();
        vaultReady = economy != null;
        if (vaultReady) getLogger().info("Vault-экономика подключена: " + economy.getName());
        else getLogger().severe("В конфиге включён Vault, но провайдер Economy недоступен. Денежные операции заблокированы; перехода на предметы нет.");
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
            arena.finalPool = finite(getConfig().getDouble(base + ".finalPool", 0.0), 0.0, 0.0, 1_000_000_000_000.0);
            arena.lastChampions.addAll(getConfig().getStringList(base + ".lastChampions"));
            for (String encoded : getConfig().getStringList(base + ".finalStats")) {
                FinalStatHolo holo = FinalStatHolo.parse(encoded);
                if (holo != null) arena.finalStats.add(holo);
            }
            arenas.put(arena.name, arena);
            arena.updateHolograms();
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
        getConfig().set(base + ".finalPool", arena.finalPool);
        getConfig().set(base + ".lastChampions", arena.lastChampions);
        getConfig().set(base + ".finalStats", arena.finalStats.stream().map(FinalStatHolo::encode).toList());
        saveConfig();
    }

    private void recoverInterruptedBets() {
        List<RecoveryStore.StoredBet> bets = recovery.allBets();
        if (bets.isEmpty()) return;
        if (!getConfig().getBoolean("settings.refund_interrupted_bets", true)) {
            getLogger().warning("В recovery.yml осталось " + bets.size() + " незавершённых ставок; автовозврат отключён.");
            return;
        }
        for (RecoveryStore.StoredBet bet : bets) {
            payOrQueue(bet.player(), bet.playerName(), bet.amount(), bet.vault());
            recovery.removeBet(bet.arena(), bet.team(), bet.player());
        }
        getLogger().warning("Возвращено/поставлено в очередь незавершённых ставок: " + bets.size());
    }

    Arena arenaAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return arenas.values().stream().filter(a -> a.contains(location))
            .min(Comparator.comparingDouble(a -> a.center.distanceSquared(location))).orElse(null);
    }

    Arena arenaByName(String name) { return name == null ? null : arenas.get(name.toLowerCase(Locale.ROOT)); }
    DatabaseManager.PlayerStats stats(UUID uuid) { return database == null ? DatabaseManager.PlayerStats.EMPTY : database.get(uuid); }

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
            sender.sendMessage(PREFIX + "Арены: §e" + (arenas.isEmpty() ? "нет" : String.join("§7, §e", arenas.keySet()))); return true;
        }
        if (action.equals("stats")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
            if (target == null) sender.sendMessage(PREFIX + "§cИгрок не найден."); else showStats(sender, target);
            return true;
        }
        if (action.equals("odds")) {
            Arena arena = args.length >= 2 ? arenaByName(args[1]) : sender instanceof Player p ? arenaAt(p.getLocation()) : null;
            if (arena == null) sender.sendMessage(PREFIX + "§cУкажите арену: /arena odds <арена>"); else arena.sendOdds(sender);
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage(PREFIX + "§cЭта команда доступна только игроку."); return true; }
        if (action.equals("spectate")) {
            if (!player.hasPermission("arena.spectate")) return noPermission(player);
            startSpectating(player, args.length >= 2 ? arenaByName(args[1]) : arenaAt(player.getLocation())); return true;
        }
        if (action.equals("leave")) {
            if (!leaveSpectator(player, true)) {
                Arena participant = participantArena(player.getUniqueId());
                if (participant != null) participant.voluntaryLeave(player); else player.sendMessage(PREFIX + "§eВы не участвуете в арене.");
            }
            return true;
        }
        if (!player.hasPermission("arena.admin")) return noPermission(player);
        if (action.equals("create")) return createArena(player, args);
        if (action.equals("delete")) return deleteArena(player, args);
        if (action.equals("reload") || action.equals("restart")) {
            for (Arena arena : arenas.values()) arena.shutdown();
            loadSettings(); loadKits(); connectEconomy();
            if (database != null) database.close();
            database = new DatabaseManager(this); database.start(); loadArenas();
            player.sendMessage(PREFIX + "§aКонфигурация, Vault и база данных перезагружены."); return true;
        }
        Arena arena = args.length >= 2 && Set.of("status", "validate", "gui").contains(action) ? arenaByName(args[1]) : arenaAt(player.getLocation());
        if (arena == null) { player.sendMessage(PREFIX + "§cВы не в радиусе арены."); return true; }
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
            case "kit" -> { arena.kitEnabled = parseToggle(args, 1, arena.kitEnabled); changed(player, arena, "Комплект: " + arena.kitEnabled); }
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
            case "fstatsremove" -> { arena.finalStats.clear(); arena.updateHolograms(); changed(player, arena, "Финальные голограммы удалены"); }
            case "fstatsscale" -> scaleFinalHolo(player, arena, args);
            case "debug" -> debug(player, arena, args);
            default -> setTargetBlock(player, arena, action);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== GladiatorArena 1.1.0 ===");
        sender.sendMessage("§f/arena list | odds [арена] | stats [игрок] | spectate <арена> | leave");
        if (!sender.hasPermission("arena.admin")) return;
        sender.sendMessage("§eАдмин: §fcreate/delete, status, validate, gui, start, stop, reload");
        sender.sendMessage("§fsetred/setblue/sethost/setreset, sethopred/sethopblue, setfhop, bankomat");
        sender.sendMessage("§fsetspawn, spawnred1/2, spawnblue1/2, radius, maxplayers");
        sender.sendMessage("§fauto/manual, betting, kit, friendlyfire, showbar, final");
        sender.sendMessage("§ffinalstats, fstatsremove, fstatsscale, debug hologram");
    }

    private boolean createArena(Player player, String[] args) {
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9_-]{1,32}")) { player.sendMessage(PREFIX + "§cИспользование: /arena create <имя>"); return true; }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (arenas.containsKey(name)) { player.sendMessage(PREFIX + "§cАрена уже существует."); return true; }
        for (Arena other : arenas.values()) if (other.center.getWorld().equals(player.getWorld()) && other.center.distance(player.getLocation()) < other.radius + 100.0) {
            player.sendMessage(PREFIX + "§cНовая арена пересечётся с " + other.name + "."); return true;
        }
        Arena arena = new Arena(name, player.getLocation()); arenas.put(name, arena); saveArena(arena);
        player.sendMessage(PREFIX + "§aАрена " + name + " создана."); return true;
    }

    private boolean deleteArena(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(PREFIX + "§cИспользование: /arena delete <арена>"); return true; }
        Arena arena = arenaByName(args[1]);
        if (arena == null) { player.sendMessage(PREFIX + "§cАрена не найдена."); return true; }
        arena.shutdown(); arenas.remove(arena.name); getConfig().set("arenas." + arena.name, null); saveConfig();
        player.sendMessage(PREFIX + "§aАрена удалена вместе с её голограммами."); return true;
    }

    private void setNumber(Player player, Arena arena, String[] args, boolean players) {
        if (args.length < 2) { player.sendMessage(PREFIX + "§cУкажите число."); return; }
        try {
            int value = Integer.parseInt(args[1]);
            if (players) arena.maxPlayers = clamp(value, 1, maxTeamLimit);
            else {
                int old = arena.radius; arena.radius = clamp(value, 10, 500);
                if (hasOverlap(arena)) { arena.radius = old; player.sendMessage(PREFIX + "§cТакой радиус пересекает другую арену."); return; }
            }
            changed(player, arena, (players ? "Игроков в команде: " : "Радиус: ") + (players ? arena.maxPlayers : arena.radius));
        } catch (NumberFormatException exception) { player.sendMessage(PREFIX + "§cНужно целое число."); }
    }

    private void setTargetBlock(Player player, Arena arena, String action) {
        Set<String> supported = Set.of("setred", "setblue", "sethost", "setreset", "sethopred", "sethopblue", "setfhop", "bankomat");
        if (!supported.contains(action)) { player.sendMessage(PREFIX + "§cНеизвестная команда. /arena help"); return; }
        Block target = player.getTargetBlockExact(6);
        if (target == null) { player.sendMessage(PREFIX + "§cСмотрите на блок не дальше 6 блоков."); return; }
        Location location = target.getLocation();
        switch (action) {
            case "setred" -> arena.redBtn = location; case "setblue" -> arena.blueBtn = location; case "sethost" -> arena.hostBtn = location;
            case "setreset" -> arena.resetBtn = location; case "sethopred" -> arena.redHopper = location; case "sethopblue" -> arena.blueHopper = location;
            case "setfhop" -> arena.finalHopper = location; case "bankomat" -> arena.bankomat = location;
        }
        saveArena(arena); arena.updateHolograms(); player.sendMessage(PREFIX + "§aТочка " + action + " установлена: " + formatLocation(location));
    }

    private void setLocation(Player player, Arena arena, String field, Location location) {
        switch (field) {
            case "specSpawn" -> arena.specSpawn = location; case "spawnRed1" -> arena.spawnRed1 = location; case "spawnRed2" -> arena.spawnRed2 = location;
            case "spawnBlue1" -> arena.spawnBlue1 = location; case "spawnBlue2" -> arena.spawnBlue2 = location; case "redHopper" -> arena.redHopper = location;
            case "blueHopper" -> arena.blueHopper = location; case "finalHopper" -> arena.finalHopper = location;
        }
        saveArena(arena); arena.updateHolograms(); player.sendMessage(PREFIX + "§a" + field + (location == null ? " удалена." : " установлена."));
    }

    private void addFinalHolo(Player player, Arena arena) {
        Location location = player.getLocation().clone().add(0, 1.5, 0); location.setYaw(location.getYaw() + 180f); location.setPitch(0f);
        arena.finalStats.add(new FinalStatHolo(location, 1f)); saveArena(arena); arena.updateHolograms(); player.sendMessage(PREFIX + "§aГолограмма добавлена.");
    }

    private void scaleFinalHolo(Player player, Arena arena, String[] args) {
        if (args.length < 2) { player.sendMessage(PREFIX + "§cИспользование: /arena fstatsscale <0.1..5>"); return; }
        try {
            float scale = (float) finite(Double.parseDouble(args[1]), 1.0, 0.1, 5.0);
            FinalStatHolo nearest = arena.finalStats.stream().filter(h -> h.location.getWorld().equals(player.getWorld()))
                .min(Comparator.comparingDouble(h -> h.location.distanceSquared(player.getLocation()))).orElse(null);
            if (nearest == null || nearest.location.distanceSquared(player.getLocation()) > 25) { player.sendMessage(PREFIX + "§cНет голограммы в радиусе 5 блоков."); return; }
            nearest.scale = scale; saveArena(arena); arena.updateHolograms(); player.sendMessage(PREFIX + "§aМасштаб: " + scale);
        } catch (NumberFormatException exception) { player.sendMessage(PREFIX + "§cНекорректное число."); }
    }

    private void debug(Player player, Arena arena, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("hologram")) { player.sendMessage(PREFIX + "§e/arena debug hologram"); return; }
        TextDisplay nearest = player.getWorld().getNearbyEntities(player.getLocation(), 8.0, 8.0, 8.0).stream()
            .filter(TextDisplay.class::isInstance).map(TextDisplay.class::cast)
            .filter(entity -> entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.STRING))
            .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation()))).orElse(null);
        if (nearest == null) player.sendMessage(PREFIX + "§cГолограмма GladiatorArena не найдена в радиусе 8 блоков.");
        else { nearest.remove(); player.sendMessage(PREFIX + "§aБлижайшая голограмма удалена."); }
    }

    private void changed(Player player, Arena arena, String text) { saveArena(arena); arena.updateHolograms(); player.sendMessage(PREFIX + "§a" + text); }
    private boolean noPermission(CommandSender sender) { sender.sendMessage(getConfig().getString("messages.no_permission", "§cНедостаточно прав.")); return true; }

    private void showStats(CommandSender sender, Player target) {
        DatabaseManager.PlayerStats value = stats(target.getUniqueId());
        sender.sendMessage(PREFIX + "§eСтатистика " + target.getName() + ": §a" + value.wins() + " побед§7, §c" + value.losses()
            + " поражений§7, серия §f" + value.streak() + "§7, заработано §6" + money(value.earnings()));
        if (!database.isReady()) sender.sendMessage(getConfig().getString("messages.database_unavailable"));
    }

    private void startSpectating(Player player, Arena arena) {
        if (!spectatorsEnabled) { player.sendMessage(PREFIX + "§cРежим наблюдателя отключён."); return; }
        if (arena == null) { player.sendMessage(PREFIX + "§cУкажите арену: /arena spectate <арена>"); return; }
        if (participantArena(player.getUniqueId()) != null) { player.sendMessage(PREFIX + "§cУчастник боя не может стать наблюдателем."); return; }
        if (spectators.containsKey(player.getUniqueId())) leaveSpectator(player, false);
        recovery.saveSpectator(player, arena.name); spectators.put(player.getUniqueId(), arena); player.setGameMode(GameMode.SPECTATOR);
        player.teleport(arena.specSpawn != null ? arena.specSpawn : arena.center.clone().add(0, 3, 0));
        player.sendMessage(PREFIX + "§aВы наблюдаете за ареной " + arena.name + ". Выход за границу вернёт вас назад.");
    }

    private boolean leaveSpectator(Player player, boolean message) {
        Arena removed = spectators.remove(player.getUniqueId()); boolean restored = recovery.restoreSpectator(player);
        if ((removed != null || restored) && message) player.sendMessage(PREFIX + "§eНаблюдение завершено, исходное состояние восстановлено.");
        return removed != null || restored;
    }

    private Arena participantArena(UUID uuid) { for (Arena arena : arenas.values()) if (arena.isParticipant(uuid)) return arena; return null; }

    private void tickAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Arena spectatorArena = spectators.get(player.getUniqueId());
            if (spectatorArena != null && (player.getGameMode() != GameMode.SPECTATOR || !spectatorArena.contains(player.getLocation()))) {
                leaveSpectator(player, true); player.sendMessage(PREFIX + "§cВы покинули границу арены; наблюдение автоматически завершено."); continue;
            }
            if (arenaAt(player.getLocation()) == null && participantArena(player.getUniqueId()) == null) restoreScoreboard(player);
        }
        for (Arena arena : arenas.values()) arena.tick();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer(); if (database != null) database.load(player.getUniqueId());
        if (getConfig().getBoolean("settings.spectators.restore_on_join", true) && recovery.hasSpectator(player.getUniqueId())) Bukkit.getScheduler().runTask(this, () -> leaveSpectator(player, true));
        if (getConfig().getBoolean("settings.recover_inventories_on_join", true) && recovery.hasInventory(player.getUniqueId())) Bukkit.getScheduler().runTaskLater(this, () -> {
            if (recovery.restoreInventory(player)) player.sendMessage(PREFIX + "§aИнвентарь восстановлен после незавершённого матча.");
        }, 2L);
        Bukkit.getScheduler().runTaskLater(this, () -> claimPending(player), 20L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onInteract(PlayerInteractEvent event) {
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
        Bukkit.getScheduler().runTaskLater(this, () -> recovery.restoreInventory(event.getPlayer()), 2L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer(); pendingRespawns.remove(player.getUniqueId());
        if (spectators.containsKey(player.getUniqueId()) || recovery.hasSpectator(player.getUniqueId())) leaveSpectator(player, false);
        Arena arena = participantArena(player.getUniqueId());
        if (arena != null) {
            boolean technical = arena.state == GameState.FIGHTING && combatLogLoss;
            arena.removeParticipant(player.getUniqueId(), technical ? "вышел с сервера — техническое поражение" : "вышел");
            if (!player.isDead()) recovery.restoreInventory(player);
        }
        restoreScoreboard(player);
    }

    @EventHandler(ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) {
        Arena arena = spectators.get(event.getPlayer().getUniqueId());
        if (arena == null || event.getTo() == null || arena.contains(event.getTo())) return;
        Bukkit.getScheduler().runTask(this, () -> { if (spectators.containsKey(event.getPlayer().getUniqueId())) {
            leaveSpectator(event.getPlayer(), true); event.getPlayer().sendMessage(PREFIX + "§cТелепортация за арену завершила наблюдение.");
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
            case "kit" -> arena.kitEnabled = !arena.kitEnabled; case "friendly" -> arena.friendlyFire = !arena.friendlyFire;
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
        ArenaGui holder = new ArenaGui(arena.name); Inventory gui = Bukkit.createInventory(holder, 27, "§8Arena: " + arena.name); holder.inventory = gui;
        gui.setItem(10, guiItem(Material.CLOCK, "§eАвтостарт: " + arena.automatic, "auto"));
        gui.setItem(11, guiItem(Material.GOLD_INGOT, "§eСтавки: " + arena.bettingEnabled, "betting"));
        gui.setItem(12, guiItem(Material.IRON_CHESTPLATE, "§eКомплект: " + arena.kitEnabled, "kit"));
        gui.setItem(13, guiItem(Material.IRON_SWORD, "§eFriendly fire: " + arena.friendlyFire, "friendly"));
        gui.setItem(14, guiItem(Material.SPAWNER, "§eBossBar: " + arena.showBar, "bar"));
        gui.setItem(15, guiItem(Material.LIME_DYE, "§aСтарт", "start")); gui.setItem(16, guiItem(Material.RED_DYE, "§cСтоп", "stop"));
        gui.setItem(22, guiItem(Material.WRITABLE_BOOK, "§bПроверить настройку", "validate")); player.openInventory(gui);
    }

    private ItemStack guiItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(guiActionKey, PersistentDataType.STRING, action); item.setItemMeta(meta); return item;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("help", "list", "odds", "stats", "spectate", "leave"));
            if (sender.hasPermission("arena.admin")) base.addAll(List.of("create", "delete", "status", "validate", "gui", "start", "stop", "reload",
                "setred", "setblue", "sethost", "setreset", "sethopred", "sethopblue", "setfhop", "bankomat", "unhopred", "unhopblue", "unfhop",
                "setspawn", "spawnred1", "spawnred2", "spawnblue1", "spawnblue2", "showbar", "friendlyfire", "maxplayers", "auto", "manual",
                "betting", "kit", "final", "finalstats", "fstatsremove", "fstatsscale", "radius", "debug"));
            return filter(base, args[0]);
        }
        if (args.length == 2) return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "delete", "status", "validate", "gui", "odds", "spectate" -> filter(arenas.keySet(), args[1]);
            case "stats" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            case "showbar" -> filter(List.of("spectators", "all", "false"), args[1]);
            case "friendlyfire", "betting", "kit" -> filter(List.of("true", "false", "toggle"), args[1]);
            case "maxplayers" -> filter(List.of("1", "2", "3", "4", "5", "10", "20"), args[1]);
            case "radius" -> filter(List.of("25", "50", "75", "100", "150", "200"), args[1]);
            case "fstatsscale" -> filter(List.of("0.5", "0.75", "1.0", "1.5", "2.0"), args[1]);
            case "debug" -> filter(List.of("hologram"), args[1]); default -> List.of();
        };
        return List.of();
    }

    private final class Arena {
        final String name; final Location center;
        int radius = 100, maxPlayers = 2; Location redBtn, blueBtn, hostBtn, resetBtn, redHopper, blueHopper, finalHopper, bankomat;
        Location specSpawn, spawnRed1, spawnRed2, spawnBlue1, spawnBlue2;
        boolean automatic, bettingEnabled = true, kitEnabled, friendlyFire, finalMode; String showBar = "spectators"; double finalPool;
        final List<String> lastChampions = new ArrayList<>(); final List<FinalStatHolo> finalStats = new ArrayList<>();
        final Set<UUID> red = new LinkedHashSet<>(), blue = new LinkedHashSet<>(), originalRed = new LinkedHashSet<>(), originalBlue = new LinkedHashSet<>();
        final Map<UUID, Double> redBets = new HashMap<>(), blueBets = new HashMap<>(); final Set<UUID> usedResets = new HashSet<>();
        final Map<UUID, BossBar> bars = new HashMap<>(); GameState state = GameState.WAITING; int timerTicks = -1, fightTicks, tickCounter;
        Arena(String name, Location center) { this.name = name; this.center = center.clone(); }
        boolean contains(Location location) { return location != null && location.getWorld() != null && center.getWorld().equals(location.getWorld()) && center.distanceSquared(location) <= (double) radius * radius; }
        boolean isParticipant(UUID uuid) { return red.contains(uuid) || blue.contains(uuid); }

        boolean handleInteract(Player player, Location location, PlayerInteractEvent event) {
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
            if (state == GameState.FIGHTING || state == GameState.COUNTDOWN) { player.sendMessage(PREFIX + "§cРегистрация закрыта."); return; }
            if (spectators.containsKey(player.getUniqueId())) leaveSpectator(player, false);
            Set<UUID> own = redTeam ? red : blue, other = redTeam ? blue : red;
            if (other.contains(player.getUniqueId())) { player.sendMessage(PREFIX + "§cСначала выйдите из другой команды."); return; }
            if (own.remove(player.getUniqueId())) { removeBar(player.getUniqueId()); recovery.restoreInventory(player); player.sendMessage(PREFIX + "§eВы вышли из команды."); updatePhase(); return; }
            if (own.size() >= maxPlayers) { player.sendMessage(PREFIX + "§cКоманда заполнена."); return; }
            if (kitEnabled && !applyKit(player)) return;
            own.add(player.getUniqueId()); BossBar bar = Bukkit.createBossBar((redTeam ? "§cКрасные: " : "§9Синие: ") + player.getName(), redTeam ? BarColor.RED : BarColor.BLUE, BarStyle.SOLID);
            bars.put(player.getUniqueId(), bar); player.sendMessage(PREFIX + "§aВы вступили в " + (redTeam ? "красную" : "синюю") + " команду."); updatePhase();
        }

        boolean applyKit(Player player) {
            if (!recovery.saveInventory(player, name)) { player.sendMessage(PREFIX + "§cНе удалось создать резервную копию инвентаря."); return false; }
            player.getInventory().clear(); ConfigurationSection items = kits.getConfigurationSection(name + ".items");
            if (items == null) { player.sendMessage(PREFIX + "§eВ kits.yml нет комплекта; выдан пустой комплект."); return true; }
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

        void updatePhase() {
            boolean ready = !red.isEmpty() && !blue.isEmpty();
            if (ready && state == GameState.WAITING) {
                state = GameState.BETTING; usedResets.clear(); timerTicks = automatic && !finalMode ? bettingSeconds * 2 : -1;
                broadcast(automatic && !finalMode ? "§eСтавки открыты. Автостарт через " + bettingSeconds + " с." : "§eКоманды готовы. Ожидаем ручного старта.");
            } else if (!ready && (state == GameState.BETTING || state == GameState.COUNTDOWN)) {
                state = GameState.WAITING; timerTicks = -1; broadcast("§eОжидание обеих команд.");
            }
            updateHolograms();
        }

        void beginCountdown(Player initiator) {
            if (state != GameState.BETTING || red.isEmpty() || blue.isEmpty()) { if (initiator != null) initiator.sendMessage(PREFIX + "§cДля старта нужны обе команды."); return; }
            List<String> errors = validationErrors();
            if (!errors.isEmpty()) { if (initiator != null) { initiator.sendMessage(PREFIX + "§cАрена не готова:"); errors.forEach(error -> initiator.sendMessage("§c- " + error)); } return; }
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

        void voluntaryLeave(Player player) { removeParticipant(player.getUniqueId(), state == GameState.FIGHTING ? "сдался — техническое поражение" : "вышел из команды"); if (!player.isDead()) recovery.restoreInventory(player); }
        void removeParticipant(UUID uuid, String reason) {
            boolean existed = red.remove(uuid) | blue.remove(uuid); if (!existed) return; removeBar(uuid); Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.sendMessage(PREFIX + "§eВы покинули арену: " + reason + "."); if (state == GameState.FIGHTING) checkWinner(); else updatePhase();
        }
        void eliminate(UUID uuid, String reason) { removeParticipant(uuid, reason); }
        void checkWinner() {
            if (state != GameState.FIGHTING) return;
            if (red.isEmpty() && blue.isEmpty()) endDuel(null, null); else if (red.isEmpty()) endDuel(originalBlue, originalRed); else if (blue.isEmpty()) endDuel(originalRed, originalBlue);
        }

        void endDuel(Set<UUID> winners, Set<UUID> losers) {
            state = GameState.ENDING; Set<UUID> all = new HashSet<>(); all.addAll(originalRed); all.addAll(originalBlue);
            for (UUID uuid : all) { Player online = Bukkit.getPlayer(uuid); if (online != null && !online.isDead()) recovery.restoreInventory(online); }
            double winTotal = winners == null ? 0.0 : (winners.equals(originalRed) ? total(redBets) : total(blueBets));
            Map<UUID, Double> winningBets = winners == null ? Map.of() : (winners.equals(originalRed) ? redBets : blueBets);
            double pot = total(redBets) + total(blueBets), distributable = pot * (1.0 - commissionPercent / 100.0);
            if (winTotal > 0.0) for (Map.Entry<UUID, Double> entry : winningBets.entrySet()) {
                double payout = roundTenth(entry.getValue() / winTotal * distributable); String playerName = playerName(entry.getKey());
                payOrQueue(entry.getKey(), playerName, payout, useVault); database.recordBet(entry.getKey(), playerName, true, Math.max(0.0, payout - entry.getValue()));
            }
            Map<UUID, Double> losingBets = winners == null ? Map.of() : (winners.equals(originalRed) ? blueBets : redBets);
            for (UUID uuid : losingBets.keySet()) database.recordBet(uuid, playerName(uuid), false, 0.0);
            double championShare = 0.0;
            if (finalMode && winners != null && !winners.isEmpty()) {
                championShare = roundTenth(finalPool / winners.size()); lastChampions.clear();
                for (UUID uuid : winners) { String playerName = playerName(uuid); lastChampions.add(playerName); payOrQueue(uuid, playerName, championShare, useVault); }
                finalPool = 0.0; finalMode = false; saveArena(this);
            }
            if (winners == null) refundAllBets(); else {
                for (UUID uuid : winners) database.recordMatch(uuid, playerName(uuid), true, championShare);
                for (UUID uuid : losers) database.recordMatch(uuid, playerName(uuid), false, 0.0);
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

        void stop(String reason, boolean refund) {
            if (state == GameState.WAITING && red.isEmpty() && blue.isEmpty() && redBets.isEmpty() && blueBets.isEmpty()) return;
            broadcast("§cМатч " + reason + "."); if (refund) refundAllBets(); Set<UUID> participants = new HashSet<>(); participants.addAll(red); participants.addAll(blue);
            for (UUID uuid : participants) { Player player = Bukkit.getPlayer(uuid); if (player != null && !player.isDead()) recovery.restoreInventory(player); removeBar(uuid); }
            recovery.clearArenaBets(name); red.clear(); blue.clear(); originalRed.clear(); originalBlue.clear(); redBets.clear(); blueBets.clear(); state = GameState.WAITING; timerTicks = -1; fightTicks = 0; updateHolograms();
        }

        void shutdown() {
            stop("остановлен при выключении плагина", true);
            for (UUID uuid : new ArrayList<>(spectators.keySet())) if (spectators.get(uuid) == this) { Player player = Bukkit.getPlayer(uuid); if (player != null) leaveSpectator(player, false); }
            removeHolograms();
        }

        void placeBet(Player player, boolean onRed) {
            if (!bettingEnabled || state != GameState.BETTING) { player.sendMessage(PREFIX + "§cСтавки сейчас закрыты."); return; }
            Map<UUID, Double> team = onRed ? redBets : blueBets, other = onRed ? blueBets : redBets;
            if (other.getOrDefault(player.getUniqueId(), 0.0) > 0.0) { player.sendMessage(PREFIX + "§cНельзя ставить на обе команды."); return; }
            double value = useVault ? vaultBetStep : heldCurrencyValue(player), current = team.getOrDefault(player.getUniqueId(), 0.0);
            if (value < minBet || current + value > maxBet) { player.sendMessage(PREFIX + "§cСтавка должна быть от " + money(minBet) + " до " + money(maxBet) + "."); return; }
            if (!withdraw(player, value)) return; double newValue = roundTenth(current + value); team.put(player.getUniqueId(), newValue);
            recovery.saveBet(name, onRed ? "red" : "blue", player.getUniqueId(), player.getName(), newValue, useVault);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f); player.sendMessage(PREFIX + "§aСтавка принята: " + money(value) + currency()); updateHolograms();
        }

        void cancelBet(Player player) {
            if (state != GameState.BETTING) { player.sendMessage(PREFIX + "§cОтмена доступна только на этапе ставок."); return; }
            UUID uuid = player.getUniqueId(); if (usedResets.contains(uuid)) { player.sendMessage(PREFIX + "§cВы уже отменяли ставку."); return; }
            double amount = redBets.getOrDefault(uuid, 0.0) + blueBets.getOrDefault(uuid, 0.0);
            if (amount <= 0.0) { player.sendMessage(PREFIX + "§eУ вас нет ставки."); return; }
            if (!pay(player, amount, useVault)) return; redBets.remove(uuid); blueBets.remove(uuid); usedResets.add(uuid);
            recovery.removeBet(name, "red", uuid); recovery.removeBet(name, "blue", uuid); player.sendMessage(PREFIX + "§aСтавка возвращена: " + money(amount) + currency()); updateHolograms();
        }

        void refundAllBets() {
            Map<UUID, Double> all = new HashMap<>(redBets); blueBets.forEach((uuid, amount) -> all.merge(uuid, amount, Double::sum));
            all.forEach((uuid, amount) -> payOrQueue(uuid, playerName(uuid), amount, useVault)); redBets.clear(); blueBets.clear(); recovery.clearArenaBets(name);
        }

        void contributeFinal(Player player) {
            if (state != GameState.WAITING) { player.sendMessage(PREFIX + "§cФинальная касса заблокирована."); return; }
            double value = useVault ? vaultBetStep : heldCurrencyValue(player); if (!withdraw(player, value)) return;
            finalPool = roundTenth(finalPool + value); saveArena(this); updateHolograms(); player.sendMessage(PREFIX + "§aВзнос принят. Пул: " + money(finalPool) + currency());
        }

        void exchange(Player player) {
            if (useVault) { player.sendMessage(PREFIX + "§eПри Vault размен не нужен."); return; }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == mainCurrency && hand.getAmount() >= 1) { hand.setAmount(hand.getAmount() - 1); giveMaterial(player, subCurrency, 10); }
            else if (hand.getType() == subCurrency && hand.getAmount() >= 10) { hand.setAmount(hand.getAmount() - 10); giveMaterial(player, mainCurrency, 1); }
            else player.sendMessage(PREFIX + "§cНужен 1 основной или 10 мелких предметов валюты.");
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
            if (player != null && !player.isDead()) recovery.restoreInventory(player);
        }

        void updateBossBars() {
            List<Player> viewers = playersInRadius();
            for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
                Player fighter = Bukkit.getPlayer(entry.getKey()); BossBar bar = entry.getValue(); bar.removeAll(); if (fighter == null) continue;
                double max = Objects.requireNonNull(fighter.getAttribute(Attribute.MAX_HEALTH)).getValue(); bar.setProgress(Math.max(0.0, Math.min(1.0, fighter.getHealth() / max)));
                for (Player viewer : viewers) { boolean participant = isParticipant(viewer.getUniqueId());
                    if (showBar.equals("all") || showBar.equals("spectators") && !participant || showBar.equals("false") && viewer.hasPermission("arena.admin")) bar.addPlayer(viewer); }
            }
        }

        void updateScoreboards() {
            for (Player player : playersInRadius()) {
                previousScoreboards.putIfAbsent(player.getUniqueId(), player.getScoreboard()); Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
                Objective objective = scoreboard.registerNewObjective("arena_bets", "dummy", "§6§lАРЕНА: " + name.toUpperCase(Locale.ROOT)); objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                List<String> lines = new ArrayList<>(List.of(stateLine(), " ", "§cКрасные: §f" + red.size() + "/" + maxPlayers, "§9Синие: §f" + blue.size() + "/" + maxPlayers));
                if (bettingEnabled) { double r = total(redBets), b = total(blueBets), sum = r + b; lines.add("  "); lines.add("§eБанк: §f" + money(sum) + currency()); lines.add("§cК: §f" + odds(sum, r) + "x"); lines.add("§9С: §f" + odds(sum, b) + "x"); }
                int score = lines.size(); Set<String> unique = new HashSet<>(); for (String line : lines) { while (!unique.add(line)) line += "§r"; objective.getScore(line).setScore(score--); }
                player.setScoreboard(scoreboard);
            }
        }

        String stateLine() { return switch (state) {
            case WAITING -> "§7Ожидание игроков"; case BETTING -> timerTicks < 0 ? "§eОжидание ведущего" : "§eСтавки: " + timerTicks / 2 + " с";
            case COUNTDOWN -> "§cСтарт: " + timerTicks / 2 + " с"; case FIGHTING -> "§cБой: " + duration(); case ENDING -> "§6Матч завершён";
        }; }

        void updateHolograms() {
            removeHolograms(); spawnBetHolo(redHopper, "red", "§cКрасные", total(redBets)); spawnBetHolo(blueHopper, "blue", "§9Синие", total(blueBets));
            String champions = lastChampions.isEmpty() ? "§7Нет данных" : "§f" + String.join(", ", lastChampions);
            String text = finalStatsFormat.replace("%pool%", money(finalPool)).replace("%currency%", currency()).replace("%champions%", champions);
            for (int i = 0; i < finalStats.size(); i++) { FinalStatHolo holo = finalStats.get(i); spawnHologram(holo.location, text, "final:" + i, holo.scale, Display.Billboard.FIXED); }
        }

        void spawnBetHolo(Location hopper, String team, String title, double amount) {
            if (hopper == null) return; double sum = total(redBets) + total(blueBets);
            spawnHologram(hopper.clone().add(0.5, 1.25, 0.5), title + "\n§f" + money(amount) + currency() + " §7(" + odds(sum, amount) + "x)", "bet:" + team, 0.65f, Display.Billboard.CENTER);
        }

        void spawnHologram(Location location, String text, String id, float scale, Display.Billboard billboard) {
            if (location == null || location.getWorld() == null) return; TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
            display.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, name + ":" + id); display.setBillboard(billboard); display.setText(text); display.setSeeThrough(true);
            Transformation transform = display.getTransformation(); transform.getScale().set(scale, scale, scale); display.setTransformation(transform);
        }

        void removeHolograms() {
            for (World world : Bukkit.getWorlds()) for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                String id = display.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING); if (id != null && id.startsWith(name + ":")) display.remove();
            }
        }

        void sendOdds(CommandSender sender) {
            double r = total(redBets), b = total(blueBets), sum = r + b;
            sender.sendMessage(PREFIX + "§e" + name + ": банк §f" + money(sum) + currency() + "§7, §cКрасные " + odds(sum, r) + "x§7, §9Синие " + odds(sum, b) + "x§7, комиссия " + money(commissionPercent) + "%");
        }
        void sendStatus(CommandSender sender) { sender.sendMessage(PREFIX + "§e" + name + " §7— " + state + ", §c" + red.size() + "§7:§9" + blue.size() + "§7, банк §f" + money(total(redBets) + total(blueBets)) + currency() + "§7, radius " + radius); }
        void validate(CommandSender sender) { List<String> errors = validationErrors(); if (errors.isEmpty()) sender.sendMessage(PREFIX + "§aАрена полностью готова."); else { sender.sendMessage(PREFIX + "§cПроблемы настройки:"); errors.forEach(error -> sender.sendMessage("§c- " + error)); } }

        List<String> validationErrors() {
            List<String> errors = new ArrayList<>(); if (redBtn == null) errors.add("не задана кнопка красных"); if (blueBtn == null) errors.add("не задана кнопка синих");
            if (spawnRed1 == null) errors.add("не задан spawnred1"); if (spawnBlue1 == null) errors.add("не задан spawnblue1");
            if (maxPlayers > 1 && spawnRed2 == null) errors.add("не задан spawnred2"); if (maxPlayers > 1 && spawnBlue2 == null) errors.add("не задан spawnblue2");
            if (specSpawn == null) errors.add("не задан setspawn"); if (bettingEnabled && (redHopper == null || blueHopper == null)) errors.add("не заданы обе воронки ставок");
            if (hasOverlap(this)) errors.add("радиус пересекает другую арену"); if (useVault && !vaultReady) errors.add("Vault Economy недоступен"); return errors;
        }

        void toggleFinal(Player player) {
            if (state != GameState.WAITING || !red.isEmpty() || !blue.isEmpty()) { player.sendMessage(PREFIX + "§cФинал переключается только на пустой арене."); return; }
            finalMode = !finalMode; player.sendMessage(PREFIX + (finalMode ? "§6Финальный режим включён." : "§eФинальный режим выключен."));
        }
        List<Player> playersInRadius() { return center.getWorld().getPlayers().stream().filter(player -> contains(player.getLocation())).toList(); }
        double total(Map<UUID, Double> bets) { return bets.values().stream().mapToDouble(Double::doubleValue).sum(); }
        void broadcast(String message) { playersInRadius().forEach(player -> player.sendMessage(PREFIX + message)); }
        void title(String title, String subtitle) { playersInRadius().forEach(player -> player.sendTitle(title, subtitle, 5, 40, 10)); }
        void sound(Sound sound) { playersInRadius().forEach(player -> player.playSound(player.getLocation(), sound, 1f, 1f)); }
        String duration() { int seconds = fightTicks / 2; return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }
        void removeBar(UUID uuid) { BossBar bar = bars.remove(uuid); if (bar != null) bar.removeAll(); }
    }

    private boolean withdraw(Player player, double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) { player.sendMessage(PREFIX + "§cВозьмите валюту в основную руку."); return false; }
        if (useVault) {
            if (!vaultReady || economy == null) { player.sendMessage(getConfig().getString("messages.vault_unavailable")); return false; }
            if (!economy.has(player, amount)) { player.sendMessage(PREFIX + "§cНедостаточно средств: нужно " + money(amount) + vaultSymbol); return false; }
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            if (!response.transactionSuccess()) { player.sendMessage(PREFIX + "§cПлатёж отклонён: " + response.errorMessage); getLogger().warning("Vault withdraw отказал для " + player.getName() + ": " + response.errorMessage); return false; }
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if ((hand.getType() != mainCurrency && hand.getType() != subCurrency) || hand.getAmount() < 1) { player.sendMessage(PREFIX + "§cВозьмите валюту в основную руку."); return false; }
        hand.setAmount(hand.getAmount() - 1); return true;
    }

    private boolean pay(Player player, double amount, boolean vault) {
        if (vault) {
            if (!vaultReady || economy == null) { recovery.queuePayout(player.getUniqueId(), player.getName(), amount, true); player.sendMessage(PREFIX + "§eVault недоступен; выплата сохранена."); return true; }
            EconomyResponse response = economy.depositPlayer(player, amount);
            if (!response.transactionSuccess()) { recovery.queuePayout(player.getUniqueId(), player.getName(), amount, true); getLogger().warning("Vault deposit отказал для " + player.getName() + ": " + response.errorMessage); }
            return true;
        }
        RecoveryStore.giveItems(player, amount, mainCurrency, subCurrency); return true;
    }

    private void payOrQueue(UUID uuid, String name, double amount, boolean vault) {
        if (amount <= 0.0) return; Player online = Bukkit.getPlayer(uuid); if (online != null && online.isOnline()) { pay(online, amount, vault); return; }
        if (vault && vaultReady && economy != null) { EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(uuid), amount); if (response.transactionSuccess()) return; getLogger().warning("Офлайн-выплата Vault отложена для " + name + ": " + response.errorMessage); }
        recovery.queuePayout(uuid, name, amount, vault);
    }

    private void claimPending(Player player) {
        double items = recovery.claimItemPayout(player, mainCurrency, subCurrency);
        double vault = vaultReady ? recovery.claimVaultPayout(player, economy) : 0.0;
        if (items + vault > 0.0) player.sendMessage(PREFIX + "§aПолучена отложенная выплата: " + money(items + vault) + currency());
    }

    private double heldCurrencyValue(Player player) { Material type = player.getInventory().getItemInMainHand().getType(); return type == mainCurrency ? 1.0 : type == subCurrency ? 0.1 : 0.0; }
    private String currency() { return useVault ? vaultSymbol : " зол."; }
    private void restoreScoreboard(Player player) { Scoreboard previous = previousScoreboards.remove(player.getUniqueId()); if (previous != null) player.setScoreboard(previous); }
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
    private String odds(double total, double side) { return side <= 0.0 ? "—" : money(total * (1.0 - commissionPercent / 100.0) / side); }
    private static String playerName(UUID uuid) { String name = Bukkit.getOfflinePlayer(uuid).getName(); return name == null ? uuid.toString().substring(0, 8) : name; }
    private static String formatLocation(Location location) { return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ(); }
    private static <T> List<String> filter(Collection<T> values, String prefix) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().map(String::valueOf).filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList(); }
    private static void giveMaterial(Player player, Material material, int amount) { Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, amount)); overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item)); }

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
