package dev.addons.npc.service;

import dev.addons.npc.config.NpcRepository;
import dev.addons.npc.model.LookMode;
import dev.addons.npc.model.NpcDefinition;
import dev.addons.npc.platform.HeadLookController;
import dev.addons.npc.platform.MannequinAdapter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class NpcManager {
    private final JavaPlugin plugin;
    private final NpcRepository repository;
    private final SkinService skinService;
    private final MannequinAdapter adapter;
    private final HeadLookController headLookController;
    private final NamespacedKey npcIdKey;
    private final NamespacedKey labelIdKey;
    private final Map<String, UUID> spawned = new HashMap<>();
    private final Map<String, UUID> labels = new HashMap<>();
    private final Map<UUID, Set<UUID>> visibleViewers = new HashMap<>();
    private final Set<String> trackingPlayers = new HashSet<>();
    private BukkitTask updateTask;

    public NpcManager(JavaPlugin plugin, NpcRepository repository, SkinService skinService,
                      MannequinAdapter adapter) {
        this.plugin = plugin;
        this.repository = repository;
        this.skinService = skinService;
        this.adapter = adapter;
        this.headLookController = new HeadLookController(plugin);
        this.npcIdKey = new NamespacedKey(plugin, "npc-id");
        this.labelIdKey = new NamespacedKey(plugin, "npc-label-id");
    }

    public void start() {
        if (updateTask != null) updateTask.cancel();
        discoverExisting();
        syncAll();
        int period = Math.max(1, plugin.getConfig().getInt("settings.look-task-period-ticks", 10));
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateNpcState, period, period);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        spawned.clear();
        labels.clear();
        visibleViewers.clear();
        trackingPlayers.clear();
    }

    public void syncAll() {
        for (NpcDefinition definition : repository.all()) {
            safeSync(definition, "startup/reload");
        }
        for (String id : Map.copyOf(spawned).keySet()) {
            if (repository.get(id) == null) {
                removeEntity(id);
            }
        }
        for (String id : Map.copyOf(labels).keySet()) {
            if (repository.get(id) == null) removeEntity(id);
        }
    }

    public void syncWorld(World world) {
        discoverExisting(world);
        for (NpcDefinition definition : repository.all()) {
            if (definition.location().world().equalsIgnoreCase(world.getName())) safeSync(definition, "world load");
        }
    }

    private void safeSync(NpcDefinition definition, String reason) {
        try {
            sync(definition);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not synchronize NPC '" + definition.id() + "' during " + reason, exception);
        }
    }

    public LivingEntity sync(NpcDefinition definition) {
        if (!definition.enabled()) {
            removeEntity(definition.id());
            return null;
        }
        Location location = definition.location().resolve();
        if (location == null) {
            plugin.getLogger().warning("World '" + definition.location().world() + "' for NPC '"
                    + definition.id() + "' is not loaded");
            return null;
        }
        if (!location.getChunk().isLoaded()) location.getChunk().load();
        reconcileEntities(List.of(location.getChunk().getEntities()));
        LivingEntity living = entity(definition.id()).orElse(null);
        if (living != null && living.getType() != definition.entityType()) {
            visibleViewers.remove(living.getUniqueId());
            living.remove();
            spawned.remove(definition.id());
            living = null;
        }
        if (living == null) {
            living = spawnLiving(definition, location);
            spawned.put(definition.id(), living.getUniqueId());
        } else if (!living.getWorld().equals(location.getWorld())
                || living.getLocation().distanceSquared(location) > 0.0001) {
            living.teleport(location);
        }
        apply(definition, living);
        resetRotation(definition, living);
        syncLabel(definition, living);
        updateVisibility(definition, living, label(definition.id()).orElse(null));
        return living;
    }

    @SuppressWarnings("unchecked")
    private LivingEntity spawnLiving(NpcDefinition definition, Location location) {
        Class<? extends Entity> entityClass = definition.entityType().getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            throw new IllegalStateException("Entity type is not living: " + definition.entityType());
        }
        Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) entityClass;
        return location.getWorld().spawn(location, livingClass, CreatureSpawnEvent.SpawnReason.CUSTOM, false,
                entity -> entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, definition.id()));
    }

    private void apply(NpcDefinition definition, LivingEntity living) {
        living.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, definition.id());
        living.setPersistent(true);
        living.setRemoveWhenFarAway(false);
        living.setInvulnerable(true);
        living.setSilent(true);
        living.setAI(false);
        living.setGravity(!(living instanceof Mannequin));
        living.setCollidable(false);
        living.setCustomName(null);
        living.setCustomNameVisible(false);
        living.setVisibleByDefault(false);
        EntityEquipment equipment = living.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(itemOrAir(definition.rightHand()), true);
            equipment.setItemInOffHand(itemOrAir(definition.leftHand()), true);
            // Paper exposes equipment for Mannequins too, but drop chances are valid only for Mob entities.
            if (living instanceof Mob) {
                equipment.setItemInMainHandDropChance(0.0f);
                equipment.setItemInOffHandDropChance(0.0f);
            }
        }
        if (living instanceof Mannequin mannequin) {
            mannequin.setImmovable(true);
            mannequin.setPose(definition.pose());
            adapter.setDescription(mannequin, "");
            skinService.apply(mannequin, definition.skin());
        }
    }

    private void syncLabel(NpcDefinition definition, LivingEntity living) {
        Location target = labelLocation(living);
        TextDisplay display = label(definition.id()).orElse(null);
        if (display == null) {
            display = target.getWorld().spawn(target, TextDisplay.class,
                    entity -> entity.getPersistentDataContainer().set(
                            labelIdKey, PersistentDataType.STRING, definition.id()));
            labels.put(definition.id(), display.getUniqueId());
        } else if (!display.getWorld().equals(target.getWorld())
                || display.getLocation().distanceSquared(target) > 0.0001) {
            display.teleport(target);
        }
        String text = MessageService.colorize(definition.name());
        if (!definition.description().isBlank()) text += "\n" + MessageService.colorize(definition.description());
        display.setText(text);
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setLineWidth(240);
        display.setViewRange(Math.max(1.0f, (float) (definition.nameVisibilityRange() / 64.0)));
        display.setPersistent(true);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setVisibleByDefault(false);
    }

    private Location labelLocation(LivingEntity living) {
        double offset = plugin.getConfig().getDouble("settings.name-height-offset", 0.35);
        return living.getLocation().add(0, living.getHeight() + offset, 0);
    }

    public void delete(String id) {
        removeEntity(id);
        repository.remove(id);
        repository.save();
    }

    public void removeEntity(String id) {
        entity(id).ifPresent(entity -> {
            visibleViewers.remove(entity.getUniqueId());
            entity.remove();
        });
        label(id).ifPresent(entity -> {
            visibleViewers.remove(entity.getUniqueId());
            entity.remove();
        });
        spawned.remove(id.toLowerCase());
        labels.remove(id.toLowerCase());
        trackingPlayers.remove(id.toLowerCase());
    }

    /**
     * Removes every loaded physical entity carrying this plugin's NPC or label id.
     * This intentionally does not touch the repository and is used to recover old
     * entities whose definition has already been deleted.
     */
    public int purgePhysical(String rawId) {
        String id = NpcDefinition.normalizeId(rawId);
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String taggedId = taggedId(entity);
                if (taggedId != null && taggedId.equalsIgnoreCase(id)) {
                    forget(entity, taggedId);
                    entity.remove();
                    removed++;
                }
            }
        }
        spawned.remove(id);
        labels.remove(id);
        return removed;
    }

    public int cleanupLoadedOrphans() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            removed += cleanupOrphans(world.getEntities());
        }
        return removed;
    }

    public int cleanupOrphans(Iterable<? extends Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            String id = taggedId(entity);
            if (id != null && repository.get(id) == null) {
                forget(entity, id);
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public int reconcileEntities(Iterable<? extends Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            String npcId = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
            if (npcId != null) {
                String id = npcId.toLowerCase();
                UUID known = spawned.get(id);
                if (repository.get(id) == null || !(entity instanceof LivingEntity)
                        || known != null && !known.equals(entity.getUniqueId())) {
                    entity.remove();
                    removed++;
                } else spawned.put(id, entity.getUniqueId());
                continue;
            }
            String labelId = entity.getPersistentDataContainer().get(labelIdKey, PersistentDataType.STRING);
            if (labelId != null) {
                String id = labelId.toLowerCase();
                UUID known = labels.get(id);
                if (repository.get(id) == null || !(entity instanceof TextDisplay)
                        || known != null && !known.equals(entity.getUniqueId())) {
                    entity.remove();
                    removed++;
                } else labels.put(id, entity.getUniqueId());
            }
        }
        return removed;
    }

    public Set<String> loadedOrphanIds() {
        Set<String> result = new HashSet<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String id = taggedId(entity);
                if (id != null && repository.get(id) == null) result.add(id.toLowerCase());
            }
        }
        return result;
    }

    private String taggedId(Entity entity) {
        String id = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        return id != null ? id : entity.getPersistentDataContainer().get(labelIdKey, PersistentDataType.STRING);
    }

    private void forget(Entity entity, String id) {
        String normalized = id.toLowerCase();
        visibleViewers.remove(entity.getUniqueId());
        if (entity.getUniqueId().equals(spawned.get(normalized))) spawned.remove(normalized);
        if (entity.getUniqueId().equals(labels.get(normalized))) labels.remove(normalized);
    }

    public Optional<LivingEntity> entity(String id) {
        UUID uuid = spawned.get(id.toLowerCase());
        if (uuid == null) {
            return Optional.empty();
        }
        Entity entity = plugin.getServer().getEntity(uuid);
        if (entity instanceof LivingEntity living && living.isValid()) {
            return Optional.of(living);
        }
        spawned.remove(id.toLowerCase());
        return Optional.empty();
    }

    private Optional<TextDisplay> label(String id) {
        UUID uuid = labels.get(id.toLowerCase());
        if (uuid == null) return Optional.empty();
        Entity entity = plugin.getServer().getEntity(uuid);
        if (entity instanceof TextDisplay display && display.isValid()) return Optional.of(display);
        labels.remove(id.toLowerCase());
        return Optional.empty();
    }

    public NpcDefinition definition(Entity entity) {
        String id = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        return id == null ? null : repository.get(id);
    }

    public boolean isManaged(Entity entity) {
        return definition(entity) != null;
    }

    private void discoverExisting() {
        for (World world : plugin.getServer().getWorlds()) {
            discoverExisting(world);
        }
    }

    private void discoverExisting(World world) {
        reconcileEntities(world.getEntities());
    }

    private void updateNpcState() {
        for (NpcDefinition npc : repository.all()) {
            if (!npc.enabled()) continue;
            LivingEntity living = entity(npc.id()).orElse(null);
            if (living == null) continue;
            TextDisplay display = label(npc.id()).orElse(null);
            updateVisibility(npc, living, display);
            if (!npc.lookAtPlayers() || npc.lookRange() <= 0) {
                if (trackingPlayers.remove(npc.id())) resetRotation(npc, living);
                continue;
            }
            Player nearest = null;
            double nearestDistance = npc.lookRange() * npc.lookRange();
            for (Player player : living.getWorld().getPlayers()) {
                if (!player.isValid() || player.isDead()) {
                    continue;
                }
                double distance = player.getLocation().distanceSquared(living.getLocation());
                if (distance <= nearestDistance) {
                    nearestDistance = distance;
                    nearest = player;
                }
            }
            if (nearest != null) {
                trackingPlayers.add(npc.id());
                if (npc.lookMode() == LookMode.BODY) rotateBodyToward(living, nearest.getEyeLocation());
                else lookHeadToward(npc, living, nearest.getEyeLocation());
            } else if (trackingPlayers.remove(npc.id())) resetRotation(npc, living);
        }
    }

    public void resetRotation(NpcDefinition npc) {
        entity(npc.id()).ifPresent(living -> resetRotation(npc, living));
    }

    private void resetRotation(NpcDefinition npc, LivingEntity living) {
        float yaw = npc.location().yaw();
        float pitch = npc.location().pitch();
        living.setRotation(yaw, pitch);
        headLookController.setBodyYaw(living, yaw);
        Location direction = new Location(living.getWorld(), 0, 0, 0, yaw, pitch);
        headLookController.lookAt(living, living.getEyeLocation().add(direction.getDirection().multiply(8)));
        headLookController.setBodyYaw(living, yaw);
    }

    private void lookHeadToward(NpcDefinition npc, LivingEntity living, Location target) {
        float baseYaw = npc.location().yaw();
        headLookController.setBodyYaw(living, baseYaw);
        if (!headLookController.lookAt(living, target)) rotateBodyToward(living, target);
        else headLookController.setBodyYaw(living, baseYaw);
    }

    private void updateVisibility(NpcDefinition npc, LivingEntity living, TextDisplay display) {
        for (Player player : living.getWorld().getPlayers()) {
            double distanceSquared = player.getLocation().distanceSquared(living.getLocation());
            setVisible(player, living, npc.visibilityRange() > 0
                    && distanceSquared <= npc.visibilityRange() * npc.visibilityRange());
            if (display != null) {
                boolean hasText = !npc.name().isBlank() || !npc.description().isBlank();
                setVisible(player, display, hasText && npc.nameVisibilityRange() > 0
                        && distanceSquared <= npc.nameVisibilityRange() * npc.nameVisibilityRange());
            }
        }
    }

    private void setVisible(Player player, Entity entity, boolean visible) {
        UUID playerUuid = player.getUniqueId();
        if (visible) {
            Set<UUID> viewers = visibleViewers.computeIfAbsent(entity.getUniqueId(), ignored -> new HashSet<>());
            if (viewers.add(playerUuid)) player.showEntity(plugin, entity);
            return;
        }
        Set<UUID> viewers = visibleViewers.get(entity.getUniqueId());
        if (viewers != null && viewers.remove(playerUuid)) {
            player.hideEntity(plugin, entity);
            if (viewers.isEmpty()) visibleViewers.remove(entity.getUniqueId());
        }
    }

    public void forgetViewer(UUID playerUuid) {
        visibleViewers.values().forEach(viewers -> viewers.remove(playerUuid));
        visibleViewers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static void rotateBodyToward(LivingEntity living, Location target) {
        Location from = living.getEyeLocation();
        double dx = target.getX() - from.getX();
        double dy = target.getY() - from.getY();
        double dz = target.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        living.setRotation(yaw, pitch);
        // setRotation is the Spigot-compatible full-body fallback.
    }

    private static ItemStack itemOrAir(ItemStack item) {
        return item == null ? new ItemStack(org.bukkit.Material.AIR) : item;
    }
}
