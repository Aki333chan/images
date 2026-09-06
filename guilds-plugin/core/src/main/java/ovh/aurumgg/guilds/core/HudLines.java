package ovh.aurumgg.guilds.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Содержимое сайдбара — строками, без единого обращения к Bukkit.
 *
 * <h2>Особенность scoreboard, из-за которой это отдельный класс</h2>
 *
 * <b>Строк не больше пятнадцати.</b> Столько влезает в сайдбар. Всё, что
 * не поместилось, обрезается здесь, а не превращается в невидимый хвост.
 *
 * <p>Раньше здесь была и вторая забота — разводить одинаковые строки хвостами
 * «&amp;r», потому что записи scoreboard уникальны по тексту и два одинаковых
 * разделителя схлопывались в один. Её больше нет: строки выводятся префиксами
 * команд, а записи стали невидимыми ключами по номеру строки (см.
 * SidebarKeeper). Совпадение видимого текста теперь никого не волнует.
 *
 * <h2>Что важнее при нехватке места</h2>
 *
 * Урезается СПИСОК ПАТИ, а не блок гильдии. Пати — это те, с кем сейчас идёт
 * бой, но их здоровье видно и по строке «в пати 8 человек», а вот блок гильдии
 * занимает четыре строки и целиком помещается всегда. Обрезанный список пати
 * честно заканчивается строкой «и ещё N».
 *
 * <h2>Бонусы</h2>
 *
 * Действующие усиления показываются зелёным под блоком гильдии — чтобы человек
 * ВИДЕЛ, что купленный гильдией бонус сейчас работает, а не гадал. Без этого
 * бонус на добычу неотличим от его отсутствия: руда падает, а насколько её
 * стало больше, на глаз не скажешь.
 *
 * Строк под них не больше трёх: видов бонусов всего пять, и в редком случае,
 * когда действуют все, остальные сворачиваются в «и ещё N» — иначе блок
 * гильдии съел бы список пати, ради которого сайдбар в бою и нужен.
 */
public final class HudLines {

    /**
     * Переводчик подписей сайдбара.
     *
     * Аргументом, а не полем: сборка строк — чистая функция, её проверяют
     * тестом, а язык сервера живёт в конфиге, до которого core не достаёт.
     * Умолчание {@link #RU} оставляет прежний вид тем вызовам, до которых
     * перевод ещё не дошёл, — и на нём же держатся старые тесты.
     */
    public interface Labels {
        String get(String key);
    }

    /** Встроенные русские подписи: запасной вариант и основа тестов. */
    public static final Labels RU = key -> switch (key) {
        case "hud.party" -> "Пати";
        case "hud.guild" -> "Гильдия";
        case "hud.rank" -> "Ранг";
        case "hud.online" -> "В сети";
        case "hud.bank" -> "Банк";
        case "hud.bonuses" -> "Бонусы";
        case "hud.more" -> "… и ещё {count}";
        case "hud.d" -> "д";
        case "hud.h" -> "ч";
        case "hud.m" -> "м";
        case "hud.lessThanMinute" -> "<1м";
        case "mc.bonus.short.miningSpeed" -> "Спешка";
        case "mc.bonus.short.movementSpeed" -> "Скорость";
        case "mc.bonus.short.blockDrops" -> "Блоки";
        case "mc.bonus.short.mobDrops" -> "Мобы";
        case "mc.bonus.short.experience" -> "Опыт";
        // Строчными, как и в словаре панели: одно и то же имя ключа должно
        // означать одно и то же слово по обе стороны.
        case "mc.rank.leader" -> "лидер";
        case "mc.rank.officer" -> "офицер";
        case "mc.rank.member" -> "участник";
        case "time.forever" -> "навсегда";
        case "time.s" -> "{n} с";
        case "time.min" -> "{n} мин";
        case "time.h" -> "{n} ч";
        case "time.hMin" -> "{n} ч {m} мин";
        case "time.d" -> "{n} д";
        case "time.dH" -> "{n} д {h} ч";
        case "mc.bonus.level" -> "уровень {n}";
        default -> key;
    };

    /** Сколько строк влезает в сайдбар. */
    public static final int MAX_LINES = 15;

    /**
     * Сколько бонусов показывать строками.
     *
     * Больше трёх — и блок гильдии начинает вытеснять пати. Полный список
     * всегда есть в {@code /guild bonuses}, а в сайдбаре важнее сам факт «у
     * нас что-то действует» и ближайший срок.
     */
    static final int MAX_BONUS_LINES = 3;

    private HudLines() {}

    public static List<String> build(HudModel model) {
        return build(model, RU);
    }

    public static List<String> build(HudModel model, Labels t) {
        List<String> lines = new ArrayList<>();
        if (model == null || model.isEmpty()) return lines;

        if (model.hasParty()) {
            lines.add("&7" + t.get("hud.party") + " &8(&f" + model.partyMembers().size()
                    + "&8/&f" + model.partyLimit() + "&8)");
            // Сколько строк можно отдать под участников: всё, что останется
            // после блока гильдии и разделителя между блоками.
            int reserved = model.hasGuild() ? guildBlock(model, t).size() + 1 : 0;
            int room = MAX_LINES - lines.size() - reserved;
            List<HudModel.Member> members = model.partyMembers();

            // Если не помещаются даже все участники, последнюю строку отдаём
            // под «и ещё N» — иначе список молча обрывался бы на середине, и
            // человек считал бы, что половина пати вышла с сервера.
            boolean truncated = members.size() > room;
            int shown = truncated ? Math.max(0, room - 1) : members.size();
            for (int i = 0; i < shown; i++) {
                lines.add(memberLine(members.get(i)));
            }
            if (truncated) lines.add("&8" + more(t, members.size() - shown));
        }

        if (model.hasGuild()) {
            if (!lines.isEmpty()) lines.add("");
            lines.addAll(guildBlock(model, t));
        }

        return lines.size() > MAX_LINES ? List.copyOf(lines.subList(0, MAX_LINES)) : lines;
    }

