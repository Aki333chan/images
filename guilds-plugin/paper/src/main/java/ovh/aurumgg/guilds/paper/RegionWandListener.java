package ovh.aurumgg.guilds.paper;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.permission.ActorSelectorLimits;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.RegionSelector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Одна палка для осмотра WorldGuard-регионов и выделения FAWE.
 *
 * <p>Оставлять один материал одновременно штатным wand в WorldGuard и FAWE
 * нельзя: оба слушателя получают один клик, и кто из них победит, зависит от
 * порядка загрузки. Поэтому этот обработчик забирает подходящий клик целиком.
 * На занятой земле он показывает регионы, на свободной — ставит точку через
 * публичный WorldEdit API. FAWE реализует тот же API, так что никаких его
 * внутренних классов и привязки к конкретной сборке здесь нет.</p>
 */
final class RegionWandListener implements Listener {

    static final String USE_PERMISSION = "aurumguilds.region-wand.use";
    static final String BYPASS_PERMISSION = "aurumguilds.region-wand.bypass";

    private final AurumGuildsPlugin plugin;
    private final GuildService guilds;
    private final WorldGuardBridge regions;
    private final Map<UUID, Long> lastMessageAt = new HashMap<>();
    private volatile Settings settings;

    RegionWandListener(AurumGuildsPlugin plugin, GuildService guilds, WorldGuardBridge regions) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.regions = regions;
        reload();
    }

    void reload() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("region-wand");
        String materialName = section == null ? "STICK" : section.getString("material", "STICK");
        Material material = Material.matchMaterial(materialName == null ? "STICK" : materialName);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("region-wand.material «" + materialName
                    + "» не существует — используется STICK");
            material = Material.STICK;
        }

        settings = new Settings(
                section == null || section.getBoolean("enabled", true),
                material,
                section == null || section.getBoolean("ignore-global-region", true),
                section == null || section.getBoolean("block-selection-inside-regions", true),
                section == null || section.getBoolean("show-guild", true),
                section == null || section.getBoolean("admin-sneak-bypass", true),
                clamp(section == null ? 500L : section.getLong("message-cooldown-ms", 500L), 0L, 5_000L),
                (int) clamp(section == null ? 5L : section.getLong("max-regions", 5L), 1L, 10L));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Settings current = settings;
        if (!current.enabled()
                || event.getItem() == null
                || event.getItem().getType() != current.material()) return;

        // Не превращаем каждую обычную палку в инструмент у человека, которому
        // FAWE всё равно не разрешает пользоваться выделением.
        if (!event.getPlayer().hasPermission(USE_PERMISSION)
                || !event.getPlayer().hasPermission("worldedit.wand")
                || !event.getPlayer().hasPermission("worldedit.selection.pos")) return;

        Block block = event.getClickedBlock();
        List<WorldGuardBridge.RegionSnapshot> found = regions.at(
                block.getLocation(), current.ignoreGlobalRegion());
        boolean bypass = current.adminSneakBypass()
                && event.getPlayer().isSneaking()
                && event.getPlayer().hasPermission(BYPASS_PERMISSION);

        if (!found.isEmpty() && !bypass) {
            if (current.blockSelectionInsideRegions()) deny(event);
            show(event, found, current);
            if (current.blockSelectionInsideRegions()) return;
        }

        // В том числе для Shift+bypass: клик всё равно забираем у обоих
        // штатных wand, иначе точка могла бы установиться дважды.
        if (select(event, block)) deny(event);
    }

    private boolean select(PlayerInteractEvent event, Block block) {
        try {
            var actor = BukkitAdapter.adapt(event.getPlayer());
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

            var world = BukkitAdapter.adapt(block.getWorld());
            RegionSelector selector = session.getRegionSelector(world);
            BlockVector3 point = BlockVector3.at(block.getX(), block.getY(), block.getZ());
            boolean changed;
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                changed = selector.selectPrimary(point, ActorSelectorLimits.forActor(actor));
                if (changed) selector.explainPrimarySelection(actor, session, point);
            } else {
                changed = selector.selectSecondary(point, ActorSelectorLimits.forActor(actor));
                if (changed) selector.explainSecondarySelection(actor, session, point);
            }
            session.dispatchCUISelection(actor);
            return true;
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось поставить точку FAWE для " + event.getPlayer().getName(), error);
            return false;
        }
    }

    private void show(PlayerInteractEvent event, List<WorldGuardBridge.RegionSnapshot> found, Settings current) {
        long now = System.currentTimeMillis();
        UUID playerId = event.getPlayer().getUniqueId();
        Long previous = lastMessageAt.put(playerId, now);
        if (previous != null && now - previous < current.messageCooldownMs()) return;

        List<String> lines = new ArrayList<>();
        lines.add(plugin.text("regionWand.header"));
        int shown = Math.min(current.maxRegions(), found.size());
        for (int i = 0; i < shown; i++) {
            WorldGuardBridge.RegionSnapshot region = found.get(i);
            String owners = region.owners().isEmpty()
                    ? plugin.text("regionWand.ownerNone")
                    : String.join(", ", region.owners());
            lines.add(plugin.text("regionWand.region", Map.of(
                    "region", region.id(),
                    "priority", String.valueOf(region.priority()),
                    "owners", owners)));
            if (current.showGuild()) {
                guilds.regionOwner(event.getPlayer().getWorld().getName(), region.id())
                        .flatMap(guilds::byId)
                        .ifPresent(guild -> lines.add(plugin.text("regionWand.guild", Map.of(
                                "guild", guild.name()))));
            }
        }
        if (found.size() > shown) {
            lines.add(plugin.text("regionWand.more", Map.of(
                    "count", String.valueOf(found.size() - shown))));
        }
        Msg.lines(event.getPlayer(), lines);
    }

    private static void deny(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessageAt.remove(event.getPlayer().getUniqueId());
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Settings(
            boolean enabled,
            Material material,
            boolean ignoreGlobalRegion,
            boolean blockSelectionInsideRegions,
            boolean showGuild,
            boolean adminSneakBypass,
            long messageCooldownMs,
            int maxRegions) {}
}
