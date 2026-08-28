package ovh.aurumgg.auth.paper;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import ovh.aurumgg.auth.core.AuthService;

/**
 * До входа игрок видит в подсказках только команды входа.
 *
 * <h2>Зачем</h2>
 *
 * Клиент Minecraft получает список команд сервера сразу после подключения и
 * показывает его при вводе «/». Невошедшему это только мешает: половина
 * экрана занята командами, которые всё равно не выполнятся (их отбивает
 * AuthGuardListener), а нужные две — /login и /register — теряются среди
 * полусотни чужих. Оставляем ровно то, чем сейчас можно воспользоваться.
 *
 * <h2>Два события, и оба нужны</h2>
 *
 * <ul>
 *   <li>{@link PlayerCommandSendEvent} — список верхнего уровня, тот самый,
 *       что приходит клиенту при входе. Убранное отсюда не появится в меню
 *       по «/» вообще;</li>
 *   <li>{@link AsyncTabCompleteEvent} — дополнение по Tab уже во время набора.
 *       Отдельное событие и отдельная дорога: список верхнего уровня клиент
 *       получил один раз, а Tab каждый раз спрашивает сервер заново.</li>
 * </ul>
 *
 * <h2>Почему это не замена запрету</h2>
 *
 * Скрытая команда остаётся выполнимой: клиент может отправить любую строку,
 * подсказки на это не влияют. Запрет живёт в AuthGuardListener и никуда не
 * девается — здесь только видимость, то есть удобство. Об этом честно
 * предупреждает и сам javadoc PlayerCommandSendEvent: «implementations are not
 * required to securely remove all traces of the command».
 *
 * <h2>После входа</h2>
 *
 * Список команд клиенту отправляется один раз, при подключении, — то есть в
 * момент, когда игрок ещё не вошёл. Поэтому после успешного входа его
 * приходится отправлять заново: {@code Player#updateCommands()}. Без этого
 * вошедший игрок остался бы с обрезанным меню до самого перезахода, и выглядело
 * бы это как «плагин сломал команды».
 */
final class CommandVisibilityListener implements Listener {

    private final AuthService service;
    /** Команды, которые до входа видно. Тот же список, что и разрешённый в AuthGuardListener. */
    private final Set<String> allowed;
    private final boolean enabled;

    CommandVisibilityListener(AuthService service, Set<String> allowed, boolean enabled) {
        this.service = service;
        this.allowed = allowed;
        this.enabled = enabled;
    }

    private boolean hideFrom(Player player) {
        return enabled && !service.isAuthenticated(player.getUniqueId());
    }

    /**
     * Список команд, отправляемый клиенту.
     *
     * Из коллекции события можно только удалять — добавление в неё по
     * документации не определено, и мы этим не пользуемся.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!hideFrom(event.getPlayer())) return;
        event.getCommands().removeIf(name -> !isAllowed(name));
    }

    /**
     * Дополнение по Tab.
     *
     * Событие асинхронное, и обращаться к игроку отсюда нельзя — мы этого и не
     * делаем: только читаем состояние (оно лежит в потокобезопасной карте
     * сервиса) и правим список строк.
     *
     * setHandled(true) говорит серверу не спрашивать дополнения дальше —
     * иначе пустой список тут же заполнили бы обработчики самих команд.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player)) return;
        if (!event.isCommand()) return;
        if (!hideFrom(player)) return;

        // Имя команды клиент дополняет сам: начиная с 1.13 сервер шлёт ему
        // дерево команд целиком (это и есть PlayerCommandSendEvent), и
        // подсказки по «/lo» клиент строит локально, не спрашивая сервер.
        // Сюда доходят запросы на дополнение АРГУМЕНТОВ — их и разбираем.
        String buffer = event.getBuffer();
        String head = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        int space = head.indexOf(' ');
        String name = space < 0 ? head : head.substring(0, space);

        // Для чужой команды не подсказываем ничего: ни ников, ни названий
        // предметов, ни путей — невошедшему всё это ни к чему, а список
        // онлайна он получил бы, ещё не назвав пароля.
        //
        // Для своих команд событие не трогаем вовсе: аргументы у них — пароль
        // или код, дополнять там нечего, и лезть в чужую работу незачем.
        if (!isAllowed(name)) {
            event.setCompletions(List.of());
            event.setHandled(true);
        }
    }

    /**
     * Своя ли это команда.
     *
     * Имя может прийти с префиксом плагина — «aurumauth:login»: клиенту
     * отправляются обе формы. Сравниваем по части после двоеточия, иначе
     * префиксная форма наших же команд оказалась бы скрытой.
     */
    private boolean isAllowed(String name) {
        String bare = name.toLowerCase(Locale.ROOT);
        int colon = bare.indexOf(':');
        return allowed.contains(colon < 0 ? bare : bare.substring(colon + 1));
    }
}
