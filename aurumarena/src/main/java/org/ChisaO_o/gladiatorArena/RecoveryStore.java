package org.ChisaO_o.gladiatorArena;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Synchronous local journal for inventory recovery, pending payouts and active bets. */
final class RecoveryStore {
    record StoredBet(String arena, String team, UUID player, String playerName, double amount, boolean vault) {}

    /**
     * Невыплаченные деньги.
     *
     * Кроме суммы и ключа запись хранит, ИЗ КАКОЙ кассы шла выплата. Без
     * этого повтор пришлось бы отправлять наугад, а касса ставок и финальный
     * пул — разные счета: списание не с того оставило бы один счёт в минусе,
     * а другой с зависшими деньгами.
     */
    record PendingPayout(UUID player, double amount, String key, String arena, String purpose) {}

    /**
     * Долг, доставшийся от версии 1.4.0.
     *
     * За ним нет ни счёта, ни проводки: в эпоху Vault выплата была простым
     * depositPlayer, и очередь хранила только сумму. Выдать такое
     * автоматически нельзя — это создало бы валюту мимо ledger.
     */
    record LegacyDebt(UUID player, String playerName, double amount) {}
    record StoredExperience(int points, int levels) {
        boolean isEmpty() { return points <= 0 && levels <= 0; }
    }

    private final GladiatorArena plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, InventorySnapshot> liveInventories = new HashMap<>();

