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

# CSP. Два места здесь зависят от вашей установки:
#
#   img-src     — аватары игроков грузятся с crafatar.com.
#   connect-src — консоль сервера открывает WebSocket НАПРЯМУЮ к Wings, а это
#                 другой домен (узел Pterodactyl), не наш. С одним 'self'
#                 браузер такое соединение запрещает, и вкладка «Консоль»
#                 показывает «Не удалось подключиться к консоли Wings» без
#                 каких-либо следов в логах сервера — причина видна только в
#                 консоли браузера (F12).
#
# ВАЖНО: в CSP порт — часть источника. wss://node.aurumgg.ovh разрешает
# только порт 443; если Wings отвечает на 8080, нужен отдельный источник с
# портом. Ниже перечислены оба варианта — лишний просто ни на что не влияет.
#
# Какой адрес у вас на самом деле, показывает шаг 7.4 в DEPLOY.md.
# Поменяли — не забудьте: sudo nginx -t && sudo systemctl reload nginx
add_header Content-Security-Policy "default-src 'self'; img-src 'self' data: https://crafatar.com; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self' wss://node.aurumgg.ovh wss://node.aurumgg.ovh:8080; frame-ancestors 'none'" always;
