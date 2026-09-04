package dev.addons.npc.platform;

import dev.addons.npc.service.MessageService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.ChatColor;
import org.bukkit.entity.Mannequin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.plugin.java.JavaPlugin;

/** Bridges the intentionally different Paper and Spigot 26.2 mannequin APIs. */
public final class MannequinAdapter {
    private final JavaPlugin plugin;

    public MannequinAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setDescription(Mannequin mannequin, String description) {
        try {
            Method spigotMethod = mannequin.getClass().getMethod("setDescription", String.class);
            spigotMethod.invoke(mannequin, emptyToNull(MessageService.colorize(description)));
            return;
        } catch (NoSuchMethodException ignored) {
            // Paper uses an Adventure component in 26.2.
        } catch (ReflectiveOperationException exception) {
            warn("Could not set Spigot mannequin description", exception);
            return;
        }

        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Method setDescription = mannequin.getClass().getMethod("setDescription", componentClass);
            Object component = null;
            if (description != null && !description.isBlank()) {
                String plain = ChatColor.stripColor(MessageService.colorize(description));
                component = componentClass.getMethod("text", String.class).invoke(null, plain);
            }
            setDescription.invoke(mannequin, component);
        } catch (ReflectiveOperationException exception) {
            warn("Could not set Paper mannequin description", exception);
        }
    }

    public void setProfile(Mannequin mannequin, PlayerProfile profile) {
        try {
            Method spigotMethod = mannequin.getClass().getMethod("setPlayerProfile", PlayerProfile.class);
            spigotMethod.invoke(mannequin, profile);
            return;
        } catch (NoSuchMethodException ignored) {
            // Paper uses ResolvableProfile in 26.2.
        } catch (ReflectiveOperationException exception) {
            warn("Could not set Spigot mannequin profile", unwrap(exception));
            return;
        }

        try {
            Class<?> resolvableProfileClass = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            Object resolvable;
            if (profile == null) {
                resolvable = Mannequin.class.getMethod("defaultProfile").invoke(null);
            } else {
                // Paper's factory accepts com.destroystokyo.paper.profile.PlayerProfile. The
                // object returned by Bukkit implements that interface, but looking the method
                // up with its Bukkit superinterface does not match reflective signatures.
                Method factory = findCompatibleMethod(
                        resolvableProfileClass, "resolvableProfile", profile, true);
                resolvable = factory.invoke(null, profile);
            }
            Method setter = findCompatibleMethod(mannequin.getClass(), "setProfile", resolvable, false);
            setter.invoke(mannequin, resolvable);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn("Could not set Paper mannequin profile", unwrap(exception));
        }
    }

    static Method findCompatibleMethod(Class<?> owner, String name, Object argument, boolean requireStatic)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            if (requireStatic != Modifier.isStatic(method.getModifiers())) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (argument == null || parameter.isInstance(argument)) return method;
        }
        String argumentType = argument == null ? "null" : argument.getClass().getName();
        throw new NoSuchMethodException(owner.getName() + "." + name + "(compatible with " + argumentType + ")");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Throwable unwrap(Exception exception) {
        return exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : exception;
    }

    private void warn(String message, Throwable throwable) {
        plugin.getLogger().warning(message + ": " + throwable.getMessage());
    }
}
