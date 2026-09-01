package ovh.aurumgg.guilds.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Множитель бонуса: сложение с зачарованиями и розыгрыш дробной части. */
class BonusMathTest {

    /** Монетка, которая всегда выпадает орлом: дробная часть засчитывается. */
    private static final RandomGenerator ALWAYS = fixed(0.0);

    /** Монетка, которая никогда не выпадает: дробная часть теряется. */
    private static final RandomGenerator NEVER = fixed(0.999999);

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    // ------------------------------------- сложение с зачарованиями

    @Test
    @DisplayName("Множитель считается от добычи с зачарованием, а не вместо неё")
    void stacksOnTopOfEnchantments() {
        // Кирка с «Удачей III» уже дала три алмаза вместо одного: события
        // Bukkit несут добычу, которую сервер посчитал сам. Бонус гильдии
        // умножает ЭТОТ результат.
        int withFortune = 3;
        assertEquals(6, BonusMath.scaled(withFortune, 2.0, ALWAYS),
                "×2 к трём алмазам от удачи — это шесть, а не два");

        // Без зачарования тот же бонус даёт вдвое меньше: усиление гильдии
        // тем ценнее, чем лучше инструмент, — так и задумано.
        assertEquals(2, BonusMath.scaled(1, 2.0, ALWAYS));
    }

    @Test
    @DisplayName("Бонус ничего не отнимает у зачарования, даже когда монетка против")
    void neverTakesAwayWhatEnchantmentGave() {
        // Худший случай розыгрыша не должен опускать добычу ниже исходной:
        // иначе «купленный бонус» иногда РЕЗАЛ бы дроп с хорошей кирки.
        for (int amount = 1; amount <= 20; amount++) {
            assertTrue(BonusMath.scaled(amount, 1.1, NEVER) >= amount, "амount=" + amount);
            assertEquals(0, BonusMath.extra(amount, 1.0, NEVER), "×1.0 ничего не добавляет");
        }
    }

    // ------------------------------------- розыгрыш дробной части

    @Test
    @DisplayName("Целая часть выдаётся всегда, дробная — по монетке")
    void wholePartAlwaysFractionRolled() {
        // 3 × 1.5 = 4.5 → четыре всегда, пятый с вероятностью 50%.
        assertEquals(5, BonusMath.scaled(3, 1.5, ALWAYS));
        assertEquals(4, BonusMath.scaled(3, 1.5, NEVER));
    }

    @Test
    @DisplayName("Целый множитель не зависит от монетки")
    void integerMultiplierIsDeterministic() {
        assertEquals(9, BonusMath.scaled(3, 3.0, ALWAYS));
        assertEquals(9, BonusMath.scaled(3, 3.0, NEVER));
    }

    @Test
    @DisplayName("Слабый множитель на одном предмете — это шанс, а не ноль")
    void smallMultiplierStillWorksOnSingleItem() {
        // Ради этого случая розыгрыш и сделан: округление вниз означало бы,
        // что ×1.1 не делает ничего для всего, что падает поштучно.
        assertEquals(2, BonusMath.scaled(1, 1.1, ALWAYS));
        assertEquals(1, BonusMath.scaled(1, 1.1, NEVER));
    }

    @Test
    @DisplayName("На длинной дистанции множитель сходится к обещанному")
    void convergesToPromisedRateOverManyRolls() {
        // Настоящая случайность с фиксированным зерном: проверяется именно то,
        // что «шанс вместо округления» в среднем даёт ровно множитель.
        RandomGenerator random = new java.util.Random(20260901);
        long total = 0;
        int rolls = 200_000;
        for (int i = 0; i < rolls; i++) total += BonusMath.scaled(1, 1.5, random);

        double average = (double) total / rolls;
        assertTrue(Math.abs(average - 1.5) < 0.01, "среднее " + average);
    }

    // ------------------------------------- граничные случаи

    @Test
    @DisplayName("Пустая и отрицательная добыча не превращается в предметы")
    void nothingFromNothing() {
        assertEquals(0, BonusMath.scaled(0, 3.0, ALWAYS));
        assertEquals(0, BonusMath.extra(0, 3.0, ALWAYS));
        assertEquals(0, BonusMath.scaled(-5, 3.0, ALWAYS), "отрицательного дропа не бывает");
    }

    @Test
    @DisplayName("extra — это добавка, а не итог")
    void extraIsTheDifference() {
        assertEquals(3, BonusMath.extra(3, 2.0, ALWAYS));
        assertEquals(0, BonusMath.extra(3, 1.0, ALWAYS));
    }
}
