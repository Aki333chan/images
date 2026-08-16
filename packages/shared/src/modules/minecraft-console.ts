/**
 * Автодополнение в консоли сервера.
 *
 * Два уровня, и они намеренно разные по природе:
 *
 *   1. БАЗОВЫЙ — статический словарь ниже. Работает всегда, даже когда игровой
 *      сервер выключен, и не требует ни companion-плагина, ни сетевого запроса
 *      на каждое нажатие Tab: панель забирает словарь один раз при открытии
 *      вкладки и дополняет локально.
 *
 *   2. ПРОДВИНУТЫЙ — companion-плагин спрашивает настоящее автодополнение у
 *      самого Bukkit. Тогда подсказки учитывают и команды сторонних плагинов,
 *      и аргументы (имена миров, китов, зачарований) — ровно то же, что видит
 *      игрок в игре.
 *
 * Через RCON автодополнения не бывает: в протоколе Source RCON есть только
 * «выполнить команду» и «ответ», запроса подсказок там нет в принципе.
 */

/**
 * Что ожидается на позиции аргумента.
 *
 * Значение имеет только 'player': именно там панель подставляет ники игроков,
 * которые сейчас в сети. Остальные два вида различаются лишь смыслом и
 * подсказок не дают — угадывать имена предметов и координаты вслепую
 * бессмысленно, для этого есть продвинутый уровень.
 */
export type MinecraftCommandArgKind = 'player' | 'value' | 'text';

export interface MinecraftConsoleCommandDto {
  /** Имя команды без ведущего слэша: в консоли сервера слэш не пишут. */
  name: string;
  /** Виды аргументов по позициям. Дальше последней позиции подсказок нет. */
  args: MinecraftCommandArgKind[];
  /** Плагин, дающий команду; null — команда самого сервера. */
  plugin: string | null;
}

/** Словарь для базового уровня: панель забирает его один раз и кэширует. */
export interface MinecraftConsoleDictionaryDto {
  commands: MinecraftConsoleCommandDto[];
  /** Ники игроков онлайн — их панель подставляет в аргументы вида 'player'. */
  players: string[];
  /**
   * true — на сервере настроен companion-плагин, и по Tab можно спросить
   * настоящее автодополнение. false — работает только словарь.
   */
  companionAvailable: boolean;
}

/** Ответ продвинутого уровня. */
export interface MinecraftConsoleCompletionDto {
  /** false — companion-плагин не настроен или не ответил; словарь остаётся. */
  available: boolean;
  suggestions: string[];
  source: 'companion' | 'static';
}

/**
 * Команды самого сервера.
 *
 * Список ванильный (Java Edition 1.21+) плюс несколько команд Paper — их
 * невозможно отличить от ванильных, не спросив сервер, а лишняя подсказка
 * вреда не делает: команда просто не выполнится. Обратная ошибка хуже —
 * отсутствие подсказки там, где команда есть.
 *
 * Команды сторонних плагинов сюда не попадают: они берутся из каталога
 * быстрых действий (см. quick-commands.config.ts на бэкенде) и добавляются
 * к этому списку при сборке словаря — второго списка тех же команд нет.
 */
