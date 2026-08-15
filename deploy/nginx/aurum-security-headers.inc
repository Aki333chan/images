# Заголовки безопасности для manage.aurumgg.ovh.
#
# Кладётся как /etc/nginx/conf.d/aurum-security-headers.inc — расширение
# .inc намеренно: nginx автоматически подключает из conf.d только *.conf,
# а этот файл должен подключаться вручную директивой include.
#
# ПОЧЕМУ ОТДЕЛЬНЫМ ФАЙЛОМ, А НЕ ОДИН РАЗ В server-БЛОКЕ.
# В nginx add_header наследуется с верхнего уровня в location ТОЛЬКО тогда,
# когда в самом location нет ни одного своего add_header. Достаточно
# добавить в location одну строку add_header (например, Cache-Control), и
# все заголовки, заданные уровнем выше, для этого location молча пропадают.
# Ошибка не видна ни в nginx -t, ни в логах — только в ответе сервера.
# Поэтому набор вынесен сюда и подключается в каждый location, у которого
# есть собственные add_header.
#
# Проверить, что заголовки на месте:
#   curl -sI https://manage.aurumgg.ovh/ | grep -i -E 'strict-transport|content-security|x-frame'

add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "same-origin" always;

# Аватары игроков грузятся с crafatar.com — иначе их заблокирует CSP.
add_header Content-Security-Policy "default-src 'self'; img-src 'self' data: https://crafatar.com; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'" always;
