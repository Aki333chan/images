/**
 * Команды, которые панель шлёт на игровой сервер САМА.
 *
 * <h2>Зачем это знать</h2>
 *
 * Чтобы держать счётчик онлайна, TPS и список белого списка в актуальном виде,
 * панель раз в несколько секунд опрашивает сервер. Плагины вроде EssentialsX
 * пишут в консоль строку на каждую такую команду — и журнал заливает одним и
 * тем же текстом круглые сутки, из-за чего за настоящими событиями следить
 * невозможно.
 *
 * Консоль прячет строки об этих командах. Всё остальное остаётся: команда,
 * которую человек ввёл в поле консоли или запустил быстрым действием, — это
 * его действие, он ждёт результата, и убирать его из журнала нельзя.
 *
 * <h2>Почему по модулям</h2>
 *
 * У каждой игры свой набор: Minecraft опрашивают через `list` и `tps`, а
 * 7 Days to Die — через `lp` и `gettime`. Общий список означал бы, что на
 * одном сервере прячется лишнее, а на другом не прячется нужное.
 *
 * <h2>Как поддерживать</h2>
 *
 * Список должен совпадать с тем, что панель реально шлёт фоном. Сейчас это:
 *
 * - `minecraft*`: `list` (состав онлайна), `tps`/`mspt` (нагрузка),
 *   `version` (определение сборки и плагинов), `whitelist list` (белый список);
 * - `sevendays`: `lp` (состав), `gettime` (игровое время), `version`.
 *
 * СЮДА ПОПАДАЕТ ТОЛЬКО ЧТЕНИЕ. Команды, которые панель шлёт сама, но которые
 * что-то МЕНЯЮТ, прятать нельзя: `pardon` от истёкшего бана администратор
 * должен видеть в журнале — это событие, а не опрос.
 */

/** Пустой список — модуль ничего не опрашивает командами (или консоли у него нет). */
const BACKGROUND_COMMANDS: Record<string, readonly string[]> = {
  minecraft: ['list', 'tps', 'mspt', 'version', 'whitelist list'],
  'minecraft-forge': ['list', 'tps', 'mspt', 'version', 'whitelist list'],
  'minecraft-neoforge': ['list', 'tps', 'mspt', 'version', 'whitelist list'],
  sevendays: ['lp', 'listplayers', 'gettime', 'version'],
  // Palworld опрашивается по HTTP, а не командами: в консоль от этого ничего
  // не попадает, и прятать нечего.
  palworld: [],
};

export function backgroundCommandsFor(moduleId: string): readonly string[] {
  return BACKGROUND_COMMANDS[moduleId] ?? [];
}

/**
 * Приметы строки-обёртки вокруг команды, пришедшей по RCON.
 *
 * Формат у каждого плагина свой, поэтому несколько узких выражений, а не одно
 * широкое: «строка содержит list» спрятало бы и жалобу игрока со словом list
 * в тексте.
 *
 * Захватывается ВЕСЬ хвост после двоеточия, а не первое слово: команды бывают
 * из двух слов (`whitelist list`), и по одному первому их не отличить от
 * `whitelist add` — а это уже действие человека.
 */
const RCON_PATTERNS: RegExp[] = [
  // EssentialsX: [Essentials] Rcon issued server command: /list
  /\brcon issued server command:\s*(.*)$/i,
  // Ванильный сервер: [Rcon: Rcon issued server command: /list]
  /\[rcon[^\]]*issued server command:\s*([^\]]*)/i,
];

/**
 * Это отклик на команду, которую панель послала сама?
 *
 * Сравнение точное, а не по началу строки: `whitelist add Steve` не должно
 * попасть под `whitelist list`, а `listplayers` — под `list`.
 */
export function isPanelCommandEcho(text: string, commands: readonly string[]): boolean {
  if (commands.length === 0) return false;

  for (const pattern of RCON_PATTERNS) {
    const match = pattern.exec(text);
    if (!match) continue;

    const tail = (match[1] ?? '')
      .trim()
      // Ванильный формат закрывает строку скобкой, а слэш перед командой
      // ставят не все.
      .replace(/\]+$/, '')
      .replace(/^\//, '')
      .trim()
      .toLowerCase();
    if (!tail) continue;
    if (commands.some((command) => command.toLowerCase() === tail)) return true;
  }
  return false;
}
