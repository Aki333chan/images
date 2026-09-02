package ovh.aurumgg.guilds.paper;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import ovh.aurumgg.guilds.core.HudLines;

/**
 * Сайдбар, который не воюет за слот с чужими плагинами.
 *
 * <h2>Как выводится текст: командами, а не записями</h2>
 *
 * Первая версия писала строку прямо в запись scoreboard:
 * {@code objective.getScore(строка).setScore(n)}. Так делать нельзя, и на
 * живом сервере это выглядело как «&amp;7Пати &amp;8(&amp;f1&amp;8/&amp;f4&amp;8)» —
 * коды цвета показывались буквами. Причина в том, что запись — это не текст
 * для показа, а ИМЯ участника таблицы, вроде ника игрока. Форматирование в ней
 * не разбирается вообще.
 *
 * Правильный способ, он же единственный, дающий полноценные компоненты
 * Adventure: запись делается невидимым ключом (одиночный символ §-кода,
 * который ничего не рисует), а сам текст кладётся в ПРЕФИКС КОМАНДЫ, куда
 * эта запись входит — {@link Team#prefix(Component)}.
 *
 * Побочно это чинит и вторую беду. Раньше строки приходилось искусственно
 * разводить хвостами «&amp;r», потому что две одинаковые записи молча
 * схлопывались в одну и сайдбар терял строчку. Теперь ключи фиксированы по
 * номеру строки и уникальны по построению, а совпадение видимого текста
 * никого не волнует — разделителей может быть сколько угодно.
 *
 * <h2>Красные цифры справа</h2>
 *
 * Это счёт записи, и он показывается клиентом по умолчанию. Убирается
 * {@link NumberFormat#blank()}. Сам счёт при этом по-прежнему нужен — им
 * задаётся порядок строк, — просто клиенту его рисовать не надо.
 *
 * <h2>Где сайдбар находится на экране</h2>
 *
 * Всегда у правого края, по вертикали — по центру. Это рисует КЛИЕНТ, и
 * сервер на это влиять не может никак: ни через API, ни пакетами. Сдвинуть
 * его выше может только ресурспак или клиентский мод.
 *
 * <h2>Проблема слота</h2>
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

    /**
     * Сколько строк вмещает сайдбар — столько же ключей ниже.
     *
     * Число берётся из HudLines, а не пишется своё: там строки обрезаются под
     * этот же предел, и разъехавшиеся константы означали бы либо потерянные
     * строки, либо неиспользуемые ключи.
     */
    static final int MAX_LINES = HudLines.MAX_LINES;

    /**
     * Невидимые ключи строк: §-код и больше ничего.
     *
     * Клиент такой код съедает как управляющий и не рисует ни пикселя, а для
     * scoreboard это вполне себе разные записи. Именно они и делают строки
     * независимыми от своего текста.
     */
    private static final String[] KEYS = new String[MAX_LINES];

    static {
        // \u00A7 — тот самый символ секции, которым в Minecraft начинается код
        // форматирования. Escape-последовательностью, а не литералом: символ
        // невидим в большинстве редакторов, и в патче его легко потерять.
        String palette = "0123456789abcdef";
        for (int i = 0; i < MAX_LINES; i++) {
            KEYS[i] = "\u00A7" + palette.charAt(i);
        }
    }

    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();

    /** Доска на каждого игрока: у всех разный состав пати и разные цифры. */
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();

    /**
     * Что этому игроку уже показано.
     *
     * <h2>Зачем помнить</h2>
     *
     * Задача HUD зовёт {@link #show} раз в секунду на КАЖДОГО игрока, а строки
     * между двумя вызовами обычно те же самые: здоровье не меняется каждую
     * секунду, состав пати тем более. Без этой памяти каждый вызов разбирал бы
     * пятнадцать строк из «&amp;»-кодов в Component и слал пятнадцать пакетов
     * обновления команд, плюс пакеты счёта и заголовка. На сотне игроков это
     * тысячи разборов и тысячи пакетов в секунду — за неизменившуюся картинку.
     *
     * Теперь сравниваем со строками прошлого раза и трогаем только то, что
     * действительно поменялось. Видимый результат тот же — меняется лишь
     * количество работы.
     */
    private final Map<UUID, List<String>> shownLines = new ConcurrentHashMap<>();

    /** Не final: /guild admin reload меняет заголовок. */
    private volatile String title;

    SidebarKeeper(String title) {
        this.title = title;
    }

    void title(String title) {
        this.title = title;
        // Заголовок сменился — показанное больше не совпадает с тем, что
        // должно быть, и следующий проход обязан всё перерисовать.
        shownLines.clear();
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

        UUID id = player.getUniqueId();
        Scoreboard board = boardFor(player);
        Scoreboard current = player.getScoreboard();

        // Доску могли подменить снаружи — той же ареной. Тогда всё, что мы
        // помним о показанном, относится к чужой доске, и верить этому нельзя.
        boolean fresh = false;
        if (current != board) {
            Objective occupied = current.getObjective(DisplaySlot.SIDEBAR);
            if (occupied != null && !OBJECTIVE.equals(occupied.getName())) {
                // Слот у кого-то другого. Не спорим: перезапись превратила бы
                // оба сайдбара в мигание. Вернёмся, когда освободится — проверка
                // идёт каждый цикл, так что ждать ничего не нужно.
                shownLines.remove(id);
                return false;
            }
            player.setScoreboard(board);
            shownLines.remove(id);
            fresh = true;
        }

        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, COLORS.deserialize(title));
            // Цель создана заново — прошлые строки живут только в нашей памяти,
            // а у клиента их нет.
            shownLines.remove(id);
            fresh = true;
        }

        List<String> previous = shownLines.get(id);
        int shown = Math.min(lines.size(), MAX_LINES);

        // Ничего не изменилось — и делать нечего. Это обычный случай: строки
        // между двумя тактами задачи совпадают почти всегда. Сравниваем на
        // месте, не копируя: копия ради сравнения — это аллокация на каждого
        // игрока каждую секунду ровно там, где мы работу и убираем.
        if (!fresh && same(previous, lines, shown)) return true;

        if (fresh || previous == null) {
            objective.displayName(COLORS.deserialize(title));
            // Счёт задаёт порядок строк, но показывать его не надо — это те
            // самые красные цифры у правого края. Ставится один раз на цель, а
            // не каждый такт: свойство не меняется само по себе.
            objective.numberFormat(NumberFormat.blank());
        }
        if (objective.getDisplaySlot() != DisplaySlot.SIDEBAR) {
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        int before = previous == null ? 0 : previous.size();
        for (int i = 0; i < shown; i++) {
            String line = lines.get(i);
            // Разбор строки в Component — самая дорогая часть всего показа, и
            // делать её ради текста, который уже на экране, незачем.
            if (previous == null || i >= before || !line.equals(previous.get(i))) {
                teamFor(board, i).prefix(COLORS.deserialize(line));
            }
            // Больше — выше: считаем сверху вниз, чтобы порядок совпал с тем,
            // в котором строки собрали. Счёт зависит от ОБЩЕГО числа строк,
            // поэтому переставлять его приходится, когда список стал длиннее
            // или короче, — даже у строк, чей текст не менялся.
            if (previous == null || before != shown || i >= before) {
                objective.getScore(KEYS[i]).setScore(shown - i);
            }
        }
        // Хвост прошлого обновления. Без этого сайдбар не укорачивается:
        // вышедший из пати остался бы в списке навсегда. Чистим только то, что
        // раньше действительно показывалось.
        // Сколько ключей могло остаться от прошлого раза. Когда о прошлом
        // ничего не известно (доска новая или память сбросил reload), чистим
        // весь запас: там могли остаться строки, о которых мы уже не помним.
        int stale = previous == null || fresh ? MAX_LINES : before;
        for (int i = shown; i < stale; i++) {
            board.resetScores(KEYS[i]);
        }

        shownLines.put(id, List.copyOf(lines.subList(0, shown)));
        return true;
    }

    /** Совпадают ли показанные строки с первыми {@code count} новыми. */
    private static boolean same(List<String> previous, List<String> lines, int count) {
        if (previous == null || previous.size() != count) return false;
        for (int i = 0; i < count; i++) {
            if (!previous.get(i).equals(lines.get(i))) return false;
        }
        return true;
    }

    /**
     * Команда, отвечающая за строку с этим номером.
     *
     * Одна на номер, а не на текст: текст меняется каждый тик, а команда
     * должна остаться той же — иначе клиент получал бы поток создания и
     * удаления команд вместо правки префикса.
     */
    private static Team teamFor(Scoreboard board, int index) {
        String name = OBJECTIVE + "_" + index;
        Team team = board.getTeam(name);
        if (team == null) team = board.registerNewTeam(name);
        if (!team.hasEntry(KEYS[index])) team.addEntry(KEYS[index]);
        return team;
    }

    /** Убрать наш сайдбар, не трогая чужой. */
    void hide(Player player) {
        shownLines.remove(player.getUniqueId());
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
        shownLines.remove(player);
    }

    private Scoreboard boardFor(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(),
                // Своя доска, а не главная: на главной цель была бы общей для
                // всего сервера, и все увидели бы состав чужой пати.
                key -> Bukkit.getScoreboardManager().getNewScoreboard());
    }
}
