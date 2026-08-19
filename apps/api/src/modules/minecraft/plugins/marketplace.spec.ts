process.env.NODE_ENV = 'test';

import {
  compatibilityOf,
  matchGameVersion,
  matchLoader,
  type ServerTargetDto,
} from '@aurum/shared';
import { parseVersionOutput } from './plugin-targets.service';

/**
 * Совместимость — БЕЙДЖ, А НЕ ФИЛЬТР.
 *
 * Эти тесты закрепляют главное свойство всей функции: панель считает, что
 * версия «не заявлена», но никогда не делает из этого вывод «нельзя ставить».
 * Отсутствие в контракте булева `compatible` — часть того же решения, и если
 * кто-то соберётся его добавить, начинать надо с обсуждения, а не с кода.
 */
describe('бейдж совместимости', () => {
  describe('игровая версия', () => {
    it('точное совпадение', () => {
      expect(matchGameVersion(['1.20.1', '1.21.4'], '1.21.4')).toBe('match');
    });

    it('плагин под 1.21 подходит серверу 1.21.4', () => {
      // Автор указал ветку, а не каждый патч — заставлять человека читать
      // список из тридцати номеров ради этого незачем.
      expect(matchGameVersion(['1.21'], '1.21.4')).toBe('match');
    });

    it('плагин под 1.21.4 засчитывается серверу 1.21', () => {
      expect(matchGameVersion(['1.21.4'], '1.21')).toBe('match');
    });

    it('несовпадение — «не заявлено», а не «нельзя»', () => {
      // Формулировка тут принципиальна: 1.16 на 1.21 чаще всего работает,
      // просто автор не обновил метаданные.
      expect(matchGameVersion(['1.16.5'], '1.21.4')).toBe('not-declared');
    });

    it('пустой список заявленных версий — тоже «не заявлено»', () => {
      expect(matchGameVersion([], '1.21.4')).toBe('not-declared');
    });

    it('версия сервера неизвестна — сравнивать не с чем', () => {
      expect(matchGameVersion(['1.21.4'], null)).toBe('unknown');
    });

    it('1.2 не считается подходящим для 1.21', () => {
      // Проверка на наивное «начинается с»: 1.21 начинается с 1.2.
      expect(matchGameVersion(['1.2'], '1.21')).toBe('not-declared');
    });
  });

  describe('ядро', () => {
    it('плагин под Bukkit работает на Paper', () => {
      expect(matchLoader(['bukkit'], 'paper')).toBe('match');
    });

    it('плагин под Spigot работает на Purpur', () => {
      expect(matchLoader(['spigot'], 'purpur')).toBe('match');
    });

    it('плагин под Paper на Spigot — не заявлен', () => {
      // Направление важно: Paper это надмножество Spigot, но не наоборот.
      expect(matchLoader(['paper'], 'spigot')).toBe('not-declared');
    });

    it('регистр не важен', () => {
      expect(matchLoader(['PAPER'], 'paper')).toBe('match');
    });

    it('ядро сервера неизвестно — сравнивать не с чем', () => {
      expect(matchLoader(['paper'], null)).toBe('unknown');
    });
  });

  it('compatibilityOf считает обе оси разом', () => {
    const target: ServerTargetDto = {
      serverId: 's1',
      name: 'Выживание',
      gameVersion: '1.21.4',
      loader: 'paper',
      status: 'running',
    };

    expect(compatibilityOf({ loaders: ['spigot'], gameVersions: ['1.16.5'] }, target)).toEqual({
      gameVersion: 'not-declared',
      loader: 'match',
    });
  });

  it('без сервера обе оси неизвестны, а версия всё равно отдаётся', () => {
    expect(compatibilityOf({ loaders: ['paper'], gameVersions: ['1.21'] }, null)).toEqual({
      gameVersion: 'unknown',
      loader: 'unknown',
    });
  });
});

/**
 * Разбор ответа команды `version`.
 *
 * Формат чужой и меняется от ядра к ядру, а ломается такой разбор тихо —
 * поэтому проверяется на реальных строках всех ядер, которые встречаются.
 */
describe('parseVersionOutput', () => {
  it('Paper', () => {
    expect(
      parseVersionOutput('This server is running Paper version 1.21.4-40-main (MC: 1.21.4)'),
    ).toEqual({ gameVersion: '1.21.4', loader: 'paper' });
  });

  it('Spigot отдаёт CraftBukkit — считаем его Spigot', () => {
    expect(
      parseVersionOutput(
        'This server is running CraftBukkit version 1.20.1-R0.1-SNAPSHOT (MC: 1.20.1)',
      ),
    ).toEqual({ gameVersion: '1.20.1', loader: 'spigot' });
  });

  it('Purpur', () => {
    expect(
      parseVersionOutput('This server is running Purpur version 1.21.4-2245 (MC: 1.21.4)'),
    ).toEqual({ gameVersion: '1.21.4', loader: 'purpur' });
  });

  it('Folia', () => {
    expect(parseVersionOutput('This server is running Folia version 1.21.4-1 (MC: 1.21.4)')).toEqual(
      { gameVersion: '1.21.4', loader: 'folia' },
    );
  });

  it('без блока (MC: …) берётся номер после version', () => {
    expect(parseVersionOutput('This server is running Paper version 1.20.6')).toEqual({
      gameVersion: '1.20.6',
      loader: 'paper',
    });
  });

  it('мусор не превращается в выдуманную версию', () => {
    // Лучше честное «не знаю»: на нём бейдж скажет «не с чем сравнивать»,
    // а выдуманная версия молча наврала бы во всех строках списка.
    expect(parseVersionOutput('Unknown command')).toEqual({
      gameVersion: null,
      loader: null,
    });
  });
});
