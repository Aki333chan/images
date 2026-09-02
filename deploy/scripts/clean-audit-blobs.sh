#!/usr/bin/env bash
#
# Убрать из журнала аудита раздутые записи, оставшиеся от загрузки файлов.
#
# ЗАЧЕМ. До исправления интерцептор аудита разворачивал двоичное тело запроса
# по байтам: загруженный файл в пятнадцать мегабайт превращался в объект на
# 15 728 640 ключей и уезжал в metadata как ~184 МБ JSON. Новые записи такими
# уже не будут, но старые лежат в базе: они раздувают её, тормозят страницу
# «Аудит» и показываются там сплошной простынёй символов.
#
# ЧТО ДЕЛАЕТ. У раздутых записей заменяет ТОЛЬКО metadata на короткую пометку.
# Сами записи остаются: кто, что и когда сделал — это и есть журнал, и удалять
# его нельзя. Пропадает лишь содержимое файла, которого там и не должно было
# быть.
#
# Переменные — те же, что у backup-db.sh: PGHOST, PGPORT, PGUSER, PGPASSWORD,
# PGDATABASE.
#
# Запуск:
#   ./clean-audit-blobs.sh          показать, что будет затронуто
#   ./clean-audit-blobs.sh --apply  выполнить
#
# СНАЧАЛА СДЕЛАЙТЕ БЭКАП: ./backup-db.sh

set -euo pipefail

PGDATABASE="${PGDATABASE:-aurum_panel}"
# Порог: обычная запись аудита — это сотни байт. Всё, что крупнее ста
# килобайт, содержимым запроса быть не может.
THRESHOLD="${THRESHOLD:-102400}"
APPLY="${1:-}"

psql_run() {
  psql --dbname="$PGDATABASE" --no-psqlrc --quiet --tuples-only --no-align --command "$1"
}

echo "База: ${PGDATABASE}, порог: ${THRESHOLD} байт"
echo

summary="$(psql_run "
  SELECT count(*) || '|' || COALESCE(pg_size_pretty(sum(pg_column_size(metadata))), '0 bytes')
  FROM audit_log
  WHERE metadata IS NOT NULL AND pg_column_size(metadata) > ${THRESHOLD};
")"
count="${summary%%|*}"
size="${summary##*|}"

if [ "$count" = "0" ]; then
  echo "Раздутых записей нет — чистить нечего."
  exit 0
fi

echo "Найдено записей: ${count}, занимают: ${size}"
echo
echo "Из них (первые десять):"
psql --dbname="$PGDATABASE" --no-psqlrc --quiet --command "
  SELECT id, created_at, action, pg_size_pretty(pg_column_size(metadata)) AS размер
  FROM audit_log
  WHERE metadata IS NOT NULL AND pg_column_size(metadata) > ${THRESHOLD}
  ORDER BY pg_column_size(metadata) DESC
  LIMIT 10;
"

if [ "$APPLY" != "--apply" ]; then
  echo
  echo "Это был показ без изменений. Чтобы выполнить: $0 --apply"
  echo "Перед этим сделайте бэкап: ./backup-db.sh"
  exit 0
fi

echo
echo "Заменяю metadata у ${count} записей…"
psql_run "
  UPDATE audit_log
  SET metadata = jsonb_build_object(
        'body', '[двоичные данные, вычищены при обслуживании]',
        'note', 'Содержимое запроса удалено: до исправления сюда попадал весь файл целиком'
      )
  WHERE metadata IS NOT NULL AND pg_column_size(metadata) > ${THRESHOLD};
"

# VACUUM FULL здесь намеренно НЕ вызывается: он блокирует таблицу на всё время
# работы, а панель живая. Обычный VACUUM освободит место под будущие записи, и
# этого достаточно.
echo "Отпускаю место (VACUUM ANALYZE)…"
psql_run "VACUUM ANALYZE audit_log;"

echo "Готово. Записи журнала на месте, содержимое файлов из них убрано."
