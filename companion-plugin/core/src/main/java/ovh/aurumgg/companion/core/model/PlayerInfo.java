package ovh.aurumgg.companion.core.model;

import java.util.UUID;

/** Снимок состояния игрока онлайн. Собирается в основном потоке сервера. */
public record PlayerInfo(
        UUID uuid,
        String name,
        double health,
        double maxHealth,
        String world,
        double x,
        double y,
        double z,
        int ping) {}
