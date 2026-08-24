-- Последний снимок нагрузки сервера: ровно одна строка на сервер.
CREATE TABLE "server_metric_samples" (
    "server_id" TEXT NOT NULL,
    "state" TEXT,
    "cpu_absolute" DOUBLE PRECISION,
    "memory_bytes" BIGINT,
    "players_online" INTEGER,
    "players_max" INTEGER,
    "sampled_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "server_metric_samples_pkey" PRIMARY KEY ("server_id")
);

-- Состояние алерта по паре (сервер, тип). type намеренно строка, а не enum:
-- добавление нового вида алерта не должно требовать миграции типа в БД.
CREATE TABLE "server_alert_states" (
    "server_id" TEXT NOT NULL,
    "type" TEXT NOT NULL,
    "breaching_since" TIMESTAMP(3),
    "last_notified_at" TIMESTAMP(3),
    "last_value" DOUBLE PRECISION,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "server_alert_states_pkey" PRIMARY KEY ("server_id","type")
);

ALTER TABLE "server_metric_samples" ADD CONSTRAINT "server_metric_samples_server_id_fkey"
    FOREIGN KEY ("server_id") REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "server_alert_states" ADD CONSTRAINT "server_alert_states_server_id_fkey"
    FOREIGN KEY ("server_id") REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE;
