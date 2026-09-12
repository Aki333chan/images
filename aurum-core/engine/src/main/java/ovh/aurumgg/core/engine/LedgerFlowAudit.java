package ovh.aurumgg.core.engine;

import java.math.BigDecimal;

/** All-time committed flow counters for one currency. */
public record LedgerFlowAudit(long transactions, BigDecimal turnover, BigDecimal issued,
                              BigDecimal sunk, BigDecimal taxes) {}
