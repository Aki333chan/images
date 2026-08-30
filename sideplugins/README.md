# GladiatorArena 1.1.0

Плагин гладиаторских арен со ставками для Paper/Spigot 26.2 и Java 25.

## Обновление с 1.01/1.05

Замените JAR при выключенном сервере и сохраните папку `plugins/GladiatorArena` как резервную копию. Старые секции `arenas` в `config.yml` и комплекты из `kits.yml` читаются без пересоздания. Новые параметры автоматически дописываются из стандартного конфига.

## MariaDB из Pterodactyl

В `plugins/GladiatorArena/config.yml`:

```yaml
database:
  type: mariadb
  mariadb:
    jdbc_url: ""
    host: 127.0.0.1
    port: 3306
    database: gladiatorarena
    username: gladiatorarena
    password: "сложный_пароль"
    use_ssl: false
    pool_size: 5
    connection_timeout_ms: 10000
```

Возьмите `host`, `port`, имя базы, пользователя и пароль на вкладке Databases панели Pterodactyl. Если панель выдаёт готовый нестандартный JDBC URL, внесите его в `jdbc_url`; тогда отдельные `host`, `port`, `database` и `use_ssl` не используются. Плагин сам создаёт таблицу `gladiator_stats`.

При ошибке MariaDB статистика временно отключается и причина пишется в консоль. Плагин намеренно не переключается молча на SQLite: иначе два источника статистики разойдутся. Матчи, ставки, резервные копии инвентарей и ожидающие выплаты при этом остаются защищены локальным `recovery.yml`.

Для автономного режима:

```yaml
database:
  type: sqlite
  sqlite:
    file: statistics.db
```

## Экономика

`economy.use_vault: true` включает Vault/VaultUnlocked. Если провайдер экономики не зарегистрирован, платежи блокируются без перехода на золото. Результат каждой операции Vault проверяется; неудачные и офлайн-выплаты сохраняются до следующего входа игрока.

При `use_vault: false` используются `main_currency` и `sub_currency` с отношением 1:10.

## Основные команды

- `/arena create <имя>`, `/arena delete <имя>`, `/arena status [имя]`, `/arena validate [имя]`
- `/arena gui [имя]`, `/arena start`, `/arena stop`, `/arena reload`
- `/arena setred`, `setblue`, `sethost`, `setreset`, `sethopred`, `sethopblue`, `setfhop`, `bankomat`
- `/arena setspawn`, `spawnred1`, `spawnred2`, `spawnblue1`, `spawnblue2`
- `/arena auto`, `manual`, `betting <true|false>`, `kit <true|false>`, `friendlyfire <true|false>`
- `/arena maxplayers <число>`, `/arena radius <число>`, `/arena showbar <all|spectators|false>`
- `/arena odds [арена]`, `/arena stats [игрок]`
- `/arena spectate <арена>`, `/arena leave`
- `/arena final`, `finalstats`, `fstatsremove`, `fstatsscale <0.1..5>`
- `/arena debug hologram` удаляет ближайшую голограмму именно GladiatorArena.

Все команды имеют контекстное автодополнение. Административные команды требуют `arena.admin`, наблюдение — `arena.spectate`, обычное использование — `arena.use`.

## Наблюдатели

Перед входом сохраняются координаты, GameMode и параметры полёта. `/arena leave`, выход с сервера, смена GameMode, телепортация или выход за сферическую границу арены завершают наблюдение и возвращают исходное состояние. Незавершённая сессия восстанавливается после аварийного рестарта.

## PlaceholderAPI

- `%gladiatorarena_arena%`, `%gladiatorarena_state%`
- `%gladiatorarena_red_players%`, `%gladiatorarena_blue_players%`
- `%gladiatorarena_red_bets%`, `%gladiatorarena_blue_bets%`, `%gladiatorarena_total_bets%`
- `%gladiatorarena_wins%`, `%gladiatorarena_losses%`, `%gladiatorarena_streak%`, `%gladiatorarena_best_streak%`, `%gladiatorarena_earnings%`

PlaceholderAPI опционален. SQLite, MariaDB Connector/J и HikariCP уже включены в JAR.
