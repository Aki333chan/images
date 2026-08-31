package ovh.aurumgg.guilds.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.guilds.api.GuildRank;

/** Содержимое сайдбара, индикатор здоровья, проверка имён и разбор конфига. */
class HudAndNamesTest {

    // ------------------------------------------------------------ сайдбар

    @Test
    @DisplayName("Коды цвета доходят до вывода нетронутыми")
    void цветаНеЭкранируются() {
        // Регрессия на настоящую поломку: коды показывались буквами —
        // «&7Пати &8(&f1&8/&f4&8)». Ломалось не здесь: строки были верные, а
        // вывод писал их в запись scoreboard, где форматирование не
        // разбирается вообще. Этот тест сторожит свою половину контракта —
        // что core отдаёт коды как есть и ничего с ними не делает.
        HudModel model = new HudModel(
                List.of(new HudModel.Member("Steve", 100.0, true, true)),
                4, null, null, null, 0, 0, null);

        List<String> lines = HudLines.build(model);
        assertTrue(lines.get(0).startsWith("&7Пати"), lines.get(0));
        assertTrue(lines.get(0).contains("&8(&f1&8/&f4&8)"), lines.get(0));
    }

    @Test
    @DisplayName("Одинаковые разделители больше не разводятся хвостами")
    void разделителиОстаютсяПустыми() {
        // Раньше повторяющиеся строки приходилось делать разными («&r» в
        // конец), потому что записи scoreboard уникальны по тексту. Теперь
        // строка — это префикс команды, а запись — невидимый ключ по номеру
        // строки, и совпадение текста ничего не ломает.
        HudModel model = new HudModel(
                List.of(new HudModel.Member("Steve", 100.0, true, true)),
                4, "Драконы", "DRG", GuildRank.LEADER, 1, 1, null);

        for (String line : HudLines.build(model)) {
            assertFalse(line.endsWith("&r"), "лишний хвост: " + line);
        }
    }

    @Test
    @DisplayName("Пустая модель не даёт ни одной строки")
    void пустойСайдбарНеПоказывается() {
        HudModel empty = new HudModel(List.of(), 8, null, null, null, 0, 0, null);
        assertTrue(HudLines.build(empty).isEmpty());
    }

    @Test
    @DisplayName("Блок гильдии показывает тег, ранг и сколько в сети")
    void блокГильдии() {
        HudModel model = new HudModel(
                List.of(), 8, "Драконы", "DRG", GuildRank.OFFICER, 4, 12, null);

        List<String> lines = HudLines.build(model);

        assertTrue(lines.stream().anyMatch(line -> line.contains("[DRG]")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Драконы")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("офицер")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("4") && line.contains("12")),
                lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.contains("Банк")),
                "без Vault строки про банк быть не должно — она выглядела бы как пропавшие деньги");
    }

    @Test
    @DisplayName("Баланс банка показывается, только когда банк есть")
    void банкВСайдбаре() {
        HudModel model = new HudModel(
                List.of(), 8, "Драконы", "DRG", GuildRank.MEMBER, 1, 1, 1200.0);

        assertTrue(HudLines.build(model).stream().anyMatch(line -> line.contains("Банк: &61200")),
                HudLines.build(model).toString());
    }