export const MINECRAFT_SERVER_COMMANDS: MinecraftConsoleCommandDto[] = [
  cmd('advancement', ['value', 'player']),
  cmd('attribute', ['player', 'value']),
  cmd('ban', ['player', 'text']),
  cmd('ban-ip', ['value', 'text']),
  cmd('banlist', []),
  cmd('bossbar', ['value', 'value']),
  cmd('clear', ['player', 'value']),
  cmd('clone', []),
  cmd('damage', ['player', 'value']),
  cmd('data', ['value']),
  cmd('datapack', ['value']),
  cmd('debug', ['value']),
  cmd('defaultgamemode', ['value']),
  cmd('deop', ['player']),
  cmd('difficulty', ['value']),
  // effect give <targets> <effect> — цель на второй позиции.
  cmd('effect', ['value', 'player', 'value']),
  cmd('enchant', ['player', 'value']),
  cmd('execute', []),
  cmd('experience', ['value', 'player', 'value']),
  cmd('fill', []),
  cmd('fillbiome', []),
  cmd('forceload', ['value']),
  cmd('function', ['value']),
  cmd('gamemode', ['value', 'player']),
  cmd('gamerule', ['value', 'value']),
  cmd('give', ['player', 'value', 'value']),
  cmd('help', ['value']),
  cmd('item', ['value']),
  cmd('jfr', ['value']),
  cmd('kick', ['player', 'text']),
  cmd('kill', ['player']),
  cmd('list', []),
  cmd('locate', ['value', 'value']),
  cmd('loot', ['value']),
  cmd('me', ['text']),
  cmd('msg', ['player', 'text']),
  cmd('op', ['player']),
  cmd('pardon', ['player']),
  cmd('pardon-ip', ['value']),
  cmd('particle', ['value']),
  cmd('perf', ['value']),
  cmd('place', ['value']),
  cmd('playsound', ['value', 'value', 'player']),
  cmd('random', ['value']),
  cmd('recipe', ['value', 'player', 'value']),
  cmd('reload', ['value']),
  cmd('ride', ['player', 'value']),
  cmd('rotate', ['player']),
  cmd('save-all', ['value']),
  cmd('save-off', []),
  cmd('save-on', []),
  cmd('say', ['text']),
  cmd('schedule', ['value']),
  cmd('scoreboard', ['value', 'value']),
  cmd('seed', []),
  cmd('setblock', []),
  cmd('setidletimeout', ['value']),
  cmd('setworldspawn', []),
  cmd('spawnpoint', ['player']),
  cmd('spectate', ['player', 'player']),
  cmd('spreadplayers', []),
  cmd('stop', []),
  cmd('stopsound', ['player', 'value']),
  cmd('summon', ['value']),
  cmd('tag', ['player', 'value']),
  cmd('team', ['value', 'value', 'player']),
  cmd('teammsg', ['text']),
  cmd('teleport', ['player', 'player']),
  cmd('tell', ['player', 'text']),
  cmd('tellraw', ['player', 'text']),
  cmd('tick', ['value']),
  cmd('time', ['value', 'value']),
  cmd('title', ['player', 'value', 'text']),
  cmd('tp', ['player', 'player']),
  cmd('transfer', ['value', 'player']),
  cmd('trigger', ['value']),
  cmd('w', ['player', 'text']),
  cmd('weather', ['value', 'value']),
  cmd('whitelist', ['value', 'player']),
  cmd('worldborder', ['value', 'value']),
  cmd('xp', ['value', 'player', 'value']),

  // Команды Paper. На ванильном сервере их нет — см. комментарий выше.
  cmd('mspt', []),
  cmd('paper', ['value']),
  cmd('plugins', []),
  cmd('tps', []),
  cmd('version', ['value']),
];

function cmd(name: string, args: MinecraftCommandArgKind[]): MinecraftConsoleCommandDto {
  return { name, args, plugin: null };
}

/**
 * Что подставлять на позиции `index` аргумента команды `spec`.
 *
 * За пределами объявленных позиций подсказок нет — кроме команд, у которых
 * последний аргумент свободный текст (say, kick): там продолжение — тоже текст.
 */
export function argKindAt(
  spec: MinecraftConsoleCommandDto,
  index: number,
): MinecraftCommandArgKind {
  const declared = spec.args[index];
  if (declared) return declared;
  return spec.args[spec.args.length - 1] === 'text' ? 'text' : 'value';
}

/**
 * Подсказки для строки, набранной в консоли, по словарю.
 *
 * Возвращает варианты ЦЕЛИКОМ для последнего (незавершённого) токена. Строка,
 * оканчивающаяся пробелом, означает «начат следующий аргумент», и тогда
 * незавершённый токен пустой — предлагаются все допустимые значения.
 *
 * Общая с бэкендом функция намеренно: тесты на неё пишутся один раз, а
 * поведение подсказок в панели и в возможных будущих клиентах совпадает.
 */
export function completeFromDictionary(
  line: string,
  dictionary: MinecraftConsoleCommandDto[],
  players: string[],
  limit = 60,
): string[] {
  // Ведущий слэш в консоли сервера не нужен, но человек его набирает по
  // привычке из игры: молча принимаем и дополняем как без него.
  const normalized = line.startsWith('/') ? line.slice(1) : line;
  const endsWithSpace = /\s$/.test(normalized);
  const tokens = normalized.split(/\s+/).filter((t) => t.length > 0);

  // Первое слово ещё набирается — дополняем имя команды.
  if (tokens.length === 0 || (tokens.length === 1 && !endsWithSpace)) {
    const prefix = (tokens[0] ?? '').toLowerCase();
    return dictionary
      .filter((c) => c.name.toLowerCase().startsWith(prefix))
      .map((c) => c.name)
      .sort((a, b) => a.localeCompare(b))
      .slice(0, limit);
  }

  const spec = dictionary.find((c) => c.name.toLowerCase() === (tokens[0] ?? '').toLowerCase());
  if (!spec) return [];

  // Индекс аргумента, который набирается сейчас: 0 — сразу после имени команды.
  const argIndex = endsWithSpace ? tokens.length - 1 : tokens.length - 2;
  if (argIndex < 0) return [];
  if (argKindAt(spec, argIndex) !== 'player') return [];

  const prefix = (endsWithSpace ? '' : (tokens[tokens.length - 1] ?? '')).toLowerCase();
  return players
    .filter((name) => name.toLowerCase().startsWith(prefix))
    .sort((a, b) => a.localeCompare(b))
    .slice(0, limit);
}
