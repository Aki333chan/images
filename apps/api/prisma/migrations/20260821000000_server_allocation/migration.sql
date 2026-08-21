-- Адрес сервера: по нему игроки заходят, и в панели он нужнее номера ноды.
-- Заполняется при ближайшем синке с Pterodactyl, поэтому колонки nullable.
ALTER TABLE "servers" ADD COLUMN "allocation_ip" TEXT;
ALTER TABLE "servers" ADD COLUMN "allocation_alias" TEXT;
ALTER TABLE "servers" ADD COLUMN "allocation_port" INTEGER;
