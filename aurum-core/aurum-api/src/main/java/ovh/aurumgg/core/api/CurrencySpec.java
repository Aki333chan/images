package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

public record CurrencySpec(String id, String displayName, String symbol, int scale) {
    private static final BigDecimal DATABASE_LIMIT = new BigDecimal("10000000000000000");

    public CurrencySpec {
        id = Objects.requireNonNull(id, "id").trim().toLowerCase(Locale.ROOT);
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        symbol = Objects.requireNonNull(symbol, "symbol");
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Invalid currency id");
        }
        if (displayName.isEmpty() || displayName.length() > 64 || symbol.length() > 16
                || displayName.chars().anyMatch(Character::isISOControl)
                || symbol.chars().anyMatch(Character::isISOControl) || scale < 0 || scale > 8) {
            throw new IllegalArgumentException("Invalid currency specification");
        }
    }

    public BigDecimal requireAmount(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        try {
            BigDecimal normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
            if (normalized.abs().compareTo(DATABASE_LIMIT) >= 0) {
                throw new IllegalArgumentException("Amount exceeds the ledger limit");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount has more than " + scale + " decimal places", exception);
        }
    }
}
