#!/usr/bin/env bash
#
# Домашний сервер (10.0.0.2): открыть RCON и порт companion-плагина
# ТОЛЬКО для VDS (10.0.0.1).
#
# Скрипт ADDITIVE: он лишь добавляет правила `ufw allow from ...`.
# Здесь нет ни ufw reset, ни ufw disable, ни delete, ни правки default-политик —
# существующие правила (в том числе для Wings) остаются нетронутыми.
#
# Запуск:
#   sudo bash home-server-additive.sh --dry-run    # показать, что будет сделано
#   sudo bash home-server-additive.sh              # применить
#
# Откат (если понадобится) — удалить ровно эти правила:
#   sudo ufw status numbered      # найти номера
#   sudo ufw delete <номер>       # по одному, сверху вниз

set -euo pipefail

VDS_IP="10.0.0.1"

# Порты, которые нужно открыть навстречу панели.
# Правьте под свои сервера: по одному RCON-порту и по одному порту плагина
# на каждый игровой сервер.
declare -a RULES=(
  "25575|RCON сервера Выживание"
  "25576|RCON сервера Креатив"
  "8085|companion-плагин сервера Выживание"
  "8086|companion-плагин сервера Креатив"
)

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

if [ "$(id -u)" -ne 0 ]; then
  echo "Нужны права root: запустите через sudo" >&2
  exit 1
fi

if ! command -v ufw >/dev/null 2>&1; then
  echo "ufw не установлен. Проверьте вывод audit.sh: возможно, здесь чистый iptables." >&2
  exit 1
fi

echo "=== Текущее состояние (до изменений) ==="
ufw status verbose

echo
echo "=== Планируемые правила ==="
for rule in "${RULES[@]}"; do
  port="${rule%%|*}"
  comment="${rule##*|}"
  printf '  ufw allow from %s to any port %s proto tcp   # %s\n' "$VDS_IP" "$port" "$comment"
done

if [ "$DRY_RUN" -eq 1 ]; then
  echo
  echo "--dry-run: ничего не применено."
  exit 0
fi

echo
read -r -p "Применить эти правила? Введите 'да' для продолжения: " answer
if [ "$answer" != "да" ]; then
  echo "Отменено, изменений нет."
  exit 0
fi

for rule in "${RULES[@]}"; do
  port="${rule%%|*}"
  comment="${rule##*|}"
  # ufw идемпотентен: повторный allow не создаёт дубликат, а сообщает
  # «Skipping adding existing rule».
  ufw allow from "$VDS_IP" to any port "$port" proto tcp comment "$comment"
done

echo
echo "=== Состояние после изменений ==="
ufw status verbose

cat <<'EOF'

Проверьте, что:
  1. Прежние правила (Wings, SSH, игровые порты) на месте — сравните с выводом выше.
  2. Игроки по-прежнему заходят на сервера.
  3. С VDS открывается порт:  nc -vz 10.0.0.2 25575

Наружу (в интернет) эти порты по-прежнему закрыты: правило разрешает
подключение только с адреса 10.0.0.1 внутри туннеля.
EOF
