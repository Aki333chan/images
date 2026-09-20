-- Existing event data is retained; these indexes support subject/actor history.
CREATE INDEX "sdtd_event_player_history_idx" ON "mod_sevendays_events"("server_id", "player_id", "occurred_at", "id");
CREATE INDEX "sdtd_event_actor_history_idx" ON "mod_sevendays_events"("server_id", "actor_id", "occurred_at", "id");
