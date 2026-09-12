package ovh.aurumgg.core.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Two-phase, revision-guarded editor for policies and exchange rules. */
public interface AurumRulesAdminApi {
    /** Current canonical rows from the runtime registry; empty means unavailable. */
    CompletionStage<Optional<List<RuleResource>>> list(RuleType type);

    /** Validate an exact replacement and issue a short-lived, actor-bound token. */
    CompletionStage<RuleChangePreview> preview(RuleMutationRequest request, String actor);

    /** Consume a preview token. A non-empty audit reason is mandatory. */
    CompletionStage<RuleApplyResult> apply(String token, String actor, String reason);
}