    private static List<String> guildBlock(HudModel model, Labels t) {
        List<String> block = new ArrayList<>();
        block.add("&7" + t.get("hud.guild"));
        block.add("&b[" + model.guildTag() + "] &f" + model.guildName());
        if (model.rank() != null) {
            block.add("&7" + t.get("hud.rank") + ": &f" + t.get(model.rank().titleKey()));
        }
        block.add("&7" + t.get("hud.online") + ": &f" + model.guildOnline()
                + "&7/&f" + model.guildTotal());
        // Баланс показывается, только если банк вообще работает: строка
        // «Банк: 0» на сервере без Vault выглядит как пропавшие деньги.
        if (model.bankBalance() != null) {
            block.add("&7" + t.get("hud.bank") + ": &6" + money(model.bankBalance()));
        }
        block.addAll(bonusLines(model, t));
        return block;
    }

    /**
     * Действующие бонусы — зелёным, с остатком времени.
     *
     * Зелёный по всей строке, а не только у названия: это единственный блок
     * сайдбара, который сообщает не факты о составе, а что игроку сейчас
     * ХОРОШО, и цветом он отделяется от остального с одного взгляда.
     */
    private static List<String> bonusLines(HudModel model, Labels t) {
        List<String> lines = new ArrayList<>();
        if (!model.hasBonuses()) return lines;

        List<HudModel.Bonus> bonuses = model.bonuses();
        boolean truncated = bonuses.size() > MAX_BONUS_LINES;
        int shown = truncated ? MAX_BONUS_LINES - 1 : bonuses.size();

        lines.add("&7" + t.get("hud.bonuses"));
        for (int i = 0; i < shown; i++) {
            lines.add(bonusLine(bonuses.get(i), t));
        }
        if (truncated) lines.add("&8" + more(t, bonuses.size() - shown));
        return lines;
    }

    /**
     * «… и ещё 3».
     *
     * Число подстановкой, а не приклеенное сзади: по-английски оно стоит в
     * середине («… and 3 more»), и приклеивание молча ломало бы фразу.
     */
    private static String more(Labels t, int count) {
        return Messages.apply(t.get("hud.more"), java.util.Map.of("count", String.valueOf(count)));
    }

    private static String bonusLine(HudModel.Bonus bonus, Labels t) {
        String value = bonus.multiplier()
                ? "\u00D7" + multiplierText(bonus.magnitude())
                : String.valueOf(Math.round(bonus.magnitude()));
        String left = bonus.secondsLeft() == null
                ? ""
                : " &8" + shortDurationText(bonus.secondsLeft(), t);
        return "&a" + t.get(bonus.title()) + " " + value + left;
    }

    /**
     * Множитель без хвоста из нулей: «×1.5», а не «×1.50».
     *
     * Публичный: тем же видом бонус подписан и в {@code /guild info}, чтобы
     * человек, увидевший строку в сайдбаре, узнал её в карточке гильдии.
     *
     * Отдельно от {@link #money(double)}: у денег два знака после запятой —
     * это копейки, а у множителя второй знак ничего не значит и только ест
     * ширину, которой в сайдбаре и так нет.
     */
    public static String multiplierText(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        // Хвостовые нули и осиротевшую точку — долой: «1.50» → «1.5», «2.00» → «2».
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    /**
     * Остаток времени в два-три знака: «6д», «3ч», «12м».
     *
     * Публичный по той же причине, что и множитель: сроки в сайдбаре и в
     * карточке гильдии должны читаться одинаково.
     *
     * Округление ВВЕРХ, а не вниз: выданный на неделю бонус живёт 167 часов с
     * копейками, и «6д» сразу после выдачи читалось бы как обман. Меньше
     * минуты — «&lt;1м», а не «0м»: ноль выглядит как «уже кончился», хотя
     * бонус ещё действует.
     */
    public static String shortDurationText(long seconds) {
        return shortDurationText(seconds, RU);
    }

    public static String shortDurationText(long seconds, Labels t) {
        if (seconds <= 0) return "0" + t.get("hud.m");
        if (seconds < 60) return t.get("hud.lessThanMinute");
        if (seconds < 3600) return (seconds + 59) / 60 + t.get("hud.m");
        if (seconds < 86_400) return (seconds + 3599) / 3600 + t.get("hud.h");
        return (seconds + 86_399) / 86_400 + t.get("hud.d");
    }

    private static String memberLine(HudModel.Member member) {
        String glyph = member.online()
                ? HealthGlyph.forHealth(member.healthPercent())
                : HealthGlyph.offline();
        String name = member.online() ? "&f" + member.name() : "&8" + member.name();
        // Звёздочка у лидера, а не слово «лидер»: в сайдбаре каждый символ на
        // счету, а кто главный, понятно и так.
        return glyph + " " + name + (member.leader() ? " &6★" : "");
    }

    /**
     * Сумма без хвоста из нулей.
     *
     * Целое показывается целым: «Банк: 1200» читается быстрее, чем
     * «Банк: 1200.00», а сайдбар и без того узкий.
     */
    public static String money(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

}
