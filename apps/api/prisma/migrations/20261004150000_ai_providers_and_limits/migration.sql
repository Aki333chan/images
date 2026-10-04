ALTER TYPE "AiActionStatus" ADD VALUE IF NOT EXISTS 'executing';

ALTER TABLE "ai_usage_log"
  ADD COLUMN "provider" TEXT NOT NULL DEFAULT 'deepseek',
  ADD COLUMN "cost_usd_micros" BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN "reserved_cost_usd_micros" BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN "reserved_tokens" INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN "status" TEXT NOT NULL DEFAULT 'complete';

CREATE INDEX "ai_usage_log_created_at_idx" ON "ai_usage_log"("created_at");
