package dev.addons.npc.service;

import dev.addons.npc.model.SkinSpec;
import dev.addons.npc.platform.MannequinAdapter;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkinService {
    private final JavaPlugin plugin;
    private final MannequinAdapter adapter;

    public SkinService(JavaPlugin plugin, MannequinAdapter adapter) {
        this.plugin = plugin;
        this.adapter = adapter;
    }

    public void apply(Mannequin mannequin, SkinSpec skin) {
        if (skin.type() == SkinSpec.Type.NONE || skin.value().isBlank()) {
            adapter.setProfile(mannequin, null);
            return;
        }
        if (skin.type() == SkinSpec.Type.URL) {
            applyUrl(mannequin, skin.value());
            return;
        }
        applyPlayer(mannequin, skin.value());
    }

    private void applyPlayer(Mannequin mannequin, String playerName) {
        if (!playerName.matches("[A-Za-z0-9_]{1,16}")) {
            plugin.getLogger().warning("Invalid skin player name: " + playerName);
            return;
        }
        PlayerProfile initial = Bukkit.createPlayerProfile(playerName);
        CompletableFuture<PlayerProfile> update = initial.update();
        update.whenComplete((profile, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                plugin.getLogger().warning("Could not resolve skin for " + playerName + ": " + throwable.getMessage());
            } else if (mannequin.isValid()) {
                adapter.setProfile(mannequin, profile);
            }
        }));
    }

    private void applyUrl(Mannequin mannequin, String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Skin URL must use http or https");
            }
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "npc_skin");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(uri.toURL());
            profile.setTextures(textures);
            adapter.setProfile(mannequin, profile);
        } catch (RuntimeException | java.net.MalformedURLException exception) {
            plugin.getLogger().warning("Invalid skin URL '" + url + "': " + exception.getMessage());
        }
    }
}

