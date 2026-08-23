-- События игрового сервера 7 Days to Die от companion-мода.
--
-- Миграция добавляющая: существующие таблицы не трогает. Это важно —
-- панель работает на живом сервере с настоящими игроками.
CREATE TABLE "mod_sevendays_events" (
    "id" TEXT NOT NULL,
    "server_id" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "player_id" TEXT NOT NULL,
    "player_name" TEXT NOT NULL,
    "text" TEXT,
    "actor_id" TEXT,
    "actor_name" TEXT,
    "x" DOUBLE PRECISION,
    "y" DOUBLE PRECISION,
    "z" DOUBLE PRECISION,
    "occurred_at" TIMESTAMP(3) NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "mod_sevendays_events_pkey" PRIMARY KEY ("id")
);

-- Лента сервера за период — основной запрос.
CREATE INDEX "mod_sevendays_events_server_id_occurred_at_idx"
    ON "mod_sevendays_events"("server_id", "occurred_at");

-- Отбор по виду события: «покажи только PvP» при разборе жалобы.
CREATE INDEX "mod_sevendays_events_server_id_kind_occurred_at_idx"
    ON "mod_sevendays_events"("server_id", "kind", "occurred_at");

ALTER TABLE "mod_sevendays_events"
    ADD CONSTRAINT "mod_sevendays_events_server_id_fkey"
    FOREIGN KEY ("server_id") REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE;
