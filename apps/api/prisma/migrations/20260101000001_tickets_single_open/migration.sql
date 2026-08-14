-- Инвариант «один открытый тикет на пару (server_id, player_uuid)».
-- Prisma не умеет частичные уникальные индексы в схеме, поэтому индекс
-- создаётся вручную; TicketsService рассчитывает на него при гонках (P2002).
CREATE UNIQUE INDEX "tickets_one_open_per_player"
    ON "tickets" ("server_id", "player_uuid")
    WHERE "status" = 'OPEN';
