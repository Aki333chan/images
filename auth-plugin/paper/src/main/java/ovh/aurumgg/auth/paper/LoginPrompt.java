package ovh.aurumgg.auth.paper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.MessageSettings;
import ovh.aurumgg.auth.core.PromptSettings;

/**
 * Просьба войти — там, откуда её нечем вытеснить.
 *
 * <h2>Почему не хватило строчки в чате</h2>
 *
 * EssentialsX показывает свой MOTD сразу после входа, и показывает его прямой
 * отправкой текста игроку, а не событием (motdFlow → TextPager.showPage в его
 * исходниках). События «игроку пришёл текст» в Bukkit нет, перехватить или
 * отложить чужой MOTD нечем — и MOTD в несколько строк уносит нашу подсказку
 * вверх ещё до того, как человек успеет её прочитать.
 *
 * Поэтому подсказка живёт в трёх местах сразу:
 *
 * <ul>
 *   <li><b>title на весь экран</b> — главный канал. Чат его не двигает вообще
 *       никак, и не заметить надпись поперёк экрана нельзя;</li>
 *   <li><b>строка над горячей панелью</b> — постоянно на виду и не мешает
 *       читать чат;</li>
 *   <li><b>строка в чате</b> — на случай, если игрок отключил title в клиенте
 *       или уже начал что-то печатать. Повторяется реже остальных, чтобы не
 *       превратиться в спам.</li>
 * </ul>
 *
 * Всё это повторяется, пока игрок не вошёл: одного показа мало, если человек
 * в этот момент отвернулся или загружал ресурспак.
 *
 * <h2>Что тут НЕ делается</h2>
 *
 * Не делается попытка заглушить чужой MOTD. У EssentialsX для этого есть своя
 * настройка motd-delay (в миллисекундах; отрицательное значение выключает MOTD
 * совсем), и решать за администратора, что показывать на его сервере, не наше
 * дело. Мы отвечаем только за то, чтобы нашу подсказку было видно при любом
 * чужом MOTD.
 */
final class LoginPrompt {

    /** Цвета кодами вида &amp;a — как в конфигах EssentialsX и почти всех остальных. */
    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();

    private final AurumAuthPlugin plugin;
    private final AuthService service;
    /** Тексты берутся из ссылки: /auth reload меняет их на живом сервере. */
    private volatile PromptSettings settings;
    /** Повторяющиеся задачи — по одной на игрока. */
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();
    /** Когда истекает время на вход — для плейсхолдера {seconds}. */
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();

    LoginPrompt(AurumAuthPlugin plugin, AuthService service, PromptSettings settings) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
    }

    void updateSettings(PromptSettings updated) {
        this.settings = updated;
    }

    /**
     * Начать показывать подсказку и повторять её до входа.
     *
     * @param timeout сколько остаётся до кика — для обратного отсчёта
     */
    void start(Player player, Duration timeout) {
        UUID uuid = player.getUniqueId();
        stop(uuid);
        deadlines.put(uuid, System.currentTimeMillis() + timeout.toMillis());
        show(player, true);

        PromptSettings current = settings;
        if (current.repeat().isZero()) return;

        long period = AurumAuthPlugin.ticks(current.repeat());
        long chatEvery = current.chatReminder().isZero()
                ? Long.MAX_VALUE
                : Math.max(1L, current.chatReminder().toMillis() / Math.max(1L, current.repeat().toMillis()));

        // Счётчик повторов: строка в чат уходит не на каждом, иначе напоминание
        // превратится в стену текста, из-за которой не видно ни MOTD, ни
        // собственного набора команды.
        long[] ticksPassed = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || service.isAuthenticated(uuid)) {
                stop(uuid);
                return;
            }
            ticksPassed[0]++;
            show(player, ticksPassed[0] % chatEvery == 0);
        }, period, period);
        tasks.put(uuid, task);
    }

    /**
     * Показать подсказку заново — состояние игрока изменилось.
     *
     * Строка в чат при этом НЕ отправляется: смену ступени игрок и так узнаёт
     * из ответа команды («введите код из приложения»), и дублировать его
     * незачем. Обратный отсчёт продолжается прежний — таймаут входа не
     * продлевается тем, что человек дошёл до второго шага.
     */
    void refresh(Player player) {
        if (!player.isOnline() || service.isAuthenticated(player.getUniqueId())) return;
        show(player, false);
    }

    /** Убрать подсказку: игрок вошёл или вышел. */
    void stop(UUID uuid) {
        BukkitTask task = tasks.remove(uuid);
        if (task != null) task.cancel();
        deadlines.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        // Title гаснет сам через stay, но ждать этого нельзя: вошедший игрок
        // остался бы с надписью «ВОЙДИТЕ» поперёк экрана ещё несколько секунд.
        player.clearTitle();
        // Пустая строка над панелью — единственный способ её убрать: команды
        // «спрятать action bar» в API нет.
        player.sendActionBar(Component.empty());
    }

    private void show(Player player, boolean withChat) {
        PromptSettings current = settings;
        AuthStatus status = service.status(player.getUniqueId()).orElse(AuthStatus.AWAITING_LOGIN);
        PromptSettings.Stage stage = PromptSettings.Stage.of(status);
        if (stage == null) return;

        PromptSettings.Prompt prompt = current.prompts().get(stage);
        if (prompt == null) return;
        Map<String, String> values = placeholders(player);

        if (current.titleEnabled() && prompt.hasTitle()) {
            player.showTitle(Title.title(
                    render(prompt.title(), values),
                    render(prompt.subtitle(), values),
                    Title.Times.times(current.fadeIn(), current.stay(), current.fadeOut())));
        }
        if (current.actionBarEnabled() && prompt.hasActionBar()) {
            player.sendActionBar(render(prompt.actionBar(), values));
        }
        if (withChat && current.chatEnabled() && prompt.hasChat()) {
            player.sendMessage(render(prompt.chat(), values));
        }
    }

    private static Component render(String template, Map<String, String> values) {
        return COLORS.deserialize(MessageSettings.apply(template, values));
    }

    private Map<String, String> placeholders(Player player) {
        long left = deadlines.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
        return Map.of(
                "player", player.getName(),
                // Ноль вместо отрицательного: кик уже назначен на этот момент,
                // и «-3 с» на экране выглядело бы поломкой.
                "seconds", String.valueOf(Math.max(0L, (left + 999L) / 1000L)),
                "online", String.valueOf(Bukkit.getOnlinePlayers().size()),
                "max", String.valueOf(Bukkit.getMaxPlayers()));
    }
}
