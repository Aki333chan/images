import type {
  SevenDaysBanDto,
  SevenDaysPlayerDto,
  SevenDaysPlayersResponse,
  SevenDaysWhitelistEntryDto,
} from '@aurum/shared';

/**
 * Разбор ответов консоли 7 Days to Die.
 *
 * Ответы текстовые и никак не размечены — это цена telnet вместо RCON.
 *
 * Что здесь сверено с первоисточником, а что нет — важно различать, потому
 * что от этого зависит, насколько строгим можно быть при разборе:
 *
 *   `lp` / `listplayers` — формат строки известен точно и подтверждён с трёх
 *   сторон: живым сервером, парсером zigawatt/sdtd и парсером Kitsune7Den.
 *   Здесь разбор строгий: не совпало — значит, это не строка игрока.
 *
 *   `gettime`, `version` — тоже известны («Day 12, 19:53», «Game version: …»).
 *
 *   `ban list` / `whitelist list` — а вот раскладку ЭТИХ таблиц игра нигде не
 *   публикует, и ни один открытый менеджер серверов её не разбирает (проверено
 *   поиском по коду). Поэтому опираться на «первая колонка — id, вторая —
 *   срок» нельзя: это было бы угадывание, а угаданный формат ломается молча.
 *   Вместо этого записи узнаются по ФОРМЕ токенов, которая как раз
 *   задокументирована: идентификатор игрока — «Steam_…» / «EOS_…» (ровно так
 *   он приходит в pltfmid/crossid), срок — дата вида ГГГГ-ММ-ДД. Такой разбор
 *   одинаково работает и с таблицей на вертикальных чертах, и с колонками по
 *   пробелам, и с записью «Имя (причина)».
 *
 * Всё, что не разобралось, отбрасывается, а не превращается в запись с
 * пустыми полями: список игроков с призраком хуже короткого списка.
 */

/** Одна строка `<unknown>` в ответе означает «сервер не знает», а не имя. */
const UNKNOWN = '<unknown>';

/**
 * Метка времени лога в начале строки: «2026-03-14T19:43:54 432.501 INF ».
 *
 * Обычный вывод команды её не несёт, но telnet отдаёт живой лог в тот же
 * поток, и в некоторых сборках префикс достаётся и строкам вывода. Дешевле
 * снять его всегда, чем потерять строку.
 */
const LOG_PREFIX = /^\d{4}-\d{2}-\d{2}T[\d:]+\s+[\d.]+\s+\w+\s+/;

function stripPrefix(line: string): string {
  return line.replace(LOG_PREFIX, '').trim();
}

function orNull(value: string | undefined | null): string | null {
  if (value === undefined || value === null) return null;
  const trimmed = value.trim();
  return trimmed === '' || trimmed === UNKNOWN ? null : trimmed;
}

