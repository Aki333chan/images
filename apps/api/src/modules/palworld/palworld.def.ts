import { PALWORLD_PERMISSIONS, type GameModuleManifest } from '@aurum/shared';
import type { BackendGameModule } from '../module-registry';
import { PalworldModule } from './palworld.module';

/**
 * Манифест модуля Palworld.
 *
 * ПРО ПРОТОКОЛ. Palworld поддерживает Source RCON — тот же, что Minecraft, —
 * но Pocketpair пометил его устаревшим и объявил, что в одном из следующих
 * обновлений он перестанет работать, рекомендуя вместо него собственный
 * REST API. Поэтому модуль построен на REST (HTTP + JSON, Basic-авторизация,
 * порт 8212 по умолчанию), а не на общем RCON-транспорте: строить на
 * транспорте с объявленной датой отключения означало бы переписывать модуль
 * через несколько месяцев.
 *
 * ПРО CAPABILITIES — что доступно честно:
 *
 *   console      — есть. Это возможность ЯДРА: WebSocket-консоль Wings
 *                  через Pterodactyl, она не зависит от игры.
 *   playerList   — есть. GET /v1/api/players отдаёт имя, userId, пинг,
 *                  уровень и координаты — больше, чем даёт RCON.
 *   banKick      — есть. POST /kick, /ban, /unban. Список банов ведёт сама
 *                  панель: отдавать его API не умеет.
 *   quickCommands— есть, но не как в Minecraft: произвольную команду
 *                  выполнить нельзя, набор действий закрыт самим API
 *                  (объявление, сохранение мира, остановка с предупреждением).
 *
 * Чего НЕТ и почему — не «не успели», а нечем:
 *
 *   whitelist    — в Palworld нет ни команды, ни эндпоинта белого списка.
 *                  Есть только BanListURL — внешний список банов, а это
 *                  другое.
 *   inventory    — инвентарь игрока сервер наружу не отдаёт никак.
 *   tickets      — тикеты заводит игрок командой в игре; у Palworld нет ни
 *                  плагинов, ни хуков чата, поэтому обратного канала из
 *                  игры в панель не существует.
 */
export const palworldManifest: GameModuleManifest = {
  id: 'palworld',
  displayName: 'Palworld',
  capabilities: {
    console: true,
    playerList: true,
    banKick: true,
    quickCommands: true,
  },
  permissions: [
    {
      key: PALWORLD_PERMISSIONS.playersView,
      description: 'Просмотр игроков онлайн и состояния сервера',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: PALWORLD_PERMISSIONS.kick,
      description: 'Кик игрока',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: PALWORLD_PERMISSIONS.ban,
      description: 'Бан игрока и просмотр списка банов',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      key: PALWORLD_PERMISSIONS.pardon,
      description: 'Снятие бана',
      defaultRoles: ['ADMIN'],
    },
    {
      key: PALWORLD_PERMISSIONS.quickActions,
      description: 'Объявления в чат и сохранение мира',
      defaultRoles: ['ADMIN', 'MODERATOR'],
    },
    {
      // Выключение сервера — не рядовое действие: отдельным правом, и
      // модератору его не даём.
      key: PALWORLD_PERMISSIONS.shutdown,
      description: 'Остановка сервера с предупреждением игроков',
      defaultRoles: ['ADMIN'],
    },
    {
      // Пустой список — право только у ГМ: здесь задаётся пароль
      // администратора игрового сервера.
      key: PALWORLD_PERMISSIONS.configure,
      description: 'Настройка подключения к REST API Palworld',
      defaultRoles: [],
    },
  ],
};

export const palworldModule: BackendGameModule = {
  manifest: palworldManifest,
  nestModule: PalworldModule,
};
