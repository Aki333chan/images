package dev.addons.npc.platform;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

/** Best-effort bridge to the native head controller without introducing an NMS compile dependency. */
public final class HeadLookController {
    private final JavaPlugin plugin;
    private final Map<Class<?>, Strategy> strategies = new HashMap<>();
    private final Set<Class<?>> warned = new HashSet<>();

    public HeadLookController(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean lookAt(LivingEntity living, Location target) {
        Location from = living.getEyeLocation();
        double dx = target.getX() - from.getX();
        double dy = target.getY() - from.getY();
        double dz = target.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        try {
            Object handle = living.getClass().getMethod("getHandle").invoke(living);
            Strategy strategy = strategies.computeIfAbsent(handle.getClass(), this::discover);
            return strategy.apply(handle, target, yaw, pitch);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce(living.getClass(), "Head-only look is not supported for " + living.getType(), exception);
            return false;
        }
    }

    /** Uses Paper's public body-yaw API when present and the Mojang-mapped handle elsewhere. */
    public boolean setBodyYaw(LivingEntity living, float yaw) {
        try {
            Method api = findSingleFloat(living.getClass(), "setBodyYaw");
            if (api != null) {
                api.invoke(living, yaw);
                return true;
            }
            Object handle = living.getClass().getMethod("getHandle").invoke(living);
            Method nms = findSingleFloat(handle.getClass(), "setYBodyRot");
            if (nms != null) {
                nms.invoke(handle, yaw);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce(living.getClass(), "Body yaw control is not supported for " + living.getType(), exception);
        }
        return false;
    }

    private Strategy discover(Class<?> handleClass) {
        try {
            Method getLookControl = findNoArg(handleClass, "getLookControl");
            if (getLookControl == null) {
                for (Method method : handleClass.getMethods()) {
                    if (method.getParameterCount() == 0
                            && method.getReturnType().getSimpleName().toLowerCase().contains("lookcontrol")) {
                        getLookControl = method;
                        break;
                    }
                }
            }
            if (getLookControl != null) {
                Method finalGetter = getLookControl;
                Method setLookAt = findCoordinatesMethod(getLookControl.getReturnType());
                Method tick = findNoArg(getLookControl.getReturnType(), "tick");
                if (setLookAt != null) {
                    return (handle, target, yaw, pitch) -> {
                        Object control = finalGetter.invoke(handle);
                        setLookAt.invoke(control, target.getX(), target.getY(), target.getZ(), 360.0f, 360.0f);
                        if (tick != null) tick.invoke(control);
                        return true;
                    };
                }
            }

            Method setHeadYaw = findSingleFloat(handleClass, "setYHeadRot");
            Method setPitch = findSingleFloat(handleClass, "setXRot");
            if (setHeadYaw != null && setPitch != null) {
                return (handle, target, yaw, pitch) -> {
                    setHeadYaw.invoke(handle, yaw);
                    setPitch.invoke(handle, pitch);
                    return true;
                };
            }
        } catch (RuntimeException ignored) {
            // An unsupported server implementation is represented by the no-op strategy below.
        }
        return (handle, target, yaw, pitch) -> false;
    }

    private static Method findCoordinatesMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 5 && parameters[0] == double.class && parameters[1] == double.class
                    && parameters[2] == double.class && parameters[3] == float.class && parameters[4] == float.class) {
                return method;
            }
        }
        return null;
    }

    private static Method findSingleFloat(Class<?> type, String name) {
        try {
            return type.getMethod(name, float.class);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static Method findNoArg(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private void warnOnce(Class<?> type, String message, Exception exception) {
        if (warned.add(type)) plugin.getLogger().warning(message + ": " + exception.getMessage());
    }

    @FunctionalInterface
    private interface Strategy {
        boolean apply(Object handle, Location target, float yaw, float pitch) throws ReflectiveOperationException;
    }
}