function intOrNull(value: string | undefined): number | null {
  const parsed = Number.parseInt((value ?? '').trim(), 10);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Значение поля `имя=…` из хвоста строки игрока.
 *
 * Поля разделены запятыми, но по запятым строку резать НЕЛЬЗЯ: и ник игрока,
 * и координаты содержат запятые сами. Поэтому каждое поле ищется по своему
 * имени, а конец значения — по следующей запятой или концу строки.
 *
 * Имя поля обязано начинаться на границе (после запятой или в начале хвоста)
 * — иначе запрос «players» нашёл бы себя внутри «zombieplayers», а запрос
 * «id» — внутри «pltfmid».
 */
function field(rest: string, name: string): string | undefined {
  const match = new RegExp(`(?:^|,\\s*)${name}=([^,]*)`).exec(rest);
  return match?.[1];
}

/**
 * Список игроков — ответ команды `lp` (она же `listplayers`).
 *
 * Формат строки, как его собирает сама игра:
 *
 *   0. id=171, Lost, pos=(342.4, 49.0, -541.9), rot=(0.0, 194.1, 0.0),
 *   remote=True, health=112, deaths=10, zombies=225, players=3, score=175,
 *   level=12, pltfmid=Steam_7656…, crossid=EOS_0002…, ip=…, ping=13
 *
 * Заканчивается строкой «Total of N in the game» — её печатает та же команда,
 * и число берём оттуда, а не считаем сами: если строку игрока разобрать не
 * удалось, честнее показать «онлайн 3» при двух разобранных, чем тихо
 * потерять человека.
 */
export function parsePlayers(output: string): SevenDaysPlayersResponse {
  const players: SevenDaysPlayerDto[] = [];
  let online: number | null = null;

  for (const raw of output.split(/\r?\n/)) {
    const line = stripPrefix(raw);

    const total = /^Total of (\d+) in the game/.exec(line);
    if (total) {
      online = Number.parseInt(total[1]!, 10);
      continue;
    }

    // «0. id=171, Lost, pos=…»: номер по порядку, идентификатор сущности,
    // затем ник — лениво, до первого «, pos=». Ленивость тут не украшение:
    // ник вида «Lost, again» иначе съел бы разделитель и сломал разбор.
    const head = /^\d+\.\s*id=(\d+),\s*(.*?),\s*pos=\(([^)]*)\)(.*)$/.exec(line);
    if (!head) continue;

    const [, entityId, name, pos, rest] = head;
    const coords = pos!.split(',').map((n) => Number.parseFloat(n.trim()));

    players.push({
      entityId: Number.parseInt(entityId!, 10),
      name: name!.trim(),
      platformId: orNull(field(rest!, 'pltfmid')),
      crossId: orNull(field(rest!, 'crossid')),
      ip: orNull(field(rest!, 'ip')),
      ping: intOrNull(field(rest!, 'ping')),
      health: intOrNull(field(rest!, 'health')),
      deaths: intOrNull(field(rest!, 'deaths')),
      zombieKills: intOrNull(field(rest!, 'zombies')),
      playerKills: intOrNull(field(rest!, 'players')),
      score: intOrNull(field(rest!, 'score')),
      level: intOrNull(field(rest!, 'level')),
      position:
        coords.length === 3 && coords.every((n) => Number.isFinite(n))
          ? { x: coords[0]!, y: coords[1]!, z: coords[2]! }
          : null,
    });
  }

  return { players, online: online ?? players.length };
}

/**
 * Идентификатор игрока в выводе консоли.
 *
 * Либо «платформа_идентификатор» — ровно в таком виде игра отдаёт pltfmid и
 * crossid, и ровно такой принимает `ban add`, — либо голый идентификатор
 * Steam (17 цифр), как он лежит в serveradmin.xml.
 */
const ID_TOKEN = /(?:Steam|EOS|XBL|PSN|Nintendo|Unknown)_[A-Za-z0-9]+|\b\d{15,20}\b/;

/** Срок бана: дата, при желании со временем. Ни с чем в строке не путается. */
const DATE_TOKEN = /\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?)?/;

/** Строки, которые печатает сама команда, но записями не являются. */
function isNoise(line: string): boolean {
  return (
    line === '' ||
    /^Executing command\b/i.test(line) ||
    /^Total of \d+/i.test(line) ||
    /^-{3,}$/.test(line)
  );
}

/**
 * Разбор одной строки таблицы: вынуть идентификатор и срок, а остальное
 * вернуть как есть.
 *
 * Возвращает null, если идентификатора в строке нет, — это и есть способ
 * пропустить заголовок таблицы и подпись под ней, не зная их текста.
 */
function tableRow(line: string): { id: string; date: string | null; rest: string } | null {
  const id = ID_TOKEN.exec(line);
  if (!id) return null;

  const withoutId = line.slice(0, id.index) + line.slice(id.index + id[0].length);
  const date = DATE_TOKEN.exec(withoutId);
  const rest = date
    ? withoutId.slice(0, date.index) + withoutId.slice(date.index + date[0].length)
    : withoutId;

  return { id: id[0], date: date ? date[0] : null, rest };
}

/**
 * Остаток строки после идентификатора и срока — это имя и, возможно, причина.
 *
 * Разделителем может быть вертикальная черта, а может быть просто пробелы;
 * причина может стоять в скобках. Разбираются все три случая сразу, потому
 * что заранее неизвестно, какой из них выбрала конкретная версия игры.
 */
