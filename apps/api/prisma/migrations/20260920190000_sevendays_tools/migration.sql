CREATE TABLE "mod_sevendays_tools" (
  "server_id" TEXT PRIMARY KEY REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "revision" INTEGER NOT NULL DEFAULT 1,
  "data" JSONB NOT NULL
);
CREATE TABLE "mod_sevendays_tool_runs" (
  "server_id" TEXT NOT NULL REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "request_id" TEXT NOT NULL,
  "fingerprint" TEXT NOT NULL,
  "result" JSONB,
  "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("server_id", "request_id")
);
CREATE INDEX "mod_sevendays_tool_runs_created_at_idx" ON "mod_sevendays_tool_runs"("created_at");
