package ovh.aurumgg.guilds.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Справка по командам: строка на команду и короткое описание рядом.
 *
 * <h2>Зачем отдельный класс</h2>
 *
 * Раньше помощь была тремя плотными строками, где команды перечислялись через
 * запятую: «invite, kick, promote, demote, transfer, disband — состав и
 * лидерство». Прочитать это можно, а вот понять, что делает demote и в каком
 * порядке ему давать аргументы, — уже нет. Помощь для того и нужна, чтобы не
 * приходилось спрашивать.
 *
 * Теперь на каждую команду приходится своя строка: слева — как её набирать
 * вместе с аргументами, справа — что она делает. Это скучный формат, и он
 * правильный: глаз ищет нужную строку по левому столбцу, а не вычитывает
 * перечисление.
 *
 * <h2>Почему это в core, а не рядом с командой</h2>
 *
 * Здесь нет ни одного класса Bukkit — значит, разбиение на страницы
 * проверяется обычным тестом. Ошибиться в нём легко: страницы считаются от
 * единицы, а список — от нуля, и промах на единицу прячет ровно одну команду.
 *
 * <h2>Права — забота вызывающего</h2>
 *
 * Кто что вправе делать, знает Bukkit, а не этот класс. Поэтому строки
 * администрирования просто не добавляют тем, у кого нет права: страницы тогда
 * считаются по тому, что человек реально увидит, и в конце не остаётся пустой
 * страницы из скрытых команд.
 */
public final class HelpBook {

    /**
     * Сколько команд на страницу.
     *
     * Восемь строк плюс заголовок и подпись — это ровно те десять строк, что
     * видно в чате не разворачивая его. Больше — и начало справки уедет вверх
     * ещё до того, как человек дочитает.
     */
    public static final int PER_PAGE = 8;

    /** Одна команда: как набирать и что делает. */
    public record Entry(String usage, String description) {}

    private final String title;
    private final List<Entry> entries;
    /** Команда, которой листают: «/guild help». */
    private final String pageCommand;

    private HelpBook(String title, String pageCommand, List<Entry> entries) {
        this.title = title;
        this.pageCommand = pageCommand;
        this.entries = List.copyOf(entries);
    }

    public static Builder titled(String title, String pageCommand) {
        return new Builder(title, pageCommand);
    }

    public int pages() {
        return Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
    }

    /**
     * Строки одной страницы, с цветными кодами вида {@code &e}.
     *
     * Номер страницы прижимается к границам, а не считается ошибкой: человек
     * набрал «/guild help 9», страниц две — показать вторую полезнее, чем
     * отчитать за неверный номер.
     */
    public List<String> page(int page) {
        int total = pages();
        int shown = Math.min(Math.max(page, 1), total);

        List<String> lines = new ArrayList<>();
        lines.add(header(shown, total));

        int from = (shown - 1) * PER_PAGE;
        int to = Math.min(from + PER_PAGE, entries.size());
        for (int i = from; i < to; i++) {
            Entry entry = entries.get(i);
            lines.add("&e" + entry.usage() + " &8— &7" + entry.description());
        }

        // Подпись только когда есть куда листать: на однностраничной справке
        // она была бы просто лишней строкой.
        if (shown < total) {
            lines.add("&8Дальше: &e" + pageCommand + " " + (shown + 1));
        }
        return lines;
    }

    private String header(int page, int total) {
        String counter = total > 1 ? " &7(стр. " + page + " из " + total + ")" : "";
        return "&6──── &e" + title + counter + " &6────";
    }

    public static final class Builder {
        private final String title;
        private final String pageCommand;
        private final List<Entry> entries = new ArrayList<>();

        private Builder(String title, String pageCommand) {
            this.title = title;
            this.pageCommand = pageCommand;
        }

        public Builder add(String usage, String description) {
            entries.add(new Entry(usage, description));
            return this;
        }

        /**
         * Добавить, только если условие выполнено.
         *
         * Ради администраторских команд: писать вокруг каждой отдельный if
         * значило бы разорвать список надвое и потерять его читаемость — а
         * список команд должен читаться так же подряд, как он потом
         * показывается.
         */
        public Builder addIf(boolean condition, String usage, String description) {
            if (condition) add(usage, description);
            return this;
        }

        public HelpBook build() {
            return new HelpBook(title, pageCommand, entries);
        }
    }
}
