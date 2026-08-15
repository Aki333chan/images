-- CreateTable
CREATE TABLE "mod_minecraft_bans" (
    "id" TEXT NOT NULL,
    "server_id" TEXT NOT NULL,
    "player_name" TEXT NOT NULL,
    "player_uuid" TEXT,
    "reason" TEXT NOT NULL,
    "expires_at" TIMESTAMP(3),
    "created_by_id" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "pardoned_at" TIMESTAMP(3),
    "pardoned_by_id" TEXT,

    CONSTRAINT "mod_minecraft_bans_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "mod_minecraft_bans_server_id_pardoned_at_idx" ON "mod_minecraft_bans"("server_id", "pardoned_at");

-- CreateIndex
CREATE INDEX "mod_minecraft_bans_pardoned_at_expires_at_idx" ON "mod_minecraft_bans"("pardoned_at", "expires_at");

-- AddForeignKey
ALTER TABLE "mod_minecraft_bans" ADD CONSTRAINT "mod_minecraft_bans_server_id_fkey" FOREIGN KEY ("server_id") REFERENCES "servers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

