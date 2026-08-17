-- AI-ассистент: журнал расхода, предложенные действия и связь действия ИИ
-- с человеком, от чьего имени оно выполнено.

-- От чьего имени действовал не-человек. Для actor_type='ai' — id сотрудника,
-- который вёл диалог и подтвердил действие. Без этого в журнале осталось бы
-- «сделал ИИ», и концов не найти.
ALTER TABLE "audit_log" ADD COLUMN "on_behalf_of" TEXT;
CREATE INDEX "audit_log_on_behalf_of_idx" ON "audit_log"("on_behalf_of");

CREATE TABLE "ai_usage_log" (
    "id" BIGSERIAL NOT NULL,
    "user_id" TEXT NOT NULL,
    "model" TEXT NOT NULL,
    "prompt_tokens" INTEGER NOT NULL DEFAULT 0,
    "completion_tokens" INTEGER NOT NULL DEFAULT 0,
    "tool_calls" INTEGER NOT NULL DEFAULT 0,
    "error" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ai_usage_log_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "ai_usage_log_user_id_created_at_idx" ON "ai_usage_log"("user_id", "created_at");

CREATE TYPE "AiActionStatus" AS ENUM ('pending', 'approved', 'rejected', 'failed', 'expired');

-- Предложение хранится на сервере, а не в браузере: подтверждение должно
-- исполнять ровно то, что предложила модель. Если бы аргументы приезжали
-- с клиента вместе с подтверждением, «подтверждение» ничего бы не значило.
CREATE TABLE "ai_pending_actions" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "tool" TEXT NOT NULL,
    "args" JSONB NOT NULL,
    "from_untrusted_input" BOOLEAN NOT NULL DEFAULT false,
    "status" "AiActionStatus" NOT NULL DEFAULT 'pending',
    "result" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolved_at" TIMESTAMP(3),

    CONSTRAINT "ai_pending_actions_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "ai_pending_actions_user_id_status_idx" ON "ai_pending_actions"("user_id", "status");
