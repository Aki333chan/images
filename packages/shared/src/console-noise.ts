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

const SEVENDAYS_ENDPOINT = '[\\[\\]0-9a-fA-F:.%]+:\\d+';
const SEVENDAYS_CONNECTION = new RegExp(
  `^Telnet connection (?:from|closed): ${SEVENDAYS_ENDPOINT}$`,
);
const SEVENDAYS_THREAD = new RegExp(
  `^(?:Started|Exited) thread TelnetClient_${SEVENDAYS_ENDPOINT}$`,
);
const SEVENDAYS_COMMAND = new RegExp(
  `^Executing command '([^']+)' by Telnet from ${SEVENDAYS_ENDPOINT}$`,
);

/** Only presentation filtering: never remove raw logs or hide warnings/errors.
 * A manual lp has the same echo as polling; its result remains visible either way.
 */
export function isConsoleServiceLine(text: string, moduleId: string): boolean {
  const commands = backgroundCommandsFor(moduleId);
  if (moduleId !== 'sevendays') return isPanelCommandEcho(text, commands);

  // Match the game's actual log envelope; chat quoting these words is NOT noise.
  const clean = text.replace(/\x1b\[[0-9;]*m/g, '').trim();
  const log =
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+[\d.]+\s+(INF|WRN|ERR|EXC|DBG)\s+(.*)$/.exec(
      clean,
    );
  if (!log) return isPanelCommandEcho(clean, commands);
  if (log[1] !== 'INF') return false;
  const message = log[2] ?? '';
  // End-point syntax only: an error message appended to the line must stay visible.
  if (SEVENDAYS_CONNECTION.test(message)) return true;
  if (SEVENDAYS_THREAD.test(message)) return true;
  if (/^(?:Started|Exited) thread: Telnet client$/.test(message)) return true;
  const command = SEVENDAYS_COMMAND.exec(message);
  if (!command) return false;
  const value = (command[1] ?? '').trim().toLowerCase();
  // Only the panel's response-boundary markers, not arbitrary unknown commands.
  return commands.includes(value) || /^aurum[0-9a-f]{24}$/.test(value);
}
