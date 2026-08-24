/**
 * Modded-серверы Minecraft: Forge и NeoForge.
 *
 * ЭТО ДВА РАЗНЫХ ЗАГРУЗЧИКА, А НЕ ОДИН С ДВУМЯ ИМЕНАМИ, и разделение здесь —
 * не аккуратность ради аккуратности:
 *
 *   В 2023 году NeoForge отделился от Forge форком — по причинам управления
 *   проектом, а не техническим. Но начиная с Minecraft 1.20.2 NeoForge
 *   переименовал внутренние пакеты из net.minecraftforge.* в net.neoforged.*,
 *   и с этого момента моды одного загрузчика на другом просто НЕ ЗАГРУЖАЮТСЯ:
 *   мод собран против классов, которых на соседнем загрузчике не существует.
 *   Это отказ при старте, а не «работает похуже».
 *
 *   Единственное пересечение — 1.20.1: там NeoForge ещё сохранял старую
 *   структуру пакетов и грузил моды Forge. Это последняя такая версия.
 *
 * Отсюда следствие для панели: сервер объявляет ОДИН загрузчик, и предлагать
 * ему моды соседнего — значит предлагать заведомо неработающее. Поэтому
 * модуля два, с раздельными правами: доступ к Forge-серверу не должен
 * выдаваться правом от NeoForge-сервера и наоборот.
 *
 * Проверено по первоисточникам: официальная документация NeoForged
 * (репозиторий neoforged/Documentation, docs/gettingstarted/versioning.md)
 * и анонсы обоих проектов. См. раздел README про загрузчики.
 */

/** Идентификаторы модулей семейства Minecraft. */
export const MINECRAFT_MODULE_IDS = ['minecraft', 'minecraft-forge', 'minecraft-neoforge'] as const;
export type MinecraftModuleId = (typeof MINECRAFT_MODULE_IDS)[number];

/** Модули, где сервер собран на загрузчике модов, а не на ядре семейства Bukkit. */
export const MINECRAFT_LOADER_MODULE_IDS = ['minecraft-forge', 'minecraft-neoforge'] as const;
export type MinecraftLoaderModuleId = (typeof MINECRAFT_LOADER_MODULE_IDS)[number];

export function isMinecraftModule(moduleId: string): moduleId is MinecraftModuleId {
  return (MINECRAFT_MODULE_IDS as readonly string[]).includes(moduleId);
}

export function isMinecraftLoaderModule(moduleId: string): moduleId is MinecraftLoaderModuleId {
  return (MINECRAFT_LOADER_MODULE_IDS as readonly string[]).includes(moduleId);
}

/**
 * Права модуля-загрузчика.
 *
 * РАЗДЕЛЬНЫЕ КЛЮЧИ ДЛЯ КАЖДОГО МОДУЛЯ — по общей конвенции реестра
 * (`<id>.<action>`) и по смыслу: у ГМ должна быть возможность пустить
 * модератора на Forge-сервер, не пуская его на NeoForge-сервер. Один общий
 * набор ключей на оба лишил бы его этой возможности молча.
 *
 * Набор действий тот же, что у Paper-модуля, минус всё, что требует
 * companion-плагина Bukkit: инвентарь, права через LuckPerms и валюта через
 * Vault на загрузчиках модов не существуют в принципе.
 */
export function loaderPermissions(moduleId: MinecraftLoaderModuleId) {
  return {
    playersView: `${moduleId}.players.view`,
    kick: `${moduleId}.kick`,
    ban: `${moduleId}.ban`,
    pardon: `${moduleId}.ban.pardon`,
    whitelist: `${moduleId}.whitelist`,
    quickCommands: `${moduleId}.quick-commands`,
    commandRaw: `${moduleId}.command.raw`,
    configure: `${moduleId}.configure`,
  } as const;
}

export const MINECRAFT_FORGE_PERMISSIONS = loaderPermissions('minecraft-forge');
export const MINECRAFT_NEOFORGE_PERMISSIONS = loaderPermissions('minecraft-neoforge');

/** Как загрузчик называется в интерфейсе. */
export const LOADER_LABELS: Record<MinecraftLoaderModuleId, string> = {
  'minecraft-forge': 'Forge',
  'minecraft-neoforge': 'NeoForge',
};

/**
 * Папка модов на сервере — одна и та же у обоих загрузчиков.
 *
 * Совпадение имени папки НЕ означает совместимости содержимого: mods/ читают
 * оба, но каждый — только свои jar-файлы. Ровно эта деталь и создаёт ложное
 * ощущение, что «загрузчик неважен, лишь бы папка та же».
 */
export const MODS_DIR = 'mods';
