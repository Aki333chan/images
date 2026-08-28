package ovh.aurumgg.auth.paper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.core.AuthConfig;
import ovh.aurumgg.auth.core.AuthService;

/**
 * Что можно делать до входа: почти ничего.
 *
 * ПРИОРИТЕТ LOWEST И ОТСУТСТВИЕ ignoreCancelled — намеренно. Запрет должен
 * срабатывать раньше всех остальных обработчиков: если чат невошедшего дойдёт
 * до плагина чата на NORMAL, тот успеет разослать сообщение, и отменять его
 * будет уже поздно. По той же причине событие не пропускается, даже если его
 * кто-то уже отменил, — нам важно не «кто первый», а «точно не пройдёт».
 *
 * Список запретов сознательно короткий: движение, чат, команды, взаимодействие
 * с миром и открытие инвентарей. Это то, чем невошедший может что-то сделать
 * или кому-то помешать. Городить полный «карантин» с блокировкой урона,
 * подбора предметов и прочего не стали — каждый лишний перехваченный
 * обработчик это ещё одно место, где авторизация может поссориться с чужим
 * плагином, а ровно от этого мы и уходим.
 */
final class AuthGuardListener implements Listener {

    /** Команды, доступные до входа. Всё остальное отклоняется. */
    private static final Set<String> ALLOWED = Set.of("login", "l", "register", "reg");

    private final AurumAuthPlugin plugin;
    private final AuthService service;
    private final AuthConfig config;
    /** Задачи «выкинуть, если не вошёл» — по одной на игрока. */
    private final Map<UUID, BukkitTask> timeouts = new ConcurrentHashMap<>();

    AuthGuardListener(AurumAuthPlugin plugin, AuthService service, AuthConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
    }

    private boolean blocked(Player player) {
        return !service.isAuthenticated(player.getUniqueId());
    }

    // --------------------------------------------------------- вход в мир

    /**
     * Приветствие и таймаут.
     *
     * Приоритет NORMAL, а не LOWEST: здесь ничего не запрещается, только
     * отправляется сообщение и заводится таймер, и лезть с этим вперёд других
     * плагинов незачем. Сообщением о входе занимается отдельный слушатель на
     * MONITOR — см. JoinMessageListener.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        AuthStatus status = service.status(uuid).orElse(AuthStatus.AWAITING_LOGIN);
        if (status.isAuthenticated()) {
            if (status == AuthStatus.AUTHENTICATED_BY_SESSION) {
                player.sendMessage(AurumAuthPlugin.prefixed("С возвращением, пароль не нужен"));
            }
            return;
        }

        player.sendMessage(status == AuthStatus.AWAITING_REGISTRATION
                ? AurumAuthPlugin.prefixed("Зарегистрируйтесь: /register <пароль> <пароль ещё раз>")
                : AurumAuthPlugin.prefixed("Войдите: /login <пароль>"));

        scheduleTimeout(player);
    }

    private void scheduleTimeout(Player player) {
        UUID uuid = player.getUniqueId();
        cancelTimeout(uuid);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            timeouts.remove(uuid);
            if (!player.isOnline() || service.isAuthenticated(uuid)) return;
            player.kick(Component.text("Вы не вошли за отведённое время"));
        }, AurumAuthPlugin.ticks(config.loginTimeout()));
        timeouts.put(uuid, task);
    }

    /** Снять таймаут — после успешного входа или выхода игрока. */
    void cancelTimeout(UUID uuid) {
        BukkitTask task = timeouts.remove(uuid);
        if (task != null) task.cancel();
    }

    /**
     * На выходе снимаем только свой таймаут.
     *
     * Состояние игрока в сервисе убирает JoinMessageListener — там же, где
     * решается судьба сообщения о выходе, и по причине, описанной в его
     * комментарии. Делать это в двух местах значило бы поставить результат в
     * зависимость от порядка регистрации слушателей.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancelTimeout(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ запреты

    /**
     * Движение.
     *
     * Отменяется только смена БЛОКА, а не любое событие: PlayerMoveEvent
     * приходит и на поворот головы, и запрет на него превращает экран входа в
     * зависшую картинку — игрок не понимает, жив ли клиент. Осмотреться можно,
     * уйти нельзя.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!blocked(event.getPlayer())) return;
        if (event.getTo() == null) return;
        boolean sameBlock = event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ();
        if (!sameBlock) event.setCancelled(true);
    }

    /**
     * Чат.
     *
     * Событие асинхронное, и это здесь только на пользу: отменить его можно
     * из любого потока, а до главного дело не доходит вовсе. Слушаем
     * AsyncChatEvent (Paper), а не устаревший AsyncPlayerChatEvent.
     *
     * Отдельная причина запрета, помимо очевидной: люди регулярно набирают
     * пароль в чат, промахнувшись мимо команды. Пропущенное сообщение
     * означало бы пароль в общем чате и в логах сервера.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        if (!blocked(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(AurumAuthPlugin.prefixed("Сначала войдите"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blocked(event.getPlayer())) return;

        // «/login пароль» → «login». Берём первое слово без слэша и без
        // возможного префикса плагина вида /aurumauth:login.
        String raw = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        String command = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        if (ALLOWED.contains(command)) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(AurumAuthPlugin.prefixed("Сначала войдите: /login <пароль>"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }
}
