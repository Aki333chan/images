package ovh.aurumgg.guilds.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Разбор аргументов, общий для выполнения команды и для автодополнения. */
class ArgWordsTest {

    private static final List<String> GUILDS =
            List.of("Драконы", "Ночные волки", "Ночные тени", "Стальной легион братства");

    // ------------------------------------------------------------ сроки

    @Test
    @DisplayName("Сроки разбираются в минуты, часы и дни")
    void сроки() {
        assertEquals(Duration.ofMinutes(30), ArgWords.duration("30m"));
        assertEquals(Duration.ofHours(2), ArgWords.duration("2h"));
        assertEquals(Duration.ofDays(7), ArgWords.duration("7d"));
    }

    @Test
    @DisplayName("Русская буква единицы принимается наравне с латинской")
    void русскаяРаскладка() {
        // Раскладку при наборе команды переключают не всегда, а «30м» человек
        // имел в виду ровно то же самое.
        assertEquals(Duration.ofMinutes(30), ArgWords.duration("30м"));
        assertEquals(Duration.ofHours(2), ArgWords.duration("2ч"));
        assertEquals(Duration.ofDays(7), ArgWords.duration("7д"));
    }

    @Test
    @DisplayName("Имя гильдии сроком не считается")
    void имяНеСрок() {
        // Ключевой случай: в /guild admin bonus grant срок необязателен, и
        // отличить его от начала имени можно только по виду.
        assertNull(ArgWords.duration("Драконы"));
        assertNull(ArgWords.duration("Ночные"));
        assertNull(ArgWords.duration("7"), "без единицы это не срок");
        assertNull(ArgWords.duration("d"), "без числа это не срок");
        assertNull(ArgWords.duration("0d"), "нулевой срок бессмыслен");
        assertNull(ArgWords.duration("-5d"));
        assertNull(ArgWords.duration("7w"), "недель в наборе нет");
        assertNull(ArgWords.duration(null));
    }

    // ------------------------------------------------- имена по словам

    @Test
    @DisplayName("На первой позиции предлагаются первые слова всех имён")
    void первоеСлово() {
        // args = ["bonus", "list", ""] — имя начинается с индекса 2
        List<String> words = ArgWords.nextWords(GUILDS, new String[] {"list", ""}, 1);
        assertEquals(List.of("Драконы", "Ночные", "Стальной"), words,
                "«Ночные» встречается дважды, но предлагается один раз");
    }

    @Test
    @DisplayName("Второе слово предлагается только у подходящих имён")
    void второеСлово() {
        // Набрано «Ночные» — «легион» из другой гильдии предлагать нельзя,
        // иначе человек допишет имя, которого не существует.
        List<String> words = ArgWords.nextWords(GUILDS, new String[] {"list", "Ночные", ""}, 1);
        assertEquals(List.of("волки", "тени"), words);
    }

    @Test
    @DisplayName("Регистр набранного слова не мешает")
    void регистрНеВажен() {
        List<String> words = ArgWords.nextWords(GUILDS, new String[] {"list", "НОЧНЫЕ", ""}, 1);
        assertEquals(List.of("волки", "тени"), words);
    }

    @Test
    @DisplayName("Имя длиннее двух слов дополняется до конца")
    void длинноеИмя() {
        assertEquals(List.of("легион"),
                ArgWords.nextWords(GUILDS, new String[] {"Стальной", ""}, 0));
        assertEquals(List.of("братства"),
                ArgWords.nextWords(GUILDS, new String[] {"Стальной", "легион", ""}, 0));
    }

    @Test
    @DisplayName("Когда имя набрано целиком, предлагать нечего")
    void имяЗакончилось() {
        assertTrue(ArgWords.nextWords(GUILDS, new String[] {"Драконы", ""}, 0).isEmpty(),
                "у «Драконы» второго слова нет");
    }

    @Test
    @DisplayName("Несовпавшее первое слово не даёт продолжений")
    void чужоеПервоеСлово() {
        assertTrue(ArgWords.nextWords(GUILDS, new String[] {"Кто-то", ""}, 0).isEmpty());
    }

    @Test
    @DisplayName("Позиция раньше начала имени ничего не ломает")
    void позицияДоИмени() {
        // Так бывает на промежуточных аргументах: метод должен молча вернуть
        // пустоту, а не свалиться на отрицательном индексе.
        assertTrue(ArgWords.nextWords(GUILDS, new String[] {""}, 3).isEmpty());
    }

    @Test
    @DisplayName("Пустой список гильдий не даёт подсказок")
    void гильдийНет() {
        assertTrue(ArgWords.nextWords(List.of(), new String[] {""}, 0).isEmpty());
    }
}
