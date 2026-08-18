-- Лимиты сервера из Pterodactyl: нужны, чтобы показывать «занято из», а не
-- голое число байт. Обновляются при каждом синке; NULL — синка ещё не было.
ALTER TABLE "servers" ADD COLUMN "memory_limit_mb" INTEGER;
ALTER TABLE "servers" ADD COLUMN "disk_limit_mb" INTEGER;
ALTER TABLE "servers" ADD COLUMN "cpu_limit_percent" INTEGER;

-- История онлайна: один ряд на сервер и час, замеры внутри часа усредняются
-- на месте. При замере раз в 5 минут это 24 ряда в сутки на сервер.
CREATE TABLE "server_activity_samples" (
    "server_id" TEXT NOT NULL,
    "bucket" TIMESTAMP(3) NOT NULL,
    "avg_online" DOUBLE PRECISION NOT NULL,
    "max_online" INTEGER NOT NULL,
    "samples" INTEGER NOT NULL DEFAULT 1,

    CONSTRAINT "server_activity_samples_pkey" PRIMARY KEY ("server_id","bucket")
);

CREATE INDEX "server_activity_samples_server_id_bucket_idx"
    ON "server_activity_samples"("server_id", "bucket");

ALTER TABLE "server_activity_samples"
    ADD CONSTRAINT "server_activity_samples_server_id_fkey"
    FOREIGN KEY ("server_id") REFERENCES "servers"("id")
    ON DELETE CASCADE ON UPDATE CASCADE;
