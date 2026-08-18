-- Ник становится единственным именем сотрудника: display_name уходит.
--
-- Порядок важен: сначала спасаем имена тех, у кого ника ещё нет, и только
-- потом удаляем колонку. Иначе владелец панели (он заводится сидом и первый
-- вход не проходит) остался бы вообще без имени и пропал бы из списка
-- адресатов внутренних сообщений — тот самый симптом, ради которого это и
-- делается.

ALTER TABLE "users"
  ADD COLUMN "nickname_change_allowed" BOOLEAN NOT NULL DEFAULT false;

-- Владельцу — ник GM, если он свободен. Только владельцу: остальные, кто ещё
-- не входил, выберут ник сами при первом входе, и назначать за них не нужно.
UPDATE "users"
   SET "nickname" = 'GM'
 WHERE "role" = 'OWNER'
   AND "nickname" IS NULL
   AND NOT EXISTS (SELECT 1 FROM "users" u2 WHERE lower(u2."nickname") = 'gm');

-- Если 'GM' уже занят (или владельцев несколько), добираем из display_name,
-- а при конфликте — из части email до собаки с числовым хвостом.
UPDATE "users" u
   SET "nickname" = sub."candidate"
  FROM (
    SELECT "id",
           left(regexp_replace("display_name", '[^[:alnum:] _-]', '', 'g'), 31) AS "candidate"
      FROM "users"
     WHERE "role" = 'OWNER' AND "nickname" IS NULL
  ) sub
 WHERE u."id" = sub."id"
   AND length(sub."candidate") >= 2
   AND NOT EXISTS (
     SELECT 1 FROM "users" u2 WHERE lower(u2."nickname") = lower(sub."candidate")
   );

ALTER TABLE "users" DROP COLUMN "display_name";
