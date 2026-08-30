package org.ChisaO_o.gladiatorArena;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Synchronous local journal for inventory recovery, pending payouts and active bets. */
final class RecoveryStore {
    record StoredBet(String arena, String team, UUID player, String playerName, double amount, boolean vault) {}

    private final GladiatorArena plugin;
    private final File file;
    private final YamlConfiguration data;

    RecoveryStore(GladiatorArena plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recovery.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    synchronized boolean hasInventory(UUID uuid) {
        return data.isConfigurationSection("inventories." + uuid);
    }

    synchronized boolean saveInventory(Player player, String arena) {
        String path = "inventories." + player.getUniqueId();
        if (data.isConfigurationSection(path)) return true;
        data.set(path + ".arena", arena);
        data.set(path + ".storage", player.getInventory().getStorageContents());
        data.set(path + ".armor", player.getInventory().getArmorContents());
        data.set(path + ".offhand", player.getInventory().getItemInOffHand());
        data.set(path + ".effects", new ArrayList<>(player.getActivePotionEffects()));
        data.set(path + ".health", player.getHealth());
        data.set(path + ".food", player.getFoodLevel());
        data.set(path + ".saturation", player.getSaturation());
        data.set(path + ".fire_ticks", player.getFireTicks());
        data.set(path + ".level", player.getLevel());
        data.set(path + ".exp", player.getExp());
        return saveNow();
    }

    synchronized boolean restoreInventory(Player player) {
        String path = "inventories." + player.getUniqueId();
        if (!data.isConfigurationSection(path) || player.isDead()) return false;
        try {
            player.getInventory().clear();
            player.getInventory().setStorageContents(readItems(path + ".storage", player.getInventory().getStorageContents().length));
            player.getInventory().setArmorContents(readItems(path + ".armor", 4));
            ItemStack offhand = data.getItemStack(path + ".offhand");
            player.getInventory().setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);
            for (PotionEffect current : player.getActivePotionEffects()) player.removePotionEffect(current.getType());
            for (Object value : data.getList(path + ".effects", List.of())) {
                if (value instanceof PotionEffect effect) player.addPotionEffect(effect);
            }
            double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            player.setHealth(Math.max(0.01, Math.min(maxHealth, data.getDouble(path + ".health", maxHealth))));
            player.setFoodLevel(Math.max(0, Math.min(20, data.getInt(path + ".food", 20))));
            player.setSaturation((float) Math.max(0.0, data.getDouble(path + ".saturation", 5.0)));
            player.setFireTicks(Math.max(0, data.getInt(path + ".fire_ticks", 0)));
            player.setLevel(Math.max(0, data.getInt(path + ".level", 0)));
            player.setExp((float) Math.max(0.0, Math.min(1.0, data.getDouble(path + ".exp", 0.0))));
            player.updateInventory();
            data.set(path, null);
            return saveNow();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось восстановить инвентарь " + player.getName(), exception);
            return false;
        }
    }

    synchronized void saveSpectator(Player player, String arena) {
        String path = "spectators." + player.getUniqueId();
        if (data.isConfigurationSection(path)) return;
        data.set(path + ".arena", arena);
        data.set(path + ".location", player.getLocation());
        data.set(path + ".gamemode", player.getGameMode().name());
        data.set(path + ".allow_flight", player.getAllowFlight());
        data.set(path + ".flying", player.isFlying());
        saveNow();
    }

