package ovh.aurumgg.companion.paper;

import java.io.IOException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.companion.core.CompanionConfig;
import ovh.aurumgg.companion.core.http.CompanionHttpServer;
import ovh.aurumgg.companion.core.ticket.TicketClient;
import ovh.aurumgg.companion.core.ticket.TicketCooldown;

/**
 * Точка входа companion-плагина Aurum Panel.
 *
 * Работает в обе стороны:
 *   панель → плагин  — локальный HTTP-сервер (игроки, инвентарь);
 *   плагин → панель  — команда /ticket отправляет обращение игрока.
 */
public final class AurumCompanionPlugin extends JavaPlugin implements Listener {

    private CompanionHttpServer httpServer;
    private TicketCooldown cooldown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CompanionConfig config = readConfig();

        cooldown = new TicketCooldown(config.ticketCooldownSeconds());
        getServer().getPluginManager().registerEvents(this, this);

        startHttpServer(config);
        registerTicketCommand(config);
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop();
            getLogger().info("HTTP-сервер companion остановлен");
        }
    }

    private CompanionConfig readConfig() {
        FileConfiguration cfg = getConfig();
        return new CompanionConfig(
                cfg.getString("http.bind", "0.0.0.0"),
                cfg.getInt("http.port", 8085),
                cfg.getString("token", CompanionConfig.PLACEHOLDER_TOKEN),
                cfg.getString("panel.base-url", ""),
                cfg.getString("panel.server-id", ""),
                cfg.getInt("tickets.cooldown-seconds", 10));
    }

    private void startHttpServer(CompanionConfig config) {
        String problem = config.httpConfigProblem();
        if (problem != null) {
            // Лучше не подниматься совсем, чем слушать порт с предсказуемым токеном.
            getLogger().severe("HTTP-сервер не запущен: " + problem);
            return;
        }
        httpServer = new CompanionHttpServer(config, new BukkitGameBridge(this), getLogger()::warning);
        try {
            httpServer.start();
            getLogger().info("HTTP-сервер companion слушает "
                    + config.bindAddress() + ":" + config.port()
                    + " — порт не должен быть доступен из интернета");
        } catch (IOException e) {
            httpServer = null;
            getLogger().severe("Не удалось занять порт " + config.port() + ": " + e.getMessage()
                    + ". Проверьте, что порт добавлен как secondary allocation в Pterodactyl.");
        }
    }

    private void registerTicketCommand(CompanionConfig config) {
        String problem = config.ticketConfigProblem();
        if (problem != null) {
            getLogger().warning("Команда /ticket отключена: " + problem);
        }
        var command = getCommand("ticket");
        if (command == null) {
            getLogger().severe("Команда ticket не объявлена в plugin.yml");
            return;
        }
        command.setExecutor(new TicketCommand(this, new TicketClient(config), cooldown, problem == null));
    }

    /** Чтобы карта кулдаунов не росла бесконечно на долгоживущем сервере. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (cooldown != null) cooldown.forget(event.getPlayer().getUniqueId());
    }
}
