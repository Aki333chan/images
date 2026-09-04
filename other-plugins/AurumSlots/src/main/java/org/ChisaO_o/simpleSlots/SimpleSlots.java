package org.ChisaO_o.simpleSlots;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SimpleSlots extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private final Map<String, SlotMachine> machines = new HashMap<>();
    private final Map<BlockKey, SlotMachine> locationCache = new HashMap<>();
    private final Set<String> bannedPlayers = new HashSet<>();
    private final Set<SpinTask> activeSpins = new HashSet<>();

    private Material subCurrency = Material.GOLD_NUGGET;
    private Material mainCurrency = Material.GOLD_INGOT;
    private boolean vaultRequested;
    private boolean startupVaultCheckComplete;
    private Economy economy;
    private NamespacedKey hologramMarkerKey;
    private NamespacedKey hologramMachineKey;
    private boolean configurationLoaded;
    private BukkitTask pendingPoolSave;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("SimpleSlots") != null) {
            getLogger().severe("Remove the old SimpleSlots JAR before enabling AurumSlots.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            if (LegacyDataMigration.migrate(new java.io.File(getDataFolder().getParentFile(), "SimpleSlots").toPath(), getDataFolder().toPath())) {
                getLogger().info("Imported SimpleSlots data into AurumSlots; the old folder was preserved.");
            }
        } catch (java.io.IOException exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "Data migration failed; AurumSlots will not start with empty data.", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        // Persisted tags must survive the plugin rename.
        hologramMarkerKey = Objects.requireNonNull(NamespacedKey.fromString("simpleslots:hologram"));
        hologramMachineKey = Objects.requireNonNull(NamespacedKey.fromString("simpleslots:hologram_machine"));
        saveDefaultConfig();
        loadCasinoConfig();
        configurationLoaded = true;

        PluginCommand slotsCommand = Objects.requireNonNull(getCommand("slots"), "Command 'slots' is missing from plugin.yml");
        slotsCommand.setExecutor(this);
        slotsCommand.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTask(this, this::refreshVaultAfterStartup);
    }

    @Override
    public void onDisable() {
        if (!configurationLoaded) return;
        for (SpinTask task : new ArrayList<>(activeSpins)) {
            task.cancelSpin();
        }
        cancelPendingPoolSave();
        persistCasinoConfig();
        for (SlotMachine machine : machines.values()) {
            removeHolograms(machine);
        }
    }

    public String getMsg(String key) {
        String language = getConfig().getString("LANGUAGE", "ru");
        return getConfig().getString("messages." + language + "." + key, key);
    }

    public Material getSubCurrency() {
        return subCurrency;
    }

    public Material getMainCurrency() {
        return mainCurrency;
    }

    public boolean isVaultRequested() {
        return vaultRequested;
    }

    public boolean isVaultReady() {
        return vaultRequested && economy != null;
    }

    public Economy getEconomy() {
        return economy;
    }

    public String getPaymentModeDescription() {
        if (!vaultRequested) {
            return "предметы";
        }
        return economy == null ? "Vault (недоступен)" : "Vault";
    }

    public String getEconomyProviderName() {
        return economy == null ? "нет" : economy.getName();
    }

    public void loadCasinoConfig() {
        cancelPendingPoolSave();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        for (SlotMachine machine : machines.values()) {
            removeHolograms(machine);
        }
        machines.clear();
        bannedPlayers.clear();

        bannedPlayers.addAll(getConfig().getStringList("banned_players"));
        loadCurrencies();
        configurePaymentMode();
        loadMachines();
        updateCache();
    }

    private void loadCurrencies() {
        String subName = getConfig().getString("SUB_CURRENCY", "GOLD_NUGGET");
        subCurrency = Material.matchMaterial(subName == null ? "GOLD_NUGGET" : subName);
        if (subCurrency == null) {
            getLogger().warning("Unknown SUB_CURRENCY; using GOLD_NUGGET.");
            subCurrency = Material.GOLD_NUGGET;
        }

        String mainName = getConfig().getString("CURRENCY", "GOLD_INGOT");
        if (mainName == null || mainName.equalsIgnoreCase("NONE")) {
            mainCurrency = null;
            return;
        }
        mainCurrency = Material.matchMaterial(mainName);
        if (mainCurrency == null) {
            getLogger().warning("Unknown CURRENCY; using GOLD_INGOT.");
            mainCurrency = Material.GOLD_INGOT;
        }
    }

    private void configurePaymentMode() {
        vaultRequested = getConfig().getBoolean("use_vault", false);
        economy = null;

        if (!vaultRequested) {
            getLogger().info("Payment mode: items.");
            return;
        }
        if (setupEconomy()) {
            getLogger().info("Payment mode: Vault (provider: " + economy.getName() + ").");
        } else if (!startupVaultCheckComplete) {
            getLogger().info("use_vault is enabled; waiting for the economy provider to finish loading.");
        } else {
            getLogger().severe("use_vault is enabled, but no Vault economy provider is registered. Slot payments are blocked; the plugin will not fall back to items.");
        }
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            economy = null;
            return false;
        }
        economy = registration.getProvider();
        return economy != null;
    }

    private void refreshVaultAfterStartup() {
        if (!isEnabled()) {
            return;
        }
        startupVaultCheckComplete = true;
        if (!vaultRequested) {
            return;
        }
        Economy previous = economy;
        if (setupEconomy()) {
            if (previous != economy) {
                getLogger().info("Vault economy provider confirmed after server startup: " + economy.getName());
            }
        } else {
            getLogger().severe("Vault economy provider is still unavailable after server startup.");
        }
        updateCache();
    }

    private void loadMachines() {
        ConfigurationSection section = getConfig().getConfigurationSection("machines");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            SlotMachine machine = new SlotMachine(id);
            machine.bet = section.getDouble(id + ".bet", 1.0);
            machine.pool = Math.max(0, section.getInt(id + ".pool", 0));
            machine.shelfLoc = strToLoc(section.getString(id + ".shelf"), id, "shelf");
            machine.buttonLoc = strToLoc(section.getString(id + ".button"), id, "button");
            machine.hopperLoc = strToLoc(section.getString(id + ".hopper"), id, "hopper");
            machines.put(id, machine);
        }
    }

    public void saveCasinoConfig() {
        cancelPendingPoolSave();
        persistCasinoConfig();
        updateCache();
    }

    private void persistCasinoConfig() {
        getConfig().set("banned_players", new ArrayList<>(bannedPlayers));
        getConfig().set("machines", null);
        for (Map.Entry<String, SlotMachine> entry : machines.entrySet()) {
            String path = "machines." + entry.getKey();
            SlotMachine machine = entry.getValue();
            getConfig().set(path + ".bet", machine.bet);
            getConfig().set(path + ".pool", machine.pool);
            if (machine.shelfLoc != null) {
                getConfig().set(path + ".shelf", locToStr(machine.shelfLoc));
            }
            if (machine.buttonLoc != null) {
                getConfig().set(path + ".button", locToStr(machine.buttonLoc));
            }
            if (machine.hopperLoc != null) {
                getConfig().set(path + ".hopper", locToStr(machine.hopperLoc));
            }
        }
        saveConfig();
    }

    private void updateCache() {
        locationCache.clear();
        for (SlotMachine machine : machines.values()) {
            cache(machine.shelfLoc, machine);
            cache(machine.buttonLoc, machine);
            cache(machine.hopperLoc, machine);
            updateHologram(machine);
        }
    }

    private void cache(Location location, SlotMachine machine) {
        BlockKey key = BlockKey.from(location);
        if (key != null) {
            locationCache.put(key, machine);
        }
    }

    private void updateHologram(SlotMachine machine) {
        removeHolograms(machine);
        if (machine.hopperLoc == null || machine.hopperLoc.getWorld() == null) {
            return;
        }

        Location location = machine.hopperLoc.clone().add(0.5, 1.2, 0.5);
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class);
        display.setBillboard(Display.Billboard.CENTER);
        float scale = (float) Math.clamp(getConfig().getDouble("hologram_scale", 0.7), 0.25, 2.0);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
        ));

        String price;
        if (vaultRequested) {
            price = formatNumber(machine.bet) + getConfig().getString("vault_symbol", "$");
            if (economy == null && startupVaultCheckComplete) {
                price += " §c[Vault недоступен]";
            }
        } else {
            price = formatNumber(machine.bet) + " " + subCurrency.name();
        }
        display.setText("§eСтавка: §a" + price);
        display.getPersistentDataContainer().set(hologramMarkerKey, PersistentDataType.BYTE, (byte) 1);
        display.getPersistentDataContainer().set(hologramMachineKey, PersistentDataType.STRING, machine.id);
        machine.hologramUuid = display.getUniqueId();
    }

    private void removeHolograms(SlotMachine machine) {
        if (machine.hologramUuid != null) {
            Entity tracked = Bukkit.getEntity(machine.hologramUuid);
            if (tracked != null) {
                tracked.remove();
            }
        }
        machine.hologramUuid = null;

        if (machine.hopperLoc == null || machine.hopperLoc.getWorld() == null) {
            return;
        }
        machine.hopperLoc.getChunk().load();
        Location expected = machine.hopperLoc.clone().add(0.5, 1.2, 0.5);
        for (Entity entity : expected.getWorld().getNearbyEntities(expected, 1.5, 1.5, 1.5)) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            String machineId = display.getPersistentDataContainer().get(hologramMachineKey, PersistentDataType.STRING);
            boolean taggedForMachine = machine.id.equals(machineId);
            boolean legacyAtExpectedPosition = isLegacyHologram(display)
                    && display.getLocation().distanceSquared(expected) <= 0.36;
            if (taggedForMachine || legacyAtExpectedPosition) {
                display.remove();
            }
        }
    }

    private boolean isSimpleSlotsHologram(TextDisplay display) {
        return display.getPersistentDataContainer().has(hologramMarkerKey, PersistentDataType.BYTE)
                || isLegacyHologram(display);
    }

    private static boolean isLegacyHologram(TextDisplay display) {
        return isLegacyHologramText(display.getText());
    }

    static boolean isLegacyHologramText(String text) {
        String plainText = ChatColor.stripColor(text);
        return plainText != null && plainText.startsWith("Ставка:");
    }

    private String locToStr(Location location) {
        return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }

    private Location strToLoc(String value, String machineId, String part) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String[] pieces = value.split(";");
            if (pieces.length != 4 || Bukkit.getWorld(pieces[0]) == null) {
                throw new IllegalArgumentException("invalid or unloaded world");
            }
            return new Location(
                    Bukkit.getWorld(pieces[0]),
                    Integer.parseInt(pieces[1]),
                    Integer.parseInt(pieces[2]),
                    Integer.parseInt(pieces[3])
            );
        } catch (RuntimeException exception) {
            getLogger().warning("Cannot load " + part + " location for machine '" + machineId + "': " + value);
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("casino.admin")) {
            sender.sendMessage(getMsg("no_permission"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(getMsg("help"));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("reload")) {
            loadCasinoConfig();
            sender.sendMessage(getMsg("reload").replace("%mode%", getPaymentModeDescription()));
            return true;
        }
        if (subcommand.equals("status")) {
            sender.sendMessage(getMsg("status")
                    .replace("%mode%", getPaymentModeDescription())
                    .replace("%provider%", getEconomyProviderName()));
            return true;
        }
        if (subcommand.equals("debug")) {
            handleDebugCommand(sender, args);
            return true;
        }
        if (subcommand.equals("ban") && args.length == 2) {
            toggleBan(sender, args[1]);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(getMsg("help"));
            return true;
        }

        String id = args[1];
        if (subcommand.equals("remove")) {
            SlotMachine removed = machines.remove(id);
            if (removed != null) {
                removeHolograms(removed);
                saveCasinoConfig();
                sender.sendMessage(getMsg("removed").replace("%id%", id));
            }
            return true;
        }

        if (subcommand.equals("bet")) {
            setBet(sender, id, args);
            return true;
        }
        if (!Set.of("shelf", "button", "hop").contains(subcommand)) {
            sender.sendMessage(getMsg("help"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            return true;
        }

        Block target = player.getTargetBlockExact(5);
        if (target == null) {
            player.sendMessage(getMsg("look_block"));
            return true;
        }

        SlotMachine machine = machines.computeIfAbsent(id, SlotMachine::new);
        switch (subcommand) {
            case "shelf" -> {
                machine.shelfLoc = target.getLocation();
                player.sendMessage(getMsg("shelf_linked").replace("%id%", id));
            }
            case "button" -> {
                machine.buttonLoc = target.getLocation();
                player.sendMessage(getMsg("button_linked").replace("%id%", id));
            }
            case "hop" -> {
                machine.hopperLoc = target.getLocation();
                player.sendMessage(getMsg("hop_linked").replace("%id%", id));
            }
            default -> throw new IllegalStateException("Unexpected command: " + subcommand);
        }
        saveCasinoConfig();
        return true;
    }

    private void toggleBan(CommandSender sender, String playerName) {
        String normalizedName = playerName.toLowerCase(Locale.ROOT);
        if (bannedPlayers.remove(normalizedName)) {
            sender.sendMessage(getMsg("unbanned").replace("%player%", playerName));
        } else {
            bannedPlayers.add(normalizedName);
            sender.sendMessage(getMsg("banned").replace("%player%", playerName));
        }
        saveCasinoConfig();
    }

    private void setBet(CommandSender sender, String id, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(getMsg("help"));
            return;
        }
        try {
            double bet = Double.parseDouble(args[2]);
            boolean invalidForItems = !vaultRequested && bet != Math.rint(bet);
            if (!Double.isFinite(bet) || bet <= 0.0 || invalidForItems) {
                sendInvalidBet(sender);
                return;
            }
            SlotMachine machine = machines.computeIfAbsent(id, SlotMachine::new);
            machine.bet = bet;
            saveCasinoConfig();
            sender.sendMessage(getMsg("bet_set")
                    .replace("%id%", id)
                    .replace("%bet%", formatNumber(bet)));
        } catch (NumberFormatException exception) {
            sendInvalidBet(sender);
        }
    }

    private void sendInvalidBet(CommandSender sender) {
        String itemHint = vaultRequested ? "" : " без дробной части";
        sender.sendMessage(getMsg("invalid_bet").replace("%item_hint%", itemHint));
    }

    public static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void handleDebugCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(getMsg("debug_player_only"));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("removehologram")) {
            sender.sendMessage(getMsg("help"));
            return;
        }

        double radius = 8.0;
        if (args.length >= 3) {
            try {
                radius = Double.parseDouble(args[2]);
            } catch (NumberFormatException exception) {
                player.sendMessage(getMsg("debug_invalid_radius"));
                return;
            }
        }
        if (!Double.isFinite(radius) || radius < 1.0 || radius > 64.0) {
            player.sendMessage(getMsg("debug_invalid_radius"));
            return;
        }

        TextDisplay nearest = null;
        double nearestDistanceSquared = radius * radius;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof TextDisplay display) || !isSimpleSlotsHologram(display)) {
                continue;
            }
            double distanceSquared = display.getLocation().distanceSquared(player.getLocation());
            if (distanceSquared <= nearestDistanceSquared) {
                nearest = display;
                nearestDistanceSquared = distanceSquared;
            }
        }

        if (nearest == null) {
            player.sendMessage(getMsg("debug_hologram_not_found")
                    .replace("%radius%", formatNumber(radius)));
            return;
        }
        UUID removedUuid = nearest.getUniqueId();
        nearest.remove();
        for (SlotMachine machine : machines.values()) {
            if (removedUuid.equals(machine.hologramUuid)) {
                machine.hologramUuid = null;
            }
        }
        double distance = Math.round(Math.sqrt(nearestDistanceSquared) * 100.0) / 100.0;
        player.sendMessage(getMsg("debug_hologram_removed")
                .replace("%distance%", formatNumber(distance)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.addAll(Arrays.asList("bet", "shelf", "button", "hop", "remove", "ban", "status", "debug", "reload", "help"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("ban")) {
                suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            } else if (args[0].equalsIgnoreCase("debug")) {
                suggestions.add("removehologram");
            } else if (Set.of("bet", "shelf", "button", "hop", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
                suggestions.addAll(machines.keySet());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("bet")) {
            SlotMachine machine = machines.get(args[1]);
            suggestions.add(machine == null ? "1" : formatNumber(machine.bet));
            suggestions.add("10");
            suggestions.add("100");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("debug")
                && args[1].equalsIgnoreCase("removehologram")) {
            suggestions.addAll(Arrays.asList("4", "8", "16"));
        }

        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        SlotMachine machine = locationCache.get(BlockKey.from(event.getBlock().getLocation()));
        if (machine == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("casino.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(getMsg("machine_protected"));
            return;
        }
        removeHolograms(machine);
        machines.remove(machine.id);
        saveCasinoConfig();
        event.getPlayer().sendMessage("§cАвтомат " + machine.id + " удалён из-за разрушения блока.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        SlotMachine machine = locationCache.get(BlockKey.from(block.getLocation()));
        if (machine == null) {
            return;
        }

        Player player = event.getPlayer();
        if (bannedPlayers.contains(player.getName().toLowerCase(Locale.ROOT))) {
            event.setCancelled(true);
            player.sendMessage(getMsg("banned_play"));
            return;
        }
        if (block.getLocation().equals(machine.shelfLoc)) {
            event.setCancelled(true);
            return;
        }
        if (block.getLocation().equals(machine.hopperLoc)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            if (vaultRequested) {
                player.sendMessage(economy == null ? getMsg("vault_unavailable") : getMsg("vault_hopper"));
            } else {
                handlePhysicalBet(player, machine);
            }
            return;
        }
        if (block.getLocation().equals(machine.buttonLoc)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            startPaidSpin(player, machine);
        }
    }

    private void startPaidSpin(Player player, SlotMachine machine) {
        if (machine.isSpinning) {
            player.sendMessage(getMsg("spinning"));
            return;
        }
        if (vaultRequested) {
            takeVaultBetAndStart(player, machine);
            return;
        }
        int bet = (int) machine.bet;
        if (machine.pool < bet) {
            player.sendMessage(getMsg("no_bet"));
            return;
        }
        machine.pool -= bet;
        saveConfigAfterPoolChange();
        startSpin(machine, player);
    }

    private void takeVaultBetAndStart(Player player, SlotMachine machine) {
        if (economy == null) {
            player.sendMessage(getMsg("vault_unavailable"));
            return;
        }
        if (!economy.has(player, machine.bet)) {
            player.sendMessage(getMsg("not_enough_vault"));
            return;
        }

        EconomyResponse response = economy.withdrawPlayer(player, machine.bet);
        if (!response.transactionSuccess()) {
            player.sendMessage(getMsg("vault_withdraw_failed"));
            getLogger().warning("Vault withdrawal failed for " + player.getName() + ": " + response.errorMessage);
            return;
        }
        startSpin(machine, player);
    }

    private void handlePhysicalBet(Player player, SlotMachine machine) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        int required = (int) machine.bet;
        if (hand.getType() == subCurrency) {
            if (hand.getAmount() >= required) {
                hand.setAmount(hand.getAmount() - required);
                machine.pool += required;
                saveConfigAfterPoolChange();
                player.sendMessage(getMsg("bet_accepted"));
            } else {
                player.sendMessage(getMsg("not_enough").replace("%req%", String.valueOf(required)));
            }
        } else if (mainCurrency != null && hand.getType() == mainCurrency) {
            if (required % 10 == 0 && hand.getAmount() >= required / 10) {
                hand.setAmount(hand.getAmount() - required / 10);
                machine.pool += required;
                saveConfigAfterPoolChange();
                player.sendMessage(getMsg("bet_accepted"));
            } else {
                player.sendMessage(getMsg("exchange"));
            }
        } else {
            player.sendMessage(getMsg("wrong_currency"));
        }
    }

    private void saveConfigAfterPoolChange() {
        if (pendingPoolSave != null) return;
        pendingPoolSave = getServer().getScheduler().runTaskLater(this, () -> {
            pendingPoolSave = null;
            persistCasinoConfig();
        }, 5L);
    }

    private void cancelPendingPoolSave() {
        if (pendingPoolSave == null) return;
        pendingPoolSave.cancel();
        pendingPoolSave = null;
    }

    private void startSpin(SlotMachine machine, Player player) {
        SpinTask task = new SpinTask(this, machine, player);
        activeSpins.add(task);
        task.runTaskTimer(this, 0L, 5L);
    }

    public void removeTask(SpinTask task) {
        activeSpins.remove(task);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isMachineInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (isMachineInventory(event.getSource())
                || isMachineInventory(event.getDestination())
                || isMachineInventory(event.getInitiator())) {
            event.setCancelled(true);
        }
    }

    private boolean isMachineInventory(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        if (isMachineLocation(inventory.getLocation())) {
            return true;
        }
        if (inventory.getHolder() instanceof BlockState blockState) {
            return isMachineLocation(blockState.getBlock().getLocation());
        }
        return false;
    }

    private boolean isMachineLocation(Location location) {
        BlockKey key = BlockKey.from(location);
        return key != null && locationCache.containsKey(key);
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (!vaultRequested || economy != null || event.getProvider().getService() != Economy.class) {
            return;
        }
        if (setupEconomy()) {
            getLogger().info("Vault economy provider became available: " + economy.getName());
            updateCache();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (!vaultRequested || economy == null || event.getProvider().getService() != Economy.class) {
            return;
        }
        if (event.getProvider().getProvider() == economy) {
            getLogger().severe("Vault economy provider was unregistered. Slot payments are blocked.");
            economy = null;
            updateCache();
        }
    }

    public void reportVaultDepositFailure(Player player, EconomyResponse response) {
        player.sendMessage(getMsg("vault_deposit_failed"));
        getLogger().severe("Vault deposit failed for " + player.getName() + ": " + response.errorMessage);
    }
}
