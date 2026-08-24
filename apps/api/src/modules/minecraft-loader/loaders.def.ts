import {
  LOADER_LABELS,
  loaderPermissions,
  type GameModuleManifest,
  type MinecraftLoaderModuleId,
} from '@aurum/shared';
import type { BackendGameModule } from '../module-registry';
import { MinecraftLoaderModule } from './minecraft-loader.module';

/**
 * Модули серверов Minecraft на загрузчиках модов: Forge и NeoForge.
 *
 * ПОЧЕМУ ИХ ДВА, А НЕ ОДИН «Minecraft (Modded)». NeoForge отделился от Forge
 * форком в 2023 году, а с Minecraft 1.20.2 переименовал внутренние пакеты
 * (net.minecraftforge.* -> net.neoforged.*). С этого момента мод, собранный
 * под один загрузчик, на другом НЕ ЗАГРУЖАЕТСЯ: он скомпилирован против
 * классов, которых там нет. Единственное пересечение — 1.20.1, где NeoForge
 * ещё сохранял старую структуру пакетов.
 *
 * Один общий модуль означал бы общие права и общий набор модов для двух
 * несовместимых платформ — то есть панель предлагала бы человеку заведомо
 * неработающее и не давала бы развести доступы. Поэтому модуля два.
 *
 * ЧТО ОБЩЕГО У НИХ С Paper. Ровно то, что даёт сам сервер Minecraft: RCON и
 * его команды. RCON — часть базового сервера, а не ядра Bukkit и не
 * загрузчика, поэтому список игроков, кик, бан и whitelist работают
 * одинаково и обслуживаются общим кодом (см. minecraft-shared).
 *
 * ЧЕГО У НИХ НЕТ. Инвентаря, тикетов из игры, прав через LuckPerms и валюты
 * через Vault: всё это работа companion-плагина Bukkit, которого на Forge и
 * NeoForge не существует. В capabilities они честно выключены, а не помечены
 * 'requires-plugin': плагина, который бы их включил, здесь не бывает в
 * принципе. Аналог companion под загрузчики — отдельная большая работа.
 */
function loaderManifest(id: MinecraftLoaderModuleId): GameModuleManifest {
  const p = loaderPermissions(id);
  const label = LOADER_LABELS[id];

  return {
    id,
    displayName: `Minecraft (${label})`,
    capabilities: {
      // Консоль берётся из ядра (WebSocket Pterodactyl) — модуль лишь
      // объявляет возможность, вкладку рендерит общий компонент.
      console: true,
      playerList: true,
      banKick: true,
      whitelist: true,
      quickCommands: true,
      // inventory и tickets НЕ ПЕРЕЧИСЛЕНЫ — так в этом контракте и
      // выражается «возможности нет»: CapabilityState это true либо
      // 'requires-plugin', значения false в нём нет вовсе. Отсутствие ключа
      // читается однозначно, а `false` пришлось бы отличать от «забыли
      // дописать». За обеими возможностями стоит companion-плагин Bukkit,
      // которого на загрузчиках модов не существует; аналог под них —
      // отдельная большая работа.
    },
    permissions: [
      {
        key: p.playersView,
        description: `Просмотр списка игроков онлайн (${label})`,
        defaultRoles: ['ADMIN', 'MODERATOR'],
      },
      { key: p.kick, description: `Кик игрока (${label})`, defaultRoles: ['ADMIN', 'MODERATOR'] },
      {
        key: p.ban,
        description: `Бан игрока и просмотр списка банов (${label})`,
        defaultRoles: ['ADMIN', 'MODERATOR'],
      },
      { key: p.pardon, description: `Снятие бана (${label})`, defaultRoles: ['ADMIN'] },
      { key: p.whitelist, description: `Управление белым списком (${label})`, defaultRoles: ['ADMIN'] },
      {
        key: p.quickCommands,
        description: `Запуск быстрых команд (${label})`,
        defaultRoles: ['ADMIN', 'MODERATOR'],
      },
      {
        // Произвольная команда — это всё, что умеет консоль сервера,
        // включая op и stop. Модератору не даём, как и на Paper.
        key: p.commandRaw,
        description: `Произвольная RCON-команда (${label})`,
        defaultRoles: ['ADMIN'],
      },
      {
        // Пустой список — право есть только у ГМ: здесь задаётся RCON-пароль.
        key: p.configure,
        description: `Настройка подключения к игровому серверу (${label})`,
        defaultRoles: [],
      },
    ],
  };
}

export const minecraftForgeManifest = loaderManifest('minecraft-forge');
export const minecraftNeoForgeManifest = loaderManifest('minecraft-neoforge');

export const minecraftForgeModule: BackendGameModule = {
  manifest: minecraftForgeManifest,
  nestModule: MinecraftLoaderModule,
};

export const minecraftNeoForgeModule: BackendGameModule = {
  manifest: minecraftNeoForgeManifest,
  // Тот же NestJS-модуль: контроллеры у загрузчиков разные, а контейнер
  // зависимостей общий — см. пояснение в minecraft-loader.module.ts.
  nestModule: MinecraftLoaderModule,
};
