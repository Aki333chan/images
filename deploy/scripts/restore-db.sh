#!/usr/bin/env bash
#
# Восстановление базы панели из дампа.
#
# ОПАСНАЯ ОПЕРАЦИЯ: перезаписывает содержимое базы панели.
# Pterodactyl и игровые сервера не затрагиваются, но данные панели
# (пользователи, тикеты, баны, аудит) будут заменены содержимым дампа.
#
#   sudo -u aurum ./restore-db.sh /var/backups/aurum-panel/aurum_panel_2026-08-15_04-20-00.dump

set -euo pipefail

dump="${1:-}"
PGDATABASE="${PGDATABASE:-aurum_panel}"

if [ -z "$dump" ]; then
  echo "Использование: $0 <файл.dump>" >&2
  echo "Доступные дампы:" >&2
  ls -lh /var/backups/aurum-panel/*.dump 2>/dev/null >&2 || echo "  (каталог пуст)" >&2
  exit 1
fi

if [ ! -f "$dump" ]; then
  echo "Файл не найден: $dump" >&2
  exit 1
fi

if ! pg_restore --list "$dump" > /dev/null 2>&1; then
  echo "Файл не похож на дамп pg_dump -Fc: $dump" >&2
  exit 1
fi

cat <<EOF

Восстановление базы «${PGDATABASE}» из ${dump}
Текущее содержимое базы панели будет ЗАМЕНЕНО.

Перед продолжением остановите API, чтобы он не писал в базу во время наката:
  sudo systemctl stop aurum-api

EOF

read -r -p "Введите 'восстановить' для продолжения: " answer
if [ "$answer" != "восстановить" ]; then
  echo "Отменено, изменений нет."
  exit 0
fi

# --clean --if-exists удаляет объекты перед накатом, --single-transaction
# гарантирует «всё или ничего»: при ошибке база остаётся как была.
pg_restore \
  --dbname="$PGDATABASE" \
  --clean --if-exists \
  --single-transaction \
  --no-owner --no-privileges \
  "$dump"

echo
echo "Готово. Запустите API обратно:  sudo systemctl start aurum-api"
echo "И проверьте:  curl -s localhost:3001/api/health/ready"
