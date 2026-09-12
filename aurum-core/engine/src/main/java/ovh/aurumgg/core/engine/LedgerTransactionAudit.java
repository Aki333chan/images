package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ovh.aurumgg.core.api.TransactionCategory;

public record LedgerTransactionAudit(UUID id, String idempotencyKey, TransactionCategory category,
                                     String status, BigDecimal gross, BigDecimal net, BigDecimal tax,
                                     String failure, String metadata, Instant createdAt,
                                     Instant committedAt, List<LedgerPostingAudit> postings) {
    public LedgerTransactionAudit {
        postings = List.copyOf(postings);
    }
}
