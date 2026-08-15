#!/usr/bin/env bash
#
# Аудит состояния перед деплоем. СКРИПТ НИЧЕГО НЕ МЕНЯЕТ — только читает.
#
# Запустить на ОБЕИХ машинах и прислать вывод:
#   sudo bash deploy/audit.sh > audit-vds.txt        # на VDS (10.0.0.1)
#   sudo bash deploy/audit.sh > audit-home.txt       # на домашнем (10.0.0.2)
#
# Все команды здесь read-only: show/status/list/cat. Ни одной, которая
# добавляет правило, правит конфиг или перезапускает сервис.

set -uo pipefail

section() {
  printf '\n\n═══ %s ═══\n' "$1"
}

run() {
  printf '\n$ %s\n' "$*"
  # Ошибка одной команды не должна прерывать аудит: где-то утилиты просто нет.
  "$@" 2>&1 || printf '(команда завершилась с кодом %s — возможно, не установлена)\n' "$?"
}

printf 'Аудит от %s на %s\n' "$(date -Is)" "$(hostname)"

section "Система"
run uname -a
run lsb_release -a
run uptime
run free -h
run df -h /

section "WireGuard"
# Ключи в выводе wg show не показываются целиком, но на всякий случай
# приватный ключ интерфейса скрываем.
run wg show
run ip -brief address
run ip route

section "Фаервол"
run ufw status verbose
run ufw status numbered
printf '\n(если ufw не установлен — смотрим напрямую iptables)\n'
run iptables -L -n -v
run iptables -t nat -L -n -v

section "Открытые порты (кто что слушает)"
run ss -tulpn

section "Systemd: что запущено"
run systemctl list-units --type=service --state=running --no-pager

section "Реверс-прокси"
run nginx -v
run nginx -t
printf '\n--- содержимое sites-enabled ---\n'
for f in /etc/nginx/sites-enabled/*; do
  [ -e "$f" ] || continue
  printf '\n===== %s =====\n' "$f"
  cat "$f"
done
printf '\n--- conf.d ---\n'
for f in /etc/nginx/conf.d/*.conf; do
  [ -e "$f" ] || continue
  printf '\n===== %s =====\n' "$f"
  cat "$f"
done

section "Сертификаты"
run certbot certificates
run ls -la /etc/letsencrypt/live/
run systemctl list-timers --no-pager

section "Что уже установлено из нужного нам"
run node --version
run npm --version
run psql --version
run systemctl is-active postgresql
run redis-server --version
run systemctl is-active redis-server
run redis-cli -h 127.0.0.1 -p 6379 ping
printf '\n(если Redis отвечает PONG — он занят Pterodactyl, см. DEPLOY.md про второй инстанс)\n'
run php --version
run docker ps

section "Pterodactyl (не трогаем, только смотрим)"
run systemctl status pteroq --no-pager -l
run systemctl status wings --no-pager -l
printf '\n--- .env Pterodactyl: только НЕсекретные ключи ---\n'
if [ -f /var/www/pterodactyl/.env ]; then
  grep -E '^(APP_URL|APP_ENV|DB_HOST|DB_PORT|DB_DATABASE|CACHE_DRIVER|SESSION_DRIVER|QUEUE_CONNECTION|REDIS_HOST|REDIS_PORT)=' \
    /var/www/pterodactyl/.env 2>&1
else
  printf '(файл /var/www/pterodactyl/.env не найден — возможно, панель в другом каталоге)\n'
fi

section "Готово"
printf 'Отправьте этот вывод целиком. Секретов здесь нет: пароли и ключи не выводятся.\n'