    RecoveryStore(GladiatorArena plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recovery.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    synchronized boolean hasInventory(UUID uuid) {
        return liveInventories.containsKey(uuid) || data.isConfigurationSection("inventories." + uuid);
    }

    synchronized boolean saveInventory(Player player, String arena) {
        UUID uuid = player.getUniqueId();
        String path = "inventories." + uuid;
        if (data.isConfigurationSection(path)) return true;
        InventorySnapshot snapshot = InventorySnapshot.capture(player);
        liveInventories.put(uuid, snapshot);
        data.set(path + ".arena", arena);
        // Store lists explicitly. YamlConfiguration keeps a freshly assigned array as an
        // array in memory, while getList() only starts seeing it after a disk reload.
        // That made an immediate team leave clear the arena kit without returning the
        // player's items. Lists behave identically before and after serialization.
        data.set(path + ".storage", serializableItems(player.getInventory().getStorageContents()));
        data.set(path + ".armor", serializableItems(player.getInventory().getArmorContents()));
        data.set(path + ".offhand", player.getInventory().getItemInOffHand());
        data.set(path + ".effects", new ArrayList<>(player.getActivePotionEffects()));
        data.set(path + ".health", player.getHealth());
        data.set(path + ".food", player.getFoodLevel());
        data.set(path + ".saturation", player.getSaturation());
        data.set(path + ".fire_ticks", player.getFireTicks());
        data.set(path + ".held_slot", player.getInventory().getHeldItemSlot());
        data.set(path + ".level", player.getLevel());
        data.set(path + ".exp", player.getExp());
        data.set(path + ".total_exp", player.getTotalExperience());
        if (saveNow()) return true;
        liveInventories.remove(uuid);
        data.set(path, null);
        return false;
    }

    synchronized boolean restoreInventory(Player player) {
        UUID uuid = player.getUniqueId();
        String path = "inventories." + uuid;
        InventorySnapshot snapshot = liveInventories.get(uuid);
        if ((snapshot == null && !data.isConfigurationSection(path)) || player.isDead()) return false;
        try {
            if (snapshot == null) snapshot = readSnapshot(player, path);
            snapshot.restore(player);
            player.updateInventory();
            liveInventories.remove(uuid);
            data.set(path, null);
            if (!saveNow()) {
                plugin.getLogger().warning("Инвентарь " + player.getName()
                    + " восстановлен, но не удалось удалить его запись из recovery.yml.");
            }
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось восстановить инвентарь " + player.getName(), exception);
            return false;
        }
    }

    private InventorySnapshot readSnapshot(Player player, String path) {
        List<PotionEffect> effects = new ArrayList<>();
        for (Object value : data.getList(path + ".effects", List.of())) {
            if (value instanceof PotionEffect effect) effects.add(effect);
        }
        var attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double maxHealth = attribute == null ? 20.0 : attribute.getValue();
        return new InventorySnapshot(
            readItems(path + ".storage", player.getInventory().getStorageContents().length),
            readItems(path + ".armor", 4),
            data.getItemStack(path + ".offhand"), effects,
            data.getDouble(path + ".health", maxHealth), data.getInt(path + ".food", 20),
            (float) data.getDouble(path + ".saturation", 5.0), data.getInt(path + ".fire_ticks", 0),
            data.getInt(path + ".held_slot", player.getInventory().getHeldItemSlot()),
            data.getInt(path + ".level", 0), (float) data.getDouble(path + ".exp", 0.0),
            data.getInt(path + ".total_exp", 0));
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

    /**
     * Отложить денежную выплату вместе с ключом идемпотентности.
     *
     * Ключ обязателен именно здесь. Выплата откладывается тогда, когда ответа
     * от Core не пришло, — а «не пришло» не значит «не проведено»: связь могла
     * оборваться после commit. Повтор с тем же ключом Core распознаёт и второй
     * раз не платит; повтор с новым ключом заплатил бы дважды.
     */
    synchronized void queueMoneyPayout(UUID uuid, String name, double amount, String key,
                                       String arena, String purpose) {
        if (!Double.isFinite(amount) || amount <= 0.0 || key == null || key.isBlank()) return;
        String base = "money-payouts." + uuid + "." + sanitizeKey(key);
        data.set(base + ".amount", amount);
        data.set(base + ".key", key);
        data.set(base + ".arena", arena);
        data.set(base + ".purpose", purpose);
        data.set("money-payouts." + uuid + ".name", name);
        saveNow();
    }

    /** Что осталось выплатить игроку деньгами; пары «сумма, ключ». */
    synchronized List<PendingPayout> moneyPayouts(UUID uuid) {
        List<PendingPayout> result = new ArrayList<>();
        ConfigurationSection section = data.getConfigurationSection("money-payouts." + uuid);
        if (section == null) return result;
        for (String entry : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(entry);
            if (item == null) continue;
            double amount = item.getDouble("amount", 0.0);
            String key = item.getString("key", "");
            String arena = item.getString("arena", "");
            String purpose = item.getString("purpose", "BET");
            if (amount > 0.0 && !key.isBlank() && !arena.isBlank()) {
                result.add(new PendingPayout(uuid, amount, key, arena, purpose));
            }
        }
        return result;
    }

    /** Все отложенные денежные выплаты — для восстановления при старте. */
    synchronized List<PendingPayout> allMoneyPayouts() {
        List<PendingPayout> result = new ArrayList<>();
        ConfigurationSection root = data.getConfigurationSection("money-payouts");
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            try {
                result.addAll(moneyPayouts(UUID.fromString(id)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Пропущена повреждённая запись выплаты: " + id);
            }
        }
        return result;
    }

    synchronized void clearMoneyPayout(PendingPayout payout) {
        data.set("money-payouts." + payout.player() + "." + sanitizeKey(payout.key()), null);
        ConfigurationSection section = data.getConfigurationSection("money-payouts." + payout.player());
        if (section != null && section.getKeys(false).stream().allMatch("name"::equals)) {
            data.set("money-payouts." + payout.player(), null);
        }
        saveNow();
    }

    /**
     * Долги из версии 1.4.0: очередь выплат старого Vault-формата.
     *
     * Эти деньги арена обещала, но не выдала, и за ними не стоит ни один счёт
     * ledger: в эпоху Vault выплата была бы просто depositPlayer. Выдать их
     * автоматически значило бы создать деньги из воздуха мимо всякой
     * проводки — ровно то, ради чего ledger и заводился. Поэтому плагин их
     * только показывает администратору, а решение остаётся за человеком.
     */
    synchronized Map<UUID, LegacyDebt> legacyMoneyDebts() {
        Map<UUID, LegacyDebt> result = new HashMap<>();
        ConfigurationSection root = data.getConfigurationSection("payouts");
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            double amount = data.getDouble("payouts." + id + ".vault", 0.0);
            if (amount <= 0.0) continue;
            try {
                UUID uuid = UUID.fromString(id);
                result.put(uuid, new LegacyDebt(uuid, data.getString("payouts." + id + ".name", "Unknown"), amount));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Пропущена повреждённая запись старой выплаты: " + id);
            }
        }
        return result;
    }

    /** Убрать старую запись после того, как администратор рассчитался. */
    synchronized void clearLegacyMoneyDebt(UUID uuid) {
        data.set("payouts." + uuid + ".vault", null);
        cleanupPayout(uuid);
        saveNow();
    }

    /**
     * Есть ли уже учтённая ставка этого игрока на этой арене.
     *
     * Нужна журналу списаний: билет, чья ставка уже записана, закрывается без
     * возврата — иначе деньги вернулись бы дважды. См. ArenaEconomyService.
     */
    synchronized boolean hasBet(String arena, UUID player) {
        ConfigurationSection teams = data.getConfigurationSection("bets." + arena);
        if (teams == null) return false;
        for (String team : teams.getKeys(false)) {
            if (data.getDouble("bets." + arena + "." + team + "." + player + ".amount", 0.0) > 0.0) return true;
        }
        return false;
    }

    /** Ключ идемпотентности как имя узла YAML: двоеточия и точки там не живут. */
    private static String sanitizeKey(String key) {
        return key.replace(':', '-').replace('.', '-');
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

    synchronized void queueExperience(UUID uuid, int amount, boolean levels) {
        if (amount <= 0) return;
        String path = "experience." + uuid + "." + (levels ? "levels" : "points");
        long queued = (long) data.getInt(path, 0) + amount;
        data.set(path, (int) Math.min(1_000_000_000L, queued));
        saveNow();
    }

    synchronized StoredExperience claimExperience(Player player) {
        String path = "experience." + player.getUniqueId();
        int points = Math.max(0, data.getInt(path + ".points", 0));
        int levels = Math.max(0, data.getInt(path + ".levels", 0));
        if (points <= 0 && levels <= 0) return new StoredExperience(0, 0);
        if (points > 0) player.giveExp(points);
        if (levels > 0) player.giveExpLevels(levels);
        data.set(path, null);
        saveNow();
        return new StoredExperience(points, levels);
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

    static List<ItemStack> serializableItems(ItemStack[] items) {
        return new ArrayList<>(Arrays.asList(items.clone()));
    }

    static ItemStack[] decodeItems(Object stored, int size) {
        ItemStack[] result = new ItemStack[size];
        List<?> values;
        if (stored instanceof List<?> list) values = list;
        else if (stored instanceof Object[] array) values = Arrays.asList(array);
        else values = List.of();
        for (int i = 0; i < Math.min(size, values.size()); i++) {
            Object item = values.get(i);
            if (item instanceof ItemStack stack) result[i] = stack;
        }
        return result;
    }

    private ItemStack[] readItems(String path, int size) {
        // Object[] support also recovers snapshots created by affected versions that
        // are still present in memory and have not yet been reloaded from recovery.yml.
        return decodeItems(data.get(path), size);
    }

    static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) result[i] = items[i] == null ? null : items[i].clone();
        return result;
    }

    private record InventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offhand,
                                     List<PotionEffect> effects, double health, int food, float saturation,
                                     int fireTicks, int heldSlot, int level, float exp, int totalExp) {
        static InventorySnapshot capture(Player player) {
            ItemStack hand = player.getInventory().getItemInOffHand();
            return new InventorySnapshot(cloneItems(player.getInventory().getStorageContents()),
                cloneItems(player.getInventory().getArmorContents()), hand.getType().isAir() ? null : hand.clone(),
                new ArrayList<>(player.getActivePotionEffects()), player.getHealth(), player.getFoodLevel(),
                player.getSaturation(), player.getFireTicks(), player.getInventory().getHeldItemSlot(),
                player.getLevel(), player.getExp(), player.getTotalExperience());
        }

        void restore(Player player) {
            player.getInventory().clear();
            player.getInventory().setStorageContents(cloneItems(storage));
            player.getInventory().setArmorContents(cloneItems(armor));
            player.getInventory().setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand.clone());
            for (PotionEffect current : player.getActivePotionEffects()) player.removePotionEffect(current.getType());
            for (PotionEffect effect : effects) player.addPotionEffect(effect);
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double maxHealth = attribute == null ? 20.0 : attribute.getValue();
            player.setHealth(Math.max(0.01, Math.min(maxHealth, health)));
            player.setFoodLevel(Math.max(0, Math.min(20, food)));
            player.setSaturation(Math.max(0.0f, Math.min(20.0f, saturation)));
            player.setFireTicks(Math.max(0, fireTicks));
            player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));
            player.setTotalExperience(Math.max(0, totalExp));
            player.setLevel(Math.max(0, level));
            player.setExp(Math.max(0.0f, Math.min(1.0f, exp)));
        }
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
