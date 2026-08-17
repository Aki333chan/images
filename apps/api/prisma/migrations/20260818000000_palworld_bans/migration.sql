-- Баны Palworld.
--
-- REST API Palworld умеет банить и разбанивать, но списка банов не отдаёт —
-- поэтому его ведёт панель: причина, кто забанил и когда. Срока нет:
-- временных банов сам сервер не поддерживает.
CREATE TABLE "mod_palworld_bans" (
    "id" TEXT NOT NULL,
    "server_id" TEXT NOT NULL,
    "player_name" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "reason" TEXT NOT NULL,
    "created_by_id" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "pardoned_at" TIMESTAMP(3),
    "pardoned_by_id" TEXT,

    CONSTRAINT "mod_palworld_bans_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "mod_palworld_bans_server_id_pardoned_at_idx"
    ON "mod_palworld_bans"("server_id", "pardoned_at");

ALTER TABLE "mod_palworld_bans" ADD CONSTRAINT "mod_palworld_bans_server_id_fkey"
    FOREIGN KEY ("server_id") REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE;
