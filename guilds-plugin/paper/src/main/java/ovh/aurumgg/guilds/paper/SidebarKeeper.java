package ovh.aurumgg.guilds.paper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Сайдбар, который не воюет за слот с чужими плагинами.
 *
 * <h2>Проблема</h2>
 *
 * Слот SIDEBAR на сервере один. На этом же сервере стоит GladiatorArena, и по
 * её исходникам (метод {@code updateScoreboards}) видно, что во время боя она
 * КАЖДЫЕ ДВА ТИКА делает игроку {@code player.setScoreboard(...)} со своей
 * доской ставок. Если бы мы тоже писали в слот безусловно, игрок получил бы
 * мигание с частотой десять раз в секунду и не увидел бы ни того, ни другого.
 *
 * <h2>Почему не событие</h2>
 *
 * В исходниках арены нет ни одного {@code callEvent}, ни одного собственного
 * класса события и ни одного публичного метода, по которому можно было бы
 * узнать, что игрок вошёл в бой или вышел из него. Проверено поиском по всем
 * четырём её файлам. Значит, подписаться не на что, и остаётся смотреть на сам
 * слот — что здесь и делается.
 *
 * <h2>Как это работает</h2>
 *
 * Перед каждым обновлением проверяется, чья сейчас доска у игрока:
 *
 * <ul>
 *   <li><b>наша</b> — обновляем строки;</li>
 *   <li><b>чужая, но слот SIDEBAR пуст</b> — занимаем: ставим свою доску;</li>
 *   <li><b>чужая и слот занят</b> — не трогаем ничего вообще. Проверка идёт
 *       каждый цикл, поэтому как только слот освободится, сайдбар вернётся
 *       сам, без чьего-либо участия.</li>
 * </ul>
 *
 * <h2>Приятное следствие</h2>
 *
 * Арена перед заменой запоминает ТЕКУЩУЮ доску игрока и возвращает её, когда
 * бой закончен. Если к этому моменту у игрока стояла наша, она же и вернётся —
 * то есть две системы расходятся мирно, без единой строчки кода про арену с
 * нашей стороны. А если игрок зашёл на сервер уже внутри арены и наша доска
 * ему не досталась, мы просто увидим свободный слот после боя и займём его.
 */
final class SidebarKeeper {

    /** Имя нашей цели в scoreboard. По нему же мы узнаём свою доску. */
    static final String OBJECTIVE = "aurum_guilds";

    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();

    /** Доска на каждого игрока: у всех разный состав пати и разные цифры. */
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();

    private final String title;

    SidebarKeeper(String title) {
        this.title = title;
    }

    /**
     * Показать игроку строки. Пустой список — убрать сайдбар.
     *
     * @return true, если строки показаны; false — слот занят чужим плагином
     */
    boolean show(Player player, List<String> lines) {
        if (lines.isEmpty()) {
            hide(player);
            return true;
        }

        Scoreboard board = boardFor(player);
        Scoreboard current = player.getScoreboard();

        if (current != board) {
            Objective occupied = current.getObjective(DisplaySlot.SIDEBAR);
            if (occupied != null && !OBJECTIVE.equals(occupied.getName())) {
                // Слот у кого-то другого. Не спорим: перезапись превратила бы
                // оба сайдбара в мигание. Вернёмся, когда освободится — проверка
                // идёт каждый цикл, так что ждать ничего не нужно.
                return false;
            }
            player.setScoreboard(board);
        }

        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, COLORS.deserialize(title));
        }
        objective.displayName(COLORS.deserialize(title));
        if (objective.getDisplaySlot() != DisplaySlot.SIDEBAR) {
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // Убираем строки, которых в новом наборе нет. Без этого сайдбар растёт:
        // старые записи никуда не деваются сами, и вышедший из пати игрок
        // остался бы в списке навсегда.
        Set<String> wanted = new HashSet<>(lines);
        for (String entry : new HashSet<>(board.getEntries())) {
            if (!wanted.contains(entry)) board.resetScores(entry);
        }

        // Счёт задаёт порядок: больше — выше. Считаем сверху вниз, чтобы
        // порядок строк совпал с тем, в котором их собрали.
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
        return true;
    }

    /** Убрать наш сайдбар, не трогая чужой. */
    void hide(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) return;
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective != null) objective.unregister();
        // На главную доску переводим только если игрок сейчас на нашей: иначе
        // мы бы сняли чужой сайдбар, который нам не принадлежит.
        if (player.getScoreboard() == board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** Забыть игрока — он вышел с сервера. */
    void forget(UUID player) {
        boards.remove(player);
    }

    private Scoreboard boardFor(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(),
                // Своя доска, а не главная: на главной цель была бы общей для
                // всего сервера, и все увидели бы состав чужой пати.
                key -> Bukkit.getScoreboardManager().getNewScoreboard());
    }
}
