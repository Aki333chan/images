-- Момент посадки в тюрьму EssentialsX.
--
-- Плагин хранит только время, когда игрока ВЫПУСТЯТ, и ноль для бессрочных;
-- времени посадки у него нет вообще. Именно оно отвечает на вопрос «сколько
-- он уже сидит», и другого источника не существует.
--
-- Уникальность по паре сервер+ник: один игрок не может сидеть в двух местах,
-- а повторная посадка обновляет запись, а не заводит вторую.
CREATE TABLE "minecraft_jail_records" (
    "id" TEXT NOT NULL,
    "server_id" TEXT NOT NULL,
    "player_name" TEXT NOT NULL,
    "jail" TEXT NOT NULL,
    "jailed_by" TEXT NOT NULL,
    "jailed_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "minecraft_jail_records_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "minecraft_jail_records_server_id_player_name_key"
    ON "minecraft_jail_records"("server_id", "player_name");

ALTER TABLE "minecraft_jail_records" ADD CONSTRAINT "minecraft_jail_records_server_id_fkey"
    FOREIGN KEY ("server_id") REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE;
