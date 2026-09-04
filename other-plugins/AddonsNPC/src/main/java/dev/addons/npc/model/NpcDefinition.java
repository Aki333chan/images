package dev.addons.npc.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;

public final class NpcDefinition {
    private final String id;
    private StoredLocation location;
    private String name;
    private String description = "";
    private boolean enabled = true;
    private EntityType entityType = EntityType.MANNEQUIN;
    private SkinSpec skin = SkinSpec.none();
    private ClickMode clickMode = ClickMode.RIGHT;
    private DialogueMode dialogueMode = DialogueMode.SEQUENTIAL;
    private final List<String> messages = new ArrayList<>();
    private final List<ActionDefinition> actions = new ArrayList<>();
    private double cooldownSeconds = 1.0;
    private String permission = "";
    private boolean lookAtPlayers = true;
    private double lookRange = 8.0;
    private LookMode lookMode = LookMode.HEAD;
    private double visibilityRange = 48.0;
    private double nameVisibilityRange = 24.0;
    private Pose pose = Pose.STANDING;
    private ItemStack rightHand;
    private ItemStack leftHand;

    public NpcDefinition(String id, StoredLocation location, String name) {
        this.id = normalizeId(id);
        this.location = location;
        this.name = name;
    }

    public static String normalizeId(String id) {
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("ID must match [a-z0-9_-]{1,32}");
        }
        return normalized;
    }

    public String id() { return id; }
    public StoredLocation location() { return location; }
    public void location(StoredLocation location) { this.location = location; }
    public String name() { return name; }
    public void name(String name) { this.name = name; }
    public String description() { return description; }
    public void description(String description) { this.description = description; }
    public boolean enabled() { return enabled; }
    public void enabled(boolean enabled) { this.enabled = enabled; }
    public EntityType entityType() { return entityType; }
    public void entityType(EntityType entityType) {
        if (!isSupportedEntityType(entityType)) {
            throw new IllegalArgumentException("Entity type is not a spawnable living NPC: " + entityType);
        }
        this.entityType = entityType;
    }
    public SkinSpec skin() { return skin; }
    public void skin(SkinSpec skin) { this.skin = skin; }
    public ClickMode clickMode() { return clickMode; }
    public void clickMode(ClickMode clickMode) { this.clickMode = clickMode; }
    public DialogueMode dialogueMode() { return dialogueMode; }
    public void dialogueMode(DialogueMode dialogueMode) { this.dialogueMode = dialogueMode; }
    public List<String> messages() { return messages; }
    public List<ActionDefinition> actions() { return actions; }
    public double cooldownSeconds() { return cooldownSeconds; }
    public void cooldownSeconds(double cooldownSeconds) { this.cooldownSeconds = Math.max(0, cooldownSeconds); }
    public String permission() { return permission; }
    public void permission(String permission) { this.permission = permission == null ? "" : permission; }
    public boolean lookAtPlayers() { return lookAtPlayers; }
    public void lookAtPlayers(boolean lookAtPlayers) { this.lookAtPlayers = lookAtPlayers; }
    public double lookRange() { return lookRange; }
    public void lookRange(double lookRange) { this.lookRange = Math.max(0, lookRange); }
    public LookMode lookMode() { return lookMode; }
    public void lookMode(LookMode lookMode) { this.lookMode = lookMode; }
    public double visibilityRange() { return visibilityRange; }
    public void visibilityRange(double visibilityRange) { this.visibilityRange = range(visibilityRange); }
    public double nameVisibilityRange() { return nameVisibilityRange; }
    public void nameVisibilityRange(double nameVisibilityRange) { this.nameVisibilityRange = range(nameVisibilityRange); }
    public Pose pose() { return pose; }
    public void pose(Pose pose) { this.pose = pose; }
    public ItemStack rightHand() { return copy(rightHand); }
    public void rightHand(ItemStack item) { this.rightHand = copy(item); }
    public ItemStack leftHand() { return copy(leftHand); }
    public void leftHand(ItemStack item) { this.leftHand = copy(item); }

    public static boolean isSupportedEntityType(EntityType type) {
        Class<?> entityClass = type == null ? null : type.getEntityClass();
        return type != EntityType.PLAYER
                && type.isSpawnable() && entityClass != null && LivingEntity.class.isAssignableFrom(entityClass);
    }

    private static double range(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Visibility range must be finite.");
        return Math.max(0, Math.min(512, value));
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
