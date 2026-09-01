package ovh.aurumgg.guilds.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Справка по командам: разбиение на страницы и то, что ни одна не теряется. */
class HelpBookTest {

    private static HelpBook of(int commands) {
        HelpBook.Builder builder = HelpBook.titled("Гильдии", "/guild help");
        for (int i = 1; i <= commands; i++) {
            builder.add("/guild cmd" + i, "описание " + i);
        }
        return builder.build();
    }

    @Test
    @DisplayName("Короткая справка — одна страница, без подписи «дальше»")
    void singlePage() {
        List<String> lines = of(3).page(1);
        assertEquals(1, of(3).pages());
        assertEquals(4, lines.size(), "заголовок и три команды");
        assertFalse(String.join("\n", lines).contains("Дальше"));
    }

    @Test
    @DisplayName("Каждая команда попадает ровно на одну страницу")
    void everyCommandShownOnce() {
        HelpBook book = of(20);
        assertEquals(3, book.pages());

        // Промах на единицу в постраничном выводе теряет ровно одну команду, и
        // заметить это глазами в чате почти невозможно — поэтому проверяется
        // весь набор целиком, а не «на второй странице что-то есть».
        StringBuilder everything = new StringBuilder();
        for (int page = 1; page <= book.pages(); page++) {
            everything.append(String.join("\n", book.page(page))).append('\n');
        }
        for (int i = 1; i <= 20; i++) {
            String command = "/guild cmd" + i + " ";
            assertTrue(everything.toString().contains(command), "потерялась " + command);
        }
    }

    @Test
    @DisplayName("Номер страницы за границами прижимается, а не ругается")
    void pageIsClamped() {
        HelpBook book = of(20);
        assertEquals(book.page(3), book.page(99));
        assertEquals(book.page(1), book.page(0));
        assertEquals(book.page(1), book.page(-5));
    }

    @Test
    @DisplayName("Подпись «дальше» есть на всех страницах, кроме последней")
    void nextHintOnlyWhileThereIsMore() {
        HelpBook book = of(20);
        assertTrue(String.join("\n", book.page(1)).contains("/guild help 2"));
        assertTrue(String.join("\n", book.page(2)).contains("/guild help 3"));
        assertFalse(String.join("\n", book.page(3)).contains("Дальше"));
    }

    @Test
    @DisplayName("Описание идёт рядом с командой, а не отдельной строкой")
    void usageAndDescriptionOnOneLine() {
        String line = of(1).page(1).get(1);
        assertTrue(line.contains("/guild cmd1"), line);
        assertTrue(line.contains("описание 1"), line);
    }

    @Test
    @DisplayName("addIf с ложным условием не занимает место на странице")
    void skippedEntriesDoNotAffectPaging() {
        HelpBook book = HelpBook.titled("Гильдии", "/guild help")
                .add("/guild info", "о гильдии")
                .addIf(false, "/guild admin disband", "распустить чужую")
                .build();
        assertEquals(1, book.pages());
        assertEquals(2, book.page(1).size());
        assertFalse(String.join("\n", book.page(1)).contains("admin"));
    }

    @Test
    @DisplayName("Пустая справка не падает")
    void emptyBook() {
        HelpBook book = HelpBook.titled("Гильдии", "/guild help").build();
        assertEquals(1, book.pages());
        assertEquals(1, book.page(1).size(), "остаётся только заголовок");
    }
}
