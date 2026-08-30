package ovh.aurumgg.guilds.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Содержимое сайдбара — строками, без единого обращения к Bukkit.
 *
 * <h2>Две особенности scoreboard, из-за которых это отдельный класс</h2>
 *
 * <b>Строки обязаны быть разными.</b> В сайдбаре каждая строка — это запись
 * (entry), а записи в scoreboard уникальны по своему тексту. Две одинаковые
 * строки не покажутся дважды: вторая просто перезапишет первую, и сайдбар
 * молча потеряет строчку. Больнее всего это бьёт по пустым строкам-разделителям
 * — их обычно несколько. Лечится добавлением невидимого «&amp;r» в конец
 * дубликата: текст остаётся тем же на вид, а строкой становится другим.
 * Приём известный, но именно из-за молчаливости ошибки его стоит держать в
 * одном месте и под тестом.
 *
 * <b>Строк не больше пятнадцати.</b> Столько влезает в сайдбар. Всё, что
 * не поместилось, обрезается здесь, а не превращается в невидимый хвост.
 *
 * <h2>Что важнее при нехватке места</h2>
 *
 * Урезается СПИСОК ПАТИ, а не блок гильдии. Пати — это те, с кем сейчас идёт
 * бой, но их здоровье видно и по строке «в пати 8 человек», а вот блок гильдии
 * занимает четыре строки и целиком помещается всегда. Обрезанный список пати
 * честно заканчивается строкой «и ещё N».
 */
public final class HudLines {

    /** Сколько строк влезает в сайдбар. */
    public static final int MAX_LINES = 15;

    private HudLines() {}

    public static List<String> build(HudModel model) {
        List<String> lines = new ArrayList<>();
        if (model == null || model.isEmpty()) return lines;

        if (model.hasParty()) {
            lines.add("&7Пати &8(&f" + model.partyMembers().size() + "&8/&f" + model.partyLimit() + "&8)");
            // Сколько строк можно отдать под участников: всё, что останется
            // после блока гильдии и разделителя между блоками.
            int reserved = model.hasGuild() ? guildBlock(model).size() + 1 : 0;
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
            if (truncated) lines.add("&8… и ещё " + (members.size() - shown));
        }

        if (model.hasGuild()) {
            if (!lines.isEmpty()) lines.add("");
            lines.addAll(guildBlock(model));
        }

        return dedupe(lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines);
    }

    private static List<String> guildBlock(HudModel model) {
        List<String> block = new ArrayList<>();
        block.add("&7Гильдия");
        block.add("&b[" + model.guildTag() + "] &f" + model.guildName());
        if (model.rank() != null) block.add("&7Ранг: &f" + model.rank().title());
        block.add("&7В сети: &f" + model.guildOnline() + "&7/&f" + model.guildTotal());
        // Баланс показывается, только если банк вообще работает: строка
        // «Банк: 0» на сервере без Vault выглядит как пропавшие деньги.
        if (model.bankBalance() != null) {
            block.add("&7Банк: &6" + money(model.bankBalance()));
        }
        return block;
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

    /**
     * Развести одинаковые строки, оставив их одинаковыми на вид.
     *
     * «&amp;r» — код сброса форматирования: в конце строки он ничего не
     * меняет визуально, но делает её другой строкой для scoreboard.
     */
    static List<String> dedupe(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            String candidate = line;
            while (!seen.add(candidate)) candidate = candidate + "&r";
            result.add(candidate);
        }
        return result;
    }
}