    synchronized boolean restoreSpectator(Player player) {
        String path = "spectators." + player.getUniqueId();
        if (!data.isConfigurationSection(path)) return false;
        Location location = data.getLocation(path + ".location");
        GameMode mode;
        try {
            mode = GameMode.valueOf(data.getString(path + ".gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException exception) {
            mode = GameMode.SURVIVAL;
        }
        player.setGameMode(mode);
        player.setAllowFlight(data.getBoolean(path + ".allow_flight", mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR));
        if (player.getAllowFlight()) player.setFlying(data.getBoolean(path + ".flying", false));
        if (location != null && location.getWorld() != null) player.teleport(location);
        data.set(path, null);
        saveNow();
        return true;
    }

    synchronized boolean hasSpectator(UUID uuid) {
        return data.isConfigurationSection("spectators." + uuid);
    }

    synchronized void queuePayout(UUID uuid, String name, double amount, boolean vault) {
        if (!Double.isFinite(amount) || amount <= 0.0) return;
        String path = "payouts." + uuid + "." + (vault ? "vault" : "items");
        data.set(path, data.getDouble(path, 0.0) + amount);
        data.set("payouts." + uuid + ".name", name);
        saveNow();
    }

    synchronized double claimItemPayout(Player player, Material main, Material sub) {
        String path = "payouts." + player.getUniqueId() + ".items";
        double amount = data.getDouble(path, 0.0);
        if (amount <= 0.0) return 0.0;
        giveItems(player, amount, main, sub);
        data.set(path, null);
        cleanupPayout(player.getUniqueId());
        saveNow();
        return amount;
    }

    synchronized double claimVaultPayout(Player player, Economy economy) {
        String path = "payouts." + player.getUniqueId() + ".vault";
        double amount = data.getDouble(path, 0.0);
        if (amount <= 0.0 || economy == null) return 0.0;
        EconomyResponse response = economy.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Vault отклонил ожидающую выплату для " + player.getName() + ": " + response.errorMessage);
            return 0.0;
        }
        data.set(path, null);
        cleanupPayout(player.getUniqueId());
        saveNow();
        return amount;
    }

    synchronized void saveBet(String arena, String team, UUID player, String playerName, double amount, boolean vault) {
        String path = "bets." + arena + "." + team + "." + player;
        data.set(path + ".name", playerName);
        data.set(path + ".amount", amount);
        data.set(path + ".vault", vault);
        saveNow();
    }

    synchronized void removeBet(String arena, String team, UUID player) {
        data.set("bets." + arena + "." + team + "." + player, null);
        saveNow();
    }

    synchronized void clearArenaBets(String arena) {
        data.set("bets." + arena, null);
        saveNow();
    }

    synchronized List<StoredBet> allBets() {
        List<StoredBet> result = new ArrayList<>();
        ConfigurationSection arenas = data.getConfigurationSection("bets");
        if (arenas == null) return result;
        for (String arena : arenas.getKeys(false)) {
            ConfigurationSection teams = arenas.getConfigurationSection(arena);
            if (teams == null) continue;
            for (String team : teams.getKeys(false)) {
                ConfigurationSection players = teams.getConfigurationSection(team);
                if (players == null) continue;
                for (String id : players.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(id);
                        String base = id;
                        result.add(new StoredBet(arena, team, uuid, players.getString(base + ".name", "Unknown"),
                            players.getDouble(base + ".amount"), players.getBoolean(base + ".vault")));
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Пропущена повреждённая запись ставки: " + arena + "/" + team + "/" + id);
                    }
                }
            }
        }
        return result;
    }

    static void giveItems(Player player, double amount, Material main, Material sub) {
        long tenths = Math.max(0L, Math.round(amount * 10.0));
        giveStacked(player, main, tenths / 10L);
        giveStacked(player, sub, tenths % 10L);
    }

    private static void giveStacked(Player player, Material material, long amount) {
        while (amount > 0L) {
            int stack = (int) Math.min(material.getMaxStackSize(), amount);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, stack));
            for (ItemStack item : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
            amount -= stack;
        }
    }

    private ItemStack[] readItems(String path, int size) {
        List<?> list = data.getList(path, List.of());
        ItemStack[] result = new ItemStack[size];
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            Object item = list.get(i);
            if (item instanceof ItemStack stack) result[i] = stack;
        }
        return result;
    }

    private void cleanupPayout(UUID uuid) {
        ConfigurationSection section = data.getConfigurationSection("payouts." + uuid);
        if (section == null) return;
        if (section.getDouble("vault", 0.0) <= 0.0 && section.getDouble("items", 0.0) <= 0.0) {
            data.set("payouts." + uuid, null);
        }
    }

    private boolean saveNow() {
        try {
            file.getParentFile().mkdirs();
            data.save(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить recovery.yml", exception);
            return false;
        }
    }
}
