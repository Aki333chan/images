#!/usr/bin/env bash
#
# Бэкап PostgreSQL панели Aurum.
#
# Касается ТОЛЬКО базы панели. MariaDB Pterodactyl (домашний сервер) —
# отдельная система, её этот скрипт не видит и не трогает.
#
# Переменные берутся из /etc/aurum-panel/backup.env:
#   PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
#   BACKUP_DIR      куда складывать (по умолчанию /var/backups/aurum-panel)
#   KEEP_DAYS       сколько дней хранить (по умолчанию 14)
#
# Запуск вручную:  sudo -u aurum BACKUP_DIR=/tmp/test ./backup-db.sh

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/aurum-panel}"
KEEP_DAYS="${KEEP_DAYS:-14}"
PGDATABASE="${PGDATABASE:-aurum_panel}"

timestamp="$(date +%Y-%m-%d_%H-%M-%S)"
target="${BACKUP_DIR}/${PGDATABASE}_${timestamp}.dump"
# Пишем во временный файл: если pg_dump упадёт на середине, в каталоге
# не останется обрезанного дампа, который выглядит как валидный.
temp="${target}.part"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

echo "Бэкап базы ${PGDATABASE} -> ${target}"

# -Fc — custom format: сжатый и пригодный для pg_restore с выборочным
# восстановлением отдельных таблиц.
if ! pg_dump --format=custom --compress=6 --file="$temp" "$PGDATABASE"; then
  rm -f "$temp"
  echo "ОШИБКА: pg_dump не отработал, дамп не создан" >&2
  exit 1
fi

# Проверяем, что дамп читается, прежде чем считать его хорошим.
if ! pg_restore --list "$temp" > /dev/null 2>&1; then
  rm -f "$temp"
  echo "ОШИБКА: получившийся дамп не читается pg_restore — он выброшен" >&2
  exit 1
fi

mv "$temp" "$target"
chmod 600 "$target"

size="$(du -h "$target" | cut -f1)"
echo "Готово: ${target} (${size})"

# Чистим старое — только свои дампы, по маске имени базы.
deleted="$(find "$BACKUP_DIR" -maxdepth 1 -name "${PGDATABASE}_*.dump" -type f -mtime "+${KEEP_DAYS}" -print -delete | wc -l)"
echo "Удалено дампов старше ${KEEP_DAYS} дней: ${deleted}"

remaining="$(find "$BACKUP_DIR" -maxdepth 1 -name "${PGDATABASE}_*.dump" -type f | wc -l)"
echo "Всего дампов в каталоге: ${remaining}"

# Если дампов вдруг не осталось — это повод для тревоги в журнале.
if [ "$remaining" -eq 0 ]; then
  echo "ВНИМАНИЕ: после чистки не осталось ни одного дампа" >&2
  exit 1
fi