    @Test
    @DisplayName("Здоровье участников пати — цветным символом, офлайн серым")
    void индикаторыЗдоровья() {
        HudModel model = new HudModel(List.of(
                new HudModel.Member("Анна", 100, true, true),
                new HudModel.Member("Борис", 30, true, false),
                new HudModel.Member("Вера", 0, false, false)),
                8, null, null, null, 0, 0, null);

        List<String> lines = HudLines.build(model);

        assertTrue(lines.stream().anyMatch(line -> line.startsWith("&a" + HealthGlyph.SYMBOL)));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("&c" + HealthGlyph.SYMBOL)));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("&8" + HealthGlyph.SYMBOL)));
        assertTrue(lines.stream().anyMatch(line -> line.contains("★")), "лидер помечен");
    }

    @Test
    @DisplayName("Длинная пати обрезается, но блок гильдии остаётся целым")
    void обрезаетсяПати() {
        List<HudModel.Member> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new HudModel.Member("Игрок" + i, 100, true, i == 0));
        }
        HudModel model = new HudModel(many, 50, "Драконы", "DRG", GuildRank.LEADER, 3, 9, 500.0);

        List<String> lines = HudLines.build(model);

        assertTrue(lines.size() <= HudLines.MAX_LINES, "в сайдбар влезает не больше 15 строк");
        assertTrue(lines.stream().anyMatch(line -> line.contains("и ещё")),
                "обрезанный список честно говорит, что он обрезан: " + lines);
        // Блок гильдии дошёл целиком.
        assertTrue(lines.stream().anyMatch(line -> line.contains("[DRG]")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Банк")), lines.toString());
    }

    @Test
    @DisplayName("Цвет здоровья идёт от зелёного к красному и не ломается на краях")
    void шкалаЗдоровья() {
        assertEquals("&a", HealthGlyph.color(100));
        assertEquals("&a", HealthGlyph.color(80));
        assertEquals("&e", HealthGlyph.color(70));
        assertEquals("&6", HealthGlyph.color(45));
        assertEquals("&c", HealthGlyph.color(25));
        assertEquals("&4", HealthGlyph.color(5));
        assertEquals("&8", HealthGlyph.color(0));
        // Значения вне диапазона приходят от деления на максимум HP, который у
        // игрока с эффектами бывает и больше двадцати.
        assertEquals("&a", HealthGlyph.color(140));
        assertEquals("&8", HealthGlyph.color(-3));
    }

    @Test
    @DisplayName("Нулевой максимум здоровья не даёт NaN")
    void нулевойМаксимум() {
        // NaN в сравнениях ведёт себя так, что индикатор молча стал бы серым
        // у всех сразу.
        assertEquals(0, HealthGlyph.percent(5, 0));
        assertEquals(50, HealthGlyph.percent(10, 20));
    }

    // -------------------------------------------------------- имена и тег

    @Test
    @DisplayName("Цветовые коды в имени и теге запрещены")
    void цветаВИменахЗапрещены() {
        // Иначе первый же игрок заведёт мерцающий тег, а следующий — невидимый.
        assertFalse(GuildNames.checkTag("&kAA", 4).ok());
        assertFalse(GuildNames.checkTag("§cAA", 4).ok());
        assertFalse(GuildNames.checkName("Дра&aконы", 24).ok());
        assertTrue(GuildNames.checkTag("ДРК", 4).ok(), "кириллица разрешена");
        assertTrue(GuildNames.checkName("Драконы", 24).ok());
    }

    @Test
    @DisplayName("Пробелы по краям и длина проверяются")
    void длинаИПробелы() {
        assertFalse(GuildNames.checkName(" Драконы", 24).ok());
        assertFalse(GuildNames.checkName("Др", 24).ok());
        assertFalse(GuildNames.checkName("Д".repeat(25), 24).ok());
        assertFalse(GuildNames.checkTag("D R", 4).ok());
        assertFalse(GuildNames.checkTag("DRAGO", 4).ok());
        assertTrue(GuildNames.checkTag("DRG", 4).ok());
    }

    @Test
    @DisplayName("Уникальность считается без учёта регистра")
    void ключУникальности() {
        assertEquals(GuildNames.uniqueKey("Драконы"), GuildNames.uniqueKey("дРаКоНы"));
        assertNotEquals(GuildNames.uniqueKey("Драконы"), GuildNames.uniqueKey("Драконъ"));
    }

    @Test
    @DisplayName("Имя группы LuckPerms собирается из id, а не из тега")
    void имяГруппыОтId() {
        // Тег меняется и содержит что угодно, включая кириллицу; имя группы
        // обязано быть постоянным и безопасным.
        assertEquals("guild_17", GuildNames.groupName("guild_", 17));
    }

    // -------------------------------------------------------------- конфиг

    @Test
    @DisplayName("Числа вне разумного зажимаются, а не принимаются молча")
    void конфигЗажимает() {
        var config = GuildsConfig.fromMap(Map.of(
                "guild.max-tag-length", 99,
                "guild.max-name-length", 1,
                "party.max-members", 0,
                "party.invite-seconds", 100000,
                "hud.refresh-ms", 1));

        assertEquals(6, config.maxTagLength());
        assertEquals(3, config.maxNameLength());
        assertEquals(2, config.maxPartyMembers());
        assertEquals(Duration.ofMinutes(10), config.partyInviteTtl());
        assertEquals(Duration.ofMillis(500), config.hudRefresh());
    }

    @Test
    @DisplayName("Негодный префикс таблиц заменяется целиком, а не вычищается")
    void префиксТаблиц() {
        // Вычистка из «guilds; DROP TABLE users--» сделала бы
        // «guildsDROPTABLEusers» — формально безопасно, но плагин молча начал
        // бы работать с таблицами, которых никто не заводил.
        assertEquals(GuildsConfig.DEFAULT_PREFIX, GuildsConfig.tablePrefix("guilds; DROP TABLE x--"));
        assertEquals("my_guilds", GuildsConfig.tablePrefix("my_guilds"));

        var config = GuildsConfig.fromMap(Map.of("database.table-prefix", "my_guilds"));
        assertEquals("my_guilds", config.guildsTable());
        assertEquals("my_guilds_members", config.membersTable());
        assertEquals("my_guilds_bank_log", config.bankLogTable());
    }

    @Test
    @DisplayName("Негодный префикс групп LuckPerms тоже заменяется")
    void префиксГрупп() {
        // Кириллица или пробел привели бы к тому, что группа не создаётся, а
        // гильдия уже есть.
        assertEquals("guild_", GuildsConfig.groupPrefix("гильдия "));
        assertEquals("clan-", GuildsConfig.groupPrefix("clan-"));
    }

    @Test
    @DisplayName("Пустой конфиг даёт рабочие значения по умолчанию")
    void значенияПоУмолчанию() {
        var config = GuildsConfig.fromMap(Map.of());

        assertEquals(4, config.maxTagLength());
        assertEquals(24, config.maxNameLength());
        assertTrue(config.hudEnabled());
        assertFalse(config.requireCreatePermission(), "по умолчанию гильдию может завести любой");
        assertEquals(Set.of(true), Set.of(config.bankEnabled()));
    }
}
