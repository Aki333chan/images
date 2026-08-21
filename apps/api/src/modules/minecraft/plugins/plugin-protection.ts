import { KNOWN_PLUGINS } from '@aurum/shared';

/** Имя companion-плагина: его самого выключать и сносить нельзя. */
export const COMPANION_PLUGIN = 'AurumCompanion';

/**
 * Плагины, которые панель из себя выключить не даёт.
 *
 * Это не «важные вообще» — это те, на которых панель СТОИТ. Companion —
 * единственный канал к серверу помимо RCON: выключив его, панель теряет
 * инвентари, экономику, список плагинов и возможность включить его обратно.
 * Остальные из KNOWN_PLUGINS держат вкладки и кнопки: без LuckPerms исчезает
 * вкладка «Права», без Vault — весь блок валюты.
 *
 * Выключение при этом остаётся возможным — но там, где оно и должно быть: на
 * самом сервере, руками. Панель не берётся отключать то, что нужно ей самой,
 * потому что чинить последствия придётся уже без неё.
 *
 * Сопоставление нестрогое по регистру и знакам: в Bukkit плагин зовётся
 * «InvSeePlusPlus», в файле — «InvSeePlusPlus-0.30.1.jar», а на диске может
 * оказаться и «invsee++.jar».
 */
function normalize(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '');
}

interface Protected {
  /**
   * Имена, под которыми этот плагин может к нам прийти: имя в Bukkit и имя,
   * под которым его знают люди. Расходятся они регулярно — InvSee++
   * регистрируется как «InvSeePlusPlus», EssentialsX как «Essentials», — и
   * запрос приходит то с одним, то с другим.
   */
  aliases: string[];
  reason: string;
}

const COMPANION_REASON =
  'Это companion-плагин самой панели: выключив его, панель потеряет связь с сервером ' +
  'и включить обратно будет уже нечем. Снимайте его только вручную по SFTP.';

const PROTECTED: Protected[] = [
  { aliases: [COMPANION_PLUGIN], reason: COMPANION_REASON },
  ...KNOWN_PLUGINS.map((p) => ({
    aliases: [p.id, p.displayName],
    reason:
      `${p.displayName} — один из плагинов, на которых держится панель (${p.gives}). ` +
      'Выключить или удалить его отсюда нельзя: снимайте вручную по SFTP, если он больше не нужен.',
  })),
];

/**
 * Защищён ли плагин с таким именем в Bukkit.
 *
 * Имя приходит от самого сервера и всегда точное, поэтому сравнение тоже
 * точное: «EssentialsChat» — отдельный плагин, и запрещать его заодно с
 * «Essentials» оснований нет.
 */
export function protectionReason(pluginName: string): string | null {
  const needle = normalize(pluginName);
  return PROTECTED.find((p) => p.aliases.some((a) => normalize(a) === needle))?.reason ?? null;
}

/**
 * Защищён ли плагин, лежащий в таком файле.
 *
 * Имя файла произвольно: «LuckPerms-Bukkit-5.4.144.jar», «Vault.jar»,
 * «EssentialsX-2.21.0.jar». Отсюда сравнение по началу имени, а не точное, и
 * отсюда же его намеренная нестрогость: лишний раз не дать снять файл
 * дополнения к EssentialsX — небольшая помеха, а молча унести LuckPerms в
 * .disabled/ и обнаружить это после перезапуска, когда права у всех
 * пропали, — уже происшествие.
 */
export function fileProtectionReason(fileName: string): string | null {
  const needle = normalize(fileName);
  return PROTECTED.find((p) => p.aliases.some((a) => needle.startsWith(normalize(a))))?.reason ?? null;
}
