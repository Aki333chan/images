package org.ChisaO_o.simpleSlots;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class SpinTask extends BukkitRunnable {
    private static final Map<Material, Integer> CHANCES = createChances();

    private final SimpleSlots plugin;
    private final SlotMachine machine;
    private final Player player;
    private final Material[] slots = new Material[3];
    private int ticks;

    SpinTask(SimpleSlots plugin, SlotMachine machine, Player player) {
        this.plugin = plugin;
        this.machine = machine;
        this.player = player;
        machine.isSpinning = true;
    }

    private static Map<Material, Integer> createChances() {
        Map<Material, Integer> chances = new LinkedHashMap<>();
        chances.put(Material.SWEET_BERRIES, 50);
        chances.put(Material.COOKED_MUTTON, 30);
        chances.put(Material.NAUTILUS_SHELL, 12);
        chances.put(Material.GOLDEN_APPLE, 5);
        chances.put(Material.HEART_OF_THE_SEA, 3);
        return Map.copyOf(chances);
    }

    @Override
    public void run() {
        try {
            ticks += 5;
            for (int index = 0; index < slots.length; index++) {
                slots[index] = getRandomIcon();
            }
            updateVisuals();
            player.playSound(machine.shelfLoc != null ? machine.shelfLoc : player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.5f);

            if (ticks >= 80) {
                cancelSpin();
                calculatePayout();
            }
        } catch (RuntimeException exception) {
            if (machine.isSpinning) cancelSpin();
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Slot spin failed for machine '" + machine.id + "' and player " + player.getName(), exception);
            player.sendMessage("§cОшибка автомата. Сообщите администратору.");
        }
    }

    void cancelSpin() {
        cancel();
        machine.isSpinning = false;
        plugin.removeTask(this);
    }

    private Material getRandomIcon() {
        int total = CHANCES.values().stream().mapToInt(Integer::intValue).sum();
        int random = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<Material, Integer> entry : CHANCES.entrySet()) {
            random -= entry.getValue();
            if (random < 0) {
                return entry.getKey();
            }
        }
        return Material.SWEET_BERRIES;
    }

    private void updateVisuals() {
        if (machine.shelfLoc == null) {
            return;
        }
        Block block = machine.shelfLoc.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder holder)) {
            return;
        }

        holder.getInventory().setItem(0, new ItemStack(slots[0]));
        holder.getInventory().setItem(1, new ItemStack(slots[1]));
        holder.getInventory().setItem(2, new ItemStack(slots[2]));

        BlockData data = block.getBlockData();
        if (data instanceof Powerable powerable) {
            boolean originalPower = powerable.isPowered();
            powerable.setPowered(!originalPower);
            block.setBlockData(powerable, true);
            powerable.setPowered(originalPower);
            block.setBlockData(powerable, true);
        }
    }

    private void calculatePayout() {
        int multiplier = calculateMultiplier();
        if (multiplier <= 0) {
            player.sendMessage(plugin.getMsg("lose"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        double winnings = machine.bet * multiplier;
        if (!giveWinnings(winnings)) return;
        String symbol = plugin.getConfig().getString("vault_symbol", "$");
        String winningsText = plugin.isVaultRequested()
                ? plugin.formatNumber(winnings) + symbol
                : plugin.formatNumber(winnings) + " шт.";
        player.sendMessage(plugin.getMsg("win")
                .replace("%win%", winningsText)
                .replace("%multi%", String.valueOf(multiplier)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    private int calculateMultiplier() {
        int hearts = (int) Arrays.stream(slots).filter(material -> material == Material.HEART_OF_THE_SEA).count();
        if (hearts > 0) {
            return hearts == 1 ? 2 : hearts == 2 ? 15 : 100;
        }
        if (slots[0] != slots[1] || slots[1] != slots[2]) {
            return 0;
        }
        return switch (slots[0]) {
            case SWEET_BERRIES -> 3;
            case COOKED_MUTTON -> 5;
            case NAUTILUS_SHELL -> 10;
            case GOLDEN_APPLE -> 20;
            default -> 0;
        };
    }

    private boolean giveWinnings(double amount) {
        if (plugin.isVaultRequested()) {
            if (!plugin.isVaultReady()) {
                player.sendMessage(plugin.getMsg("vault_deposit_failed"));
                return false;
            }
            EconomyResponse response = plugin.getEconomy().depositPlayer(player, amount);
            if (!response.transactionSuccess()) {
                plugin.reportVaultDepositFailure(player, response);
                return false;
            }
            return true;
        }

        int itemAmount = (int) amount;
        Material main = plugin.getMainCurrency();
        Material sub = plugin.getSubCurrency();
        if (main != null) {
            giveItem(main, itemAmount / 10);
            giveItem(sub, itemAmount % 10);
        } else {
            giveItem(sub, itemAmount);
        }
        return true;
    }

    private void giveItem(Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, material.getMaxStackSize());
            ItemStack stack = new ItemStack(material, stackSize);
            player.getInventory().addItem(stack).values()
                    .forEach(leftover -> player.getWorld().dropItem(player.getLocation(), leftover));
            remaining -= stackSize;
        }
    }
}
