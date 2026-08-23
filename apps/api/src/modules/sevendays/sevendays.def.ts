import { SEVENDAYS_PERMISSIONS, type GameModuleManifest } from '@aurum/shared';
import type { BackendGameModule } from '../module-registry';
import { SevenDaysModule } from './sevendays.module';

/**
 * Манифест модуля 7 Days to Die.
 *
 * ПРО ПРОТОКОЛ. Игра НЕ поддерживает Source RCON, и общий RCON-транспорт
 * Minecraft здесь переиспользовать нельзя — он бы просто не подключился.
 * В serverconfig.xml, который поставляется вместе с сервером, нет ни одного
 * свойства со словом rcon. Удалённое администрирование — это встроенный
 * telnet: TelnetEnabled (по умолчанию true), TelnetPort (8081),
 * TelnetPassword (пустой — тогда сервер слушает только loopback). Второй
 * интерфейс, WebDashboard на 8080, по умолчанию выключен и сделан для
 * просмотра, а не для управления. «RCON» в документации хостеров 7 Days to
 * Die означает то же самое telnet-подключение, названное привычным словом.
 *
 * Разница не косметическая: у RCON есть длина пакета и номер запроса, по
 * которым ответ однозначно сопоставляется с командой, а у telnet — ничего,
 * да ещё и живой лог сервера идёт в тот же поток. Отсюда отдельный
 * транспорт с выделением кадра по отметкам сервера (telnet/telnet-client.ts).
 *
 * ПРО CAPABILITIES — что доступно честно:
 *
 *   console      — есть. Это возможность ЯДРА: WebSocket-консоль Wings
 *                  через Pterodactyl, она не зависит от игры и от telnet.
 *   playerList   — есть. `lp` отдаёт ник, оба идентификатора, координаты,
 *                  здоровье, смерти, убитых зомби, уровень и пинг.
 *   banKick      — есть. `kick`, `ban add`, `ban remove`, и, в отличие от
 *                  Palworld, `ban list` — список банов ведёт сам сервер,
 *                  поэтому своей таблицы и миграций у модуля нет.
 *   whitelist    — есть, и это редкость: в 7 Days to Die белый список
 *                  работает без модов (`whitelist add/remove/list`).
 *   quickCommands— есть, закрытым набором: объявление, сохранение мира,
 *                  остановка. Произвольная команда доступна через консоль
 *                  ядра, поэтому дублировать её здесь незачем.
 *
 * Чего НЕТ и почему:
 *
 *   inventory    — ванильный сервер не отдаёт содержимое рюкзака никакой
 *                  командой консоли, а telnet — единственный канал.
 *   tickets      — тикет заводит игрок командой в игре; для этого нужен
 *                  обратный канал из игры в панель. Telnet его не даёт:
 *                  это чтение и команды, но не события.
 *
 * ВАЖНАЯ ОГОВОРКА про обе строчки выше: они верны для ВАНИЛЬНОГО сервера,
 * и только. Серверные моды в 7 Days to Die существуют — TFP сама
 * поставляет загрузчик 0_TFP_Harmony, и на нём живёт, например,
 * 7DaysToDie-ServerKit (MIT), который поднимает внутри процесса игры REST
 * и WebSocket: инвентарь (рюкзак, пояс, экипировка, в том числе у
 * офлайновых игроков) и события чата оттуда доступны.
 *
 * То есть обе возможности достижимы как 'requires-plugin' — ровно так же,
 * как инвентарь и тикеты у Minecraft держатся на companion-плагине. Здесь
 * они не объявлены не потому, что «нечем», а потому, что модуль намеренно
 * работает на голом сервере без модов: заявить capability, которая молча
 * не работает без стороннего мода, хуже, чем её не заявить.
 */
export const sevenDaysManifest: GameModuleManifest = {
  id: 'sevendays',
  displayName: '7 Days to Die',
  capabilities: {
    console: true,
    playerList: true,
    banKick: true,
    whitelist: true,
    quickCommands: true,
  },
  permissions: [
    {
      key: SEVENDAYS_PERMISSIONS.playersView,
      description: 'Просмотр игроков онлайн и состояния сервера',
      defaultRoles: ['ADMIN', 'MODERATOR', 'VIEWER'],
    },
    {
      key: SEVENDAYS_PERMISSIONS.kick,
      description: 'Кик игрока',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: SEVENDAYS_PERMISSIONS.ban,
      description: 'Бан игрока и просмотр списка банов',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: SEVENDAYS_PERMISSIONS.pardon,
      description: 'Снятие бана',
      defaultRoles: ['ADMIN'],
    },
    {
      // Белый список закрывает сервер для всех, кого в нём нет, — это
      // рычаг уровня «выключить сервер», а не рядовая модерация.
      key: SEVENDAYS_PERMISSIONS.whitelist,
      description: 'Управление белым списком',
      defaultRoles: ['ADMIN'],
    },
    {
      key: SEVENDAYS_PERMISSIONS.quickActions,
      description: 'Объявления в чат и сохранение мира',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Отложенной остановки в игре нет: сервер выключается сразу.
      // Тем более отдельным правом, и модератору его не даём.
      key: SEVENDAYS_PERMISSIONS.shutdown,
      description: 'Остановка игрового сервера командой консоли',
      defaultRoles: ['ADMIN'],
    },
    {
      // Пустой список — право только у ГМ: здесь задаётся пароль консоли.
      key: SEVENDAYS_PERMISSIONS.configure,
      description: 'Настройка подключения к telnet-консоли',
      defaultRoles: [],
    },
  ],
};

export const sevenDaysModule: BackendGameModule = {
  manifest: sevenDaysManifest,
  nestModule: SevenDaysModule,
};
