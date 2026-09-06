package dev.addons.npc.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A percentage modifier that is permanent when expiresAtMillis is zero. */
public record TimedPercentage(double percent, long expiresAtMillis) {
    public TimedPercentage {
        if (!Double.isFinite(percent) || percent < 0 || percent > 1000) {
            throw new IllegalArgumentException("Percentage must be between 0 and 1000.");
        }
        expiresAtMillis = Math.max(0, expiresAtMillis);
    }

    public static TimedPercentage none() { return new TimedPercentage(0, 0); }
    public boolean active(long now) { return percent > 0 && (expiresAtMillis == 0 || expiresAtMillis > now); }
    public boolean permanent() { return percent > 0 && expiresAtMillis == 0; }

    public double discount(double base, long now) {
        if (!active(now)) return base;
        double factor = Math.max(0, 100.0 - Math.min(100.0, percent));
        return calculate(base, factor);
    }

    public double bonus(double base, long now) {
        return active(now) ? calculate(base, 100.0 + percent) : base;
    }

    private static double calculate(double base, double percentFactor) {
        return BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(percentFactor))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
    }

    public String remaining(long now) {
        if (!active(now)) return "истекла";
        if (permanent()) return "до отключения";
        long seconds = Math.max(1, (expiresAtMillis - now + 999) / 1000);
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        if (days > 0) return days + "д " + hours + "ч";
        if (hours > 0) return hours + "ч " + minutes + "м";
        if (minutes > 0) return minutes + "м " + seconds + "с";
        return seconds + "с";
    }
}