function nameAndReason(rest: string): { displayName: string | null; reason: string | null } {
  const cells = rest
    .split('|')
    .map((c) => c.trim())
    .filter((c) => c !== '' && !/^-+$/.test(c));

  if (cells.length > 1) {
    return { displayName: orNull(cells[0]), reason: orNull(cells.slice(1).join(' ')) };
  }

  const single = (cells[0] ?? '').trim();
  const parens = /^(.*?)\s*\(([^)]*)\)\s*$/.exec(single);
  if (parens) {
    return { displayName: orNull(parens[1]), reason: orNull(parens[2]) };
  }

  return { displayName: orNull(single), reason: null };
}

/**
 * Список банов — ответ `ban list`.
 *
 * Записью считается строка, в которой есть идентификатор игрока. Заголовок
 * таблицы, разделители и итоговая строка идентификатора не содержат и
 * отсеиваются сами собой, без знания их текста.
 */
export function parseBans(output: string): SevenDaysBanDto[] {
  const bans: SevenDaysBanDto[] = [];

  for (const raw of output.split(/\r?\n/)) {
    const line = stripPrefix(raw);
    if (isNoise(line)) continue;

    const row = tableRow(line);
    if (!row) continue;

    const { displayName, reason } = nameAndReason(row.rest);
    bans.push({ id: row.id, until: orNull(row.date), displayName, reason });
  }

  return bans;
}

/**
 * Белый список — ответ `whitelist list`.
 *
 * Отдельная функция, а не общая с банами: колонок здесь меньше и смысл у них
 * другой, а общая заставила бы вызывающего помнить, какая колонка чем
 * оказалась.
 */
export function parseWhitelist(output: string): SevenDaysWhitelistEntryDto[] {
  const entries: SevenDaysWhitelistEntryDto[] = [];

  for (const raw of output.split(/\r?\n/)) {
    const line = stripPrefix(raw);
    if (isNoise(line)) continue;

    const row = tableRow(line);
    if (!row) continue;

    entries.push({ id: row.id, displayName: nameAndReason(row.rest).displayName });
  }

  return entries;
}

/**
 * Игровое время — ответ `gettime`: «Day 12, 19:53».
 *
 * День и время суток в этой игре не украшение: на каждый седьмой день
 * приходит орда, и «какой сегодня день» — первый вопрос дежурного.
 */
export function parseGameTime(output: string): { day: number | null; time: string | null } {
  const match = /Day\s+(\d+),\s*(\d{1,2}:\d{2})/i.exec(output);
  if (!match) return { day: null, time: null };
  return { day: Number.parseInt(match[1]!, 10), time: match[2]! };
}

/**
 * Версия сервера — ответ `version`.
 *
 * Игра печатает несколько строк с окружением; нужна та, где стоит «Game
 * version:». Запасной вариант — первое, что похоже на номер версии: пусть
 * лучше в интерфейсе будет приблизительная версия, чем прочерк.
 */
export function parseVersion(output: string): string | null {
  const match = /Game version:\s*(.+)/i.exec(output);
  if (match) {
    // «Game version: V 2.0 (b28) Compatibility Version: V 2.0» — номер стоит
    // до второй подписи, и обрывать его на первом пробеле нельзя: осталась бы
    // одна буква «V».
    const value = match[1]!.split(/\s*Compatibility Version:/i)[0]!.trim();
    if (value !== '') return value;
  }
  const fallback = /\bV\s?\d+\.\d+(?:\.\d+)?(?:\s*\(b\d+\))?/i.exec(output);
  return fallback ? fallback[0]!.trim() : null;
}

/**
 * Отказ консоли, поданный как обычный текст.
 *
 * У telnet нет кода ответа: и успех, и отказ приходят строками. Поэтому
 * ошибки распознаются по тексту — иначе панель показала бы «готово» там, где
 * сервер отказал.
 */
export function consoleError(output: string): string | null {
  const line = output
    .split(/\r?\n/)
    .map((l) => stripPrefix(l))
    .find(
      (l) =>
        l.startsWith('*** ERROR') ||
        /is not an allowed duration unit/i.test(l) ||
        /^Playername or entity\/steam id not found/i.test(l) ||
        /^Can't find player/i.test(l),
    );
  return line ? line.replace(/^\*\*\*\s*ERROR:?\s*/i, '').trim() : null;
}
