import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import ru from './catalogs/ru.json';
import en from './catalogs/en.json';
import pl from './catalogs/pl.json';

/**
 * Ключи, которые панели присылает игровой сервер, в словаре тоже есть.
 *
 * ЗАЧЕМ ОТДЕЛЬНО ОТ keys.spec.ts. Тот тест ищет ключи в исходниках панели, а
 * эти в исходниках не встречаются ни разу: они приходят по сети от плагина
 * AurumGuilds через companion. Переименуй ключ в плагине или забудь строку в
 * словаре панели — и сотрудник увидит на экране «guild.err.noSuchGuild».
 * Ни типы, ни keys.spec.ts этого не поймают.
 *
 * ЧТЕНИЕ ФАЙЛА ПЛАГИНА — не лишняя связность, а единственный способ проверить
 * ровно то, что нужно: два словаря в разных репозиториях-модулях обязаны
 * сойтись именами. Русский файл плагина здесь — источник правды о том, какие
 * ключи вообще существуют.
 */

/** Ключи исходов, которые companion может передать в панель. */
const FORWARDED = [
  'guild.disbanded',
  'guild.admin.leaderSet',
  'guild.admin.removed',
  'guild.admin.wasNotInGuild',
  'guild.bonus.granted',
  'guild.bonus.grantedForever',
  'guild.bonus.revoked',
  'guild.err.noSuchGuild',
  'guild.err.noMemberNamed',
  'guild.err.targetAlreadyLeader',
  'guild.err.targetNoGuild',
  'guild.err.noBonusType',
  'guild.err.noSuchBonus',
  'guild.err.internal',
];

/** Ключи, которые companion сочиняет сам, без плагина гильдий. */
const OWN = [
  'mc.g.err.noSuchBonusType',
  'mc.give.err.unknownItem',
  'mc.give.err.full',
  'mc.plug.err.notFound',
  'mc.plug.err.selfDisable',
  'mc.plug.err.refused',
  'mc.plug.err.timeout',
  'mc.perm.err.noUser',
  'mc.perm.err.noGroup',
  'mc.perm.err.alreadyAbsent',
  'mc.perm.err.alreadyPresent',
  'mc.perm.err.saveFailed',
];

/** Плоские ключи файла языка плагина. Разбор ровно под его формат: два уровня отступа. */
function pluginKeys(file: string): Set<string> {
  const keys = new Set<string>();
  const path: string[] = [];
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    // Возврат каретки срезаем сразу: файл языка правят и в Windows, и через
    // веб-интерфейс GitHub, и тогда он приезжает с CRLF. В регулярке ниже
    // «$» без флага m это конец всей строки, а «.» не совпадает с \r — из-за
    // одного невидимого символа разбор молча терял ключи, и тест падал на
    // строках, которые в файле есть.
    const raw = line.replace(/\r$/, '');
    if (raw.trim() === '' || raw.trim().startsWith('#') || raw.trim().startsWith('- ')) continue;
    const match = /^(\s*)([\w.]+):(.*)$/.exec(raw);
    if (!match) continue;
    const [, indent = '', name = '', value = ''] = match;
    const depth = indent.length / 2;
    path.length = depth;
    path[depth] = name;
    // Значение есть — это лист. Пусто — это раздел, и его имя не ключ.
    if (value.trim() !== '') keys.add(path.join('.'));
    // Ключи со списком строк (подсказки в меню) сюда не попадают: у них пустое
    // значение, и от раздела их не отличить. Проверять их и не нужно — в
    // панель уезжают только однострочные исходы.
  }
  return keys;
}

const PLUGIN_RU = join(
  __dirname,
  '../../../../guilds-plugin/paper/src/main/resources/lang/messages_ru.yml',
);

describe('ключи, приходящие от игрового сервера', () => {
  it('каждый пересылаемый ключ есть в словаре плагина', () => {
    const inPlugin = pluginKeys(PLUGIN_RU);
    expect(FORWARDED.filter((key) => !inPlugin.has(key))).toEqual([]);
  });

  it('каждый такой ключ есть во всех трёх словарях панели', () => {
    for (const [name, catalog] of [
      ['ru', ru],
      ['en', en],
      ['pl', pl],
    ] as const) {
      const missing = [...FORWARDED, ...OWN].filter(
        (key) => !(key in (catalog as unknown as Record<string, string>)),
      );
      expect({ [name]: missing }).toEqual({ [name]: [] });
    }
  });

  /**
   * Ранги и виды бонусов панель показывает по тем же именам ключей, что и
   * плагин: он присылает `mc.bonus.blockDrops`, панель ищет его у себя.
   * Разъехались — и на экране окажется само имя ключа.
   */
  it('mc.rank.* и mc.bonus.* совпадают с плагином', () => {
    const inPlugin = [...pluginKeys(PLUGIN_RU)].filter(
      (key) => key.startsWith('mc.rank.') || (key.startsWith('mc.bonus.') && !key.includes('.short.')),
    );
    // Плагину нужен ещё mc.bonus.level — это его собственная подпись величины,
    // в панель она не уезжает.
    const shared = inPlugin.filter((key) => key !== 'mc.bonus.level');
    expect(shared.filter((key) => !(key in (ru as unknown as Record<string, string>)))).toEqual([]);
  });
});
