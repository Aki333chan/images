package dev.addons.npc.listener;

import dev.addons.npc.model.NpcDefinition;
import dev.addons.npc.service.ActionExecutor;
import dev.addons.npc.service.DialogueService;
import dev.addons.npc.service.EconomyService;
import dev.addons.npc.service.MessageService;
import dev.addons.npc.service.NpcManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

public final class InteractionListener implements Listener {
    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final DialogueService dialogues;
    private final ActionExecutor actions;
    private final EconomyService economy;
    private final MessageService messages;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public InteractionListener(JavaPlugin plugin, NpcManager npcs, DialogueService dialogues,
                               ActionExecutor actions, EconomyService economy, MessageService messages) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.dialogues = dialogues;
        this.actions = actions;
        this.economy = economy;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        NpcDefinition npc = npcs.definition(event.getRightClicked());
        if (npc == null) {
            return;
        }
        event.setCancelled(true);
        interact(event.getPlayer(), event.getRightClicked(), npc, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeftClick(EntityDamageByEntityEvent event) {
        NpcDefinition npc = npcs.definition(event.getEntity());
        if (npc == null) {
            return;
        }
        if (plugin.getConfig().getBoolean("settings.cancel-left-click-damage", true)) {
            event.setCancelled(true);
        }
        if (event.getDamager() instanceof Player player) {
            interact(player, event.getEntity(), npc, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        cooldowns.remove(playerUuid);
        dialogues.clear(playerUuid);
        npcs.forgetViewer(playerUuid);
    }

    private void interact(Player player, Entity entity, NpcDefinition npc, boolean rightClick) {
        if (!npc.enabled() || !npc.clickMode().accepts(rightClick)) {
            return;
        }
        if (!player.hasPermission("addonsnpc.use")
                || (!npc.permission().isBlank() && !player.hasPermission(npc.permission()))) {
            messages.send(player, "no-permission");
            return;
        }
        double maxDistance = plugin.getConfig().getDouble("settings.interaction-distance", 5.0);
        if (!player.getWorld().equals(entity.getWorld())
                || player.getLocation().distanceSquared(entity.getLocation()) > maxDistance * maxDistance) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.get(player.getUniqueId());
        if (playerCooldowns == null) {
            playerCooldowns = new HashMap<>();
            cooldowns.put(player.getUniqueId(), playerCooldowns);
        }
        long availableAt = playerCooldowns.getOrDefault(npc.id(), 0L);
        if (availableAt > now) {
            double seconds = Math.ceil((availableAt - now) / 100.0) / 10.0;
            messages.send(player, "cooldown", Map.of("seconds", seconds));
            return;
        }
        playerCooldowns.put(npc.id(), now + (long) (npc.cooldownSeconds() * 1000));
        Map<String, Object> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("npc", MessageService.colorize(npc.name()));
        placeholders.put("balance", economy.format(economy.balance(player)));
        dialogues.send(player, npc, placeholders);
        npc.actions().forEach(action -> actions.execute(player, npc, action, placeholders));
    }
}
