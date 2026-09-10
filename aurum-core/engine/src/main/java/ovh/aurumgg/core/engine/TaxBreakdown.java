package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Objects;

public record TaxBreakdown(BigDecimal sourceDebit, BigDecimal targetCredit, BigDecimal treasuryCredit) {
    public TaxBreakdown {
        Objects.requireNonNull(sourceDebit, "sourceDebit");
        Objects.requireNonNull(targetCredit, "targetCredit");
        Objects.requireNonNull(treasuryCredit, "treasuryCredit");
        if (sourceDebit.signum() < 0 || targetCredit.signum() < 0 || treasuryCredit.signum() < 0) {
            throw new IllegalArgumentException("Tax breakdown cannot contain negative values");
        }
        if (sourceDebit.compareTo(targetCredit.add(treasuryCredit)) != 0) {
            throw new IllegalArgumentException("Tax breakdown is not balanced");
        }
    }
}
