package dev.addons.npc.service;

import dev.addons.npc.model.GuildBonusType;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Soft bridge to AurumGuilds. It deliberately resolves the registered service
 * by API class name so AddonsNPC still starts when the optional plugin is absent.
 */
public final class AurumGuildsHook {
    private static final String API_NAME = "ovh.aurumgg.guilds.api.AurumGuildsApi";
    private static final String BONUS_TYPE_NAME = "ovh.aurumgg.guilds.api.BonusType";

    private final JavaPlugin plugin;
    private volatile WeakReference<Class<?>> cachedType = new WeakReference<>(null);
    private volatile WeakReference<Object> cachedProvider = new WeakReference<>(null);

    public AurumGuildsHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean available() { return resolve().isPresent(); }

    public Optional<Membership> membership(UUID playerUuid) {
        Optional<Resolved> resolved = resolve();
        if (resolved.isEmpty()) return Optional.empty();
        try {
            Object value = resolved.get().apiType().getMethod("membership", UUID.class)
                    .invoke(resolved.get().provider(), playerUuid);
            if (!(value instanceof Optional<?> optional) || optional.isEmpty()) return Optional.empty();
            Object membership = optional.get();
            Object rank = invoke(membership, "rank");
            return Optional.of(new Membership(
                    ((Number) invoke(membership, "guildId")).longValue(),
                    String.valueOf(invoke(membership, "guildName")),
                    String.valueOf(invoke(membership, "guildTag")),
                    ((Number) invoke(rank, "weight")).intValue(),
                    ((Enum<?>) rank).name()));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            log("Could not read AurumGuilds membership", exception);
            return Optional.empty();
        }
    }

    public List<ActiveBonus> bonuses(long guildId) {
        Optional<Resolved> resolved = resolve();
        if (resolved.isEmpty()) return List.of();
        try {
            Object value = resolved.get().apiType().getMethod("bonuses", long.class)
                    .invoke(resolved.get().provider(), guildId);
            if (!(value instanceof List<?> list)) return List.of();
            List<ActiveBonus> result = new ArrayList<>();
            for (Object bonus : list) {
                Object typeValue = invoke(bonus, "type");
                GuildBonusType type;
                try { type = GuildBonusType.valueOf(((Enum<?>) typeValue).name()); }
                catch (IllegalArgumentException ignored) { continue; }
                Object expiresValue = invoke(bonus, "expiresAt");
                result.add(new ActiveBonus(type,
                        ((Number) invoke(bonus, "magnitude")).doubleValue(),
                        expiresValue instanceof Instant instant ? instant : null,
                        String.valueOf(invoke(bonus, "grantedBy"))));
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            log("Could not read AurumGuilds bonuses", exception);
            return List.of();
        }
    }

    public CompletableFuture<GrantResult> grant(long guildId, GuildBonusType type, double magnitude,
                                                 Duration duration, String actor) {
        Optional<Resolved> resolved = resolve();
        if (resolved.isEmpty()) return CompletableFuture.completedFuture(GrantResult.fail("AurumGuilds API недоступен"));
        try {
            ClassLoader loader = resolved.get().apiType().getClassLoader();
            Class<?> bonusTypeClass = Class.forName(BONUS_TYPE_NAME, true, loader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object bonusType = Enum.valueOf((Class<? extends Enum>) bonusTypeClass.asSubclass(Enum.class), type.name());
            Method method = resolved.get().apiType().getMethod("grantBonus",
                    long.class, bonusTypeClass, double.class, Duration.class, String.class);
            Object rawFuture = method.invoke(resolved.get().provider(), guildId, bonusType, magnitude, duration, actor);
            if (!(rawFuture instanceof CompletableFuture<?> future)) {
                return CompletableFuture.completedFuture(GrantResult.fail("AurumGuilds вернул некорректный результат"));
            }
            return future.handle((value, error) -> {
                if (error != null) {
                    log("AurumGuilds rejected a trader bonus", unwrap(error));
                    return GrantResult.fail("AurumGuilds не смог сохранить бонус");
                }
                try {
                    return new GrantResult((Boolean) invoke(value, "ok"), String.valueOf(invoke(value, "message")));
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    log("Could not decode AurumGuilds result", exception);
                    return GrantResult.fail("AurumGuilds вернул некорректный результат");
                }
            });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            log("Could not invoke AurumGuilds bonus hook", exception);
            return CompletableFuture.completedFuture(GrantResult.fail("AurumGuilds API несовместим"));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<Resolved> resolve() {
        ServicesManager services = plugin.getServer().getServicesManager();
        Class<?> currentType = cachedType.get();
        Object currentProvider = cachedProvider.get();
        if (currentType != null && currentProvider != null) {
            Object provider = services.load((Class) currentType);
            if (provider == currentProvider) return Optional.of(new Resolved(currentType, currentProvider));
            cachedType.clear();
            cachedProvider.clear();
        }
        for (Class<?> service : services.getKnownServices()) {
            if (!service.getName().equals(API_NAME)) continue;
            Object provider = services.load((Class) service);
            if (provider != null) {
                Resolved resolved = new Resolved(service, provider);
                cachedType = new WeakReference<>(service);
                cachedProvider = new WeakReference<>(provider);
                return Optional.of(resolved);
            }
        }
        return Optional.empty();
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private void log(String message, Throwable error) {
        plugin.getLogger().log(Level.WARNING, message + ": " + error.getMessage(), error);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof InvocationTargetException) && current.getCause() != null) current = current.getCause();
        return current;
    }

    private record Resolved(Class<?> apiType, Object provider) {}

    public record Membership(long guildId, String guildName, String guildTag, int rankWeight, String rankName) {}

    public record ActiveBonus(GuildBonusType type, double magnitude, Instant expiresAt, String grantedBy) {
        public boolean permanent() { return expiresAt == null; }
        public Duration remaining(Instant now) {
            if (expiresAt == null) return null;
            Duration left = Duration.between(now, expiresAt);
            return left.isNegative() ? Duration.ZERO : left;
        }
    }

    public record GrantResult(boolean ok, String message) {
        public static GrantResult fail(String message) { return new GrantResult(false, message); }
    }
}
