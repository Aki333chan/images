-- Состояние учётной записи. pending_approval — создана Админом и ждёт
-- решения ГМ: вход невозможен, письмо с паролем не отправлено.
CREATE TYPE "UserStatus" AS ENUM ('active', 'pending_approval', 'rejected');

-- Ник СОТРУДНИКА панели (для внутренних сообщений и аудита). К нику игрока
-- в Minecraft отношения не имеет. NULL, пока не пройден онбординг.
ALTER TABLE "users" ADD COLUMN "nickname" TEXT;
ALTER TABLE "users" ADD COLUMN "status" "UserStatus" NOT NULL DEFAULT 'active';
ALTER TABLE "users" ADD COLUMN "created_by_id" TEXT;
-- Пароль одноразовый: до смены пускаем только на экран онбординга.
ALTER TABLE "users" ADD COLUMN "must_change_password" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "users" ADD COLUMN "password_expires_at" TIMESTAMP(3);

CREATE UNIQUE INDEX "users_nickname_key" ON "users"("nickname");
CREATE INDEX "users_status_idx" ON "users"("status");

-- Личная переписка сотрудников. Приватность обеспечивается запросами:
-- выборка всегда ограничена участием текущего пользователя.
CREATE TABLE "staff_messages" (
    "id" TEXT NOT NULL,
    "from_user_id" TEXT NOT NULL,
    "to_user_id" TEXT NOT NULL,
    "text" TEXT NOT NULL,
    "read_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "staff_messages_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "staff_messages_to_user_id_read_at_idx" ON "staff_messages"("to_user_id", "read_at");
CREATE INDEX "staff_messages_from_user_id_to_user_id_created_at_idx" ON "staff_messages"("from_user_id", "to_user_id", "created_at");

ALTER TABLE "staff_messages" ADD CONSTRAINT "staff_messages_from_user_id_fkey"
    FOREIGN KEY ("from_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "staff_messages" ADD CONSTRAINT "staff_messages_to_user_id_fkey"
    FOREIGN KEY ("to_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Настройки панели, не являющиеся секретами (секреты — в integration_secrets).
CREATE TABLE "app_settings" (
    "key" TEXT NOT NULL,
    "value" TEXT NOT NULL,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "app_settings_pkey" PRIMARY KEY ("key")
);
