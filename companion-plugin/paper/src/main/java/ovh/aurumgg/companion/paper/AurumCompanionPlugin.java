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
 *
 * Отдельно стоит /webtoken: он выдаёт игроку одноразовый код для входа в
 * панель и потому обязан знать, вошёл ли игрок в игру по-настоящему. Ответ на
 * это даёт плагин авторизации через свой API — см. AuthIntegration.
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
        registerWebTokenCommand();
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
        TicketClient client = new TicketClient(config);
        command.setExecutor(new TicketCommand(this, client, cooldown, problem == null));
        if (problem == null) checkPanelReachable(client);
    }

    /**
     * Проверка связи с панелью на старте.
     *
     * Без неё о разорванном канале узнают самым неудобным способом: игрок
     * пишет /ticket, получает «попробуй позже», и никто не связывает это с
     * тем, что панель слушает не тот адрес. Проверка идёт асинхронно — в
     * onEnable нельзя ходить в сеть, сервер ждёт возврата из метода.
     */
    private void checkPanelReachable(TicketClient client) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String problem = client.checkPanel();
            if (problem == null) {
                getLogger().info("Связь с панелью есть — /ticket будет работать");
                return;
            }
            getLogger().warning("Панель не отвечает: " + problem);
            getLogger().warning("Пока это так, /ticket будет отвечать игрокам «попробуй позже». "
                    + "Проверьте panel.base-url в config.yml и то, что панель слушает адрес туннеля "
                    + "(на VDS: ss -tulpn | grep 3001 — должен быть 10.0.0.1, а не 127.0.0.1).");
        });
    }

    /**
     * /webtoken работает только вместе с HTTP-сервером: код, который панели
     * негде обменять, бесполезен и только вводит игрока в заблуждение.
     */
    private void registerWebTokenCommand() {
        var command = getCommand("webtoken");
        if (command == null) {
            getLogger().severe("Команда webtoken не объявлена в plugin.yml");
            return;
        }
        if (httpServer == null) {
            getLogger().warning("Команда /webtoken отключена: HTTP-сервер не запущен");
            return;
        }
        command.setExecutor(new WebTokenCommand(httpServer.webTokens()));

        if (!AuthIntegration.installed()) {
            // Не ошибка: companion работает и без плагина авторизации. Но об
            // этом стоит сказать вслух — на сервере без авторизации /webtoken
            // выдаст код любому подключившемуся под этим ником.
            getLogger().warning("Плагин AurumAuth не найден: /webtoken не сможет проверить, "
                    + "что игрок действительно вошёл");
        }
    }

    /** Чтобы карта кулдаунов не росла бесконечно на долгоживущем сервере. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (cooldown != null) cooldown.forget(event.getPlayer().getUniqueId());
    }
}
