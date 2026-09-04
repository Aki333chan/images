package org.ChisaO_o.gladiatorArena;

import org.bukkit.scoreboard.Objective;

import java.lang.reflect.InvocationTargetException;

/** Uses Paper's optional API without making AurumArena fail to load on Spigot. */
final class ScoreboardNumberFormatter {
    private static final String NUMBER_FORMAT = "io.papermc.paper.scoreboard.numbers.NumberFormat";

    private ScoreboardNumberFormatter() {}

    static boolean hideScores(Objective objective) {
        try {
            Class<?> formatType = Class.forName(NUMBER_FORMAT);
            Object blank = formatType.getMethod("blank").invoke(null);
            Objective.class.getMethod("numberFormat", formatType).invoke(objective, blank);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            return false;
        }
    }
}
