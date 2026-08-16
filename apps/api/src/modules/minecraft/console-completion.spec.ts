process.env.NODE_ENV = 'test';

import {
  MINECRAFT_SERVER_COMMANDS,
  argKindAt,
  completeFromDictionary,
  type MinecraftConsoleCommandDto,
} from '@aurum/shared';
import { MINECRAFT_QUICK_COMMANDS, catalogConsoleCommands } from './quick-commands.config';

/** Небольшой словарь: тесты про алгоритм, а не про полноту списка команд. */
const DICTIONARY: MinecraftConsoleCommandDto[] = [
  { name: 'gamemode', args: ['value', 'player'], plugin: null },
  { name: 'give', args: ['player', 'value'], plugin: null },
  { name: 'kick', args: ['player', 'text'], plugin: null },
  { name: 'list', args: [], plugin: null },
  { name: 'say', args: ['text'], plugin: null },
];

const PLAYERS = ['Steve', 'Alex', 'stevie_wonder'];

describe('автодополнение по словарю', () => {
  it('дополняет имя команды по префиксу', () => {
    expect(completeFromDictionary('gi', DICTIONARY, PLAYERS)).toEqual(['give']);
    expect(completeFromDictionary('g', DICTIONARY, PLAYERS)).toEqual(['gamemode', 'give']);
  });

  it('пустая строка предлагает все команды', () => {
    expect(completeFromDictionary('', DICTIONARY, PLAYERS)).toHaveLength(DICTIONARY.length);
  });

  it('регистр в имени команды не важен', () => {
    expect(completeFromDictionary('GiV', DICTIONARY, PLAYERS)).toEqual(['give']);
  });

  // Слэш в консоли сервера не пишут, но рука сама его набирает — по привычке
  // из игры. Молча принимаем: ругаться на это было бы мелочной придиркой.
  it('ведущий слэш не мешает', () => {
    expect(completeFromDictionary('/gi', DICTIONARY, PLAYERS)).toEqual(['give']);
  });

  it('подставляет ников онлайна там, где ожидается ник', () => {
    // give <player> — ник на первой позиции.
    expect(completeFromDictionary('give ', DICTIONARY, PLAYERS)).toEqual([
      'Alex',
      'Steve',
      'stevie_wonder',
    ]);
    expect(completeFromDictionary('give st', DICTIONARY, PLAYERS)).toEqual([
      'Steve',
      'stevie_wonder',
    ]);
  });

  it('учитывает позицию аргумента: у gamemode ник второй, а не первый', () => {
    // Первая позиция — режим игры, ников там быть не должно.
    expect(completeFromDictionary('gamemode ', DICTIONARY, PLAYERS)).toEqual([]);
    expect(completeFromDictionary('gamemode creative ', DICTIONARY, PLAYERS)).toEqual([
      'Alex',
      'Steve',
      'stevie_wonder',
    ]);
  });

  // Разница между «набрал слово» и «набрал слово и пробел» — это разница
  // между «дополни это слово» и «начни следующее». Легко перепутать местами.
  it('различает завершённый и незавершённый токен', () => {
    // Пробела нет — дополняется само имя команды.
    expect(completeFromDictionary('give', DICTIONARY, PLAYERS)).toEqual(['give']);
    // Пробел есть — пошёл первый аргумент.
    expect(completeFromDictionary('give ', DICTIONARY, PLAYERS)).toContain('Steve');
  });

  it('свободный текст не дополняется', () => {
    // say <text> — подставлять сюда ники было бы вредно.
    expect(completeFromDictionary('say ', DICTIONARY, PLAYERS)).toEqual([]);
    // kick <player> <reason> — причина остаётся свободным текстом,
    // даже когда слов в ней уже несколько.
    expect(completeFromDictionary('kick Steve ', DICTIONARY, PLAYERS)).toEqual([]);
    expect(completeFromDictionary('kick Steve плохое ', DICTIONARY, PLAYERS)).toEqual([]);
  });

  it('незнакомая команда подсказок не даёт', () => {
    expect(completeFromDictionary('nosuchcommand ', DICTIONARY, PLAYERS)).toEqual([]);
  });

  it('команда без аргументов подсказок не даёт', () => {
    expect(completeFromDictionary('list ', DICTIONARY, PLAYERS)).toEqual([]);
  });

  it('ограничивает число вариантов', () => {
    const many = Array.from({ length: 200 }, (_, i) => `Player${i}`);
    expect(completeFromDictionary('give ', DICTIONARY, many, 10)).toHaveLength(10);
  });

  it('за пределами объявленных позиций текст продолжается текстом', () => {
    const kick = DICTIONARY.find((c) => c.name === 'kick')!;
    expect(argKindAt(kick, 1)).toBe('text');
    expect(argKindAt(kick, 5)).toBe('text');
    const give = DICTIONARY.find((c) => c.name === 'give')!;
    expect(argKindAt(give, 9)).toBe('value');
  });
});

describe('словарь команд сервера', () => {
  it('имена уникальны', () => {
    const names = MINECRAFT_SERVER_COMMANDS.map((c) => c.name);
    expect(new Set(names).size).toBe(names.length);
  });

  it('пишутся без ведущего слэша — в консоли его нет', () => {
    for (const command of MINECRAFT_SERVER_COMMANDS) {
      expect(command.name.startsWith('/')).toBe(false);
      expect(command.name).toMatch(/^[a-z][a-z0-9-]*$/);
    }
  });

  it('у команд с целью-игроком отмечена позиция ника', () => {
    // Регрессия на самую вероятную ошибку в этом списке: сместить позицию.
    const byName = new Map(MINECRAFT_SERVER_COMMANDS.map((c) => [c.name, c]));
    expect(byName.get('kick')?.args[0]).toBe('player');
    expect(byName.get('ban')?.args[0]).toBe('player');
    expect(byName.get('tp')?.args).toEqual(['player', 'player']);
    // gamemode <mode> <target> — ник вторым, а не первым.
    expect(byName.get('gamemode')?.args).toEqual(['value', 'player']);
    // effect give <targets> <effect> — ник третьим словом, то есть args[1].
    expect(byName.get('effect')?.args[1]).toBe('player');
  });
});

describe('команды каталога для автодополнения', () => {
  it('берутся из шаблонов каталога, а не из отдельного списка', () => {
    const names = catalogConsoleCommands().map((c) => c.name);
    // heal — из ess-heal, kit — из ess-kit: оба живут только в каталоге.
    expect(names).toContain('heal');
    expect(names).toContain('kit');
  });

  it('содержат только команды плагинов — ванильные уже есть в словаре сервера', () => {
    const serverNames = new Set(MINECRAFT_SERVER_COMMANDS.map((c) => c.name));
    for (const command of catalogConsoleCommands()) {
      expect({ name: command.name, plugin: command.plugin }).toEqual({
        name: command.name,
        plugin: expect.any(String),
      });
      // say/title/kill и прочая ваниль из каталога сюда попасть не должна.
      const fromVanillaCatalog = MINECRAFT_QUICK_COMMANDS.some(
        (c) =>
          c.plugin === null &&
          (Array.isArray(c.template) ? c.template : [c.template]).some(
            (t) => t.split(/\s+/)[0] === command.name,
          ),
      );
      expect({ name: command.name, fromVanillaCatalog }).toEqual({
        name: command.name,
        fromVanillaCatalog: serverNames.has(command.name) ? fromVanillaCatalog : false,
      });
    }
  });

  it('позиции ников восстановлены из объявленных аргументов', () => {
    const byName = new Map(catalogConsoleCommands().map((c) => [c.name, c]));
    // heal {player} — ник первым.
    expect(byName.get('heal')?.args).toEqual(['player']);
    // kit {kit} {player} — ник вторым.
    expect(byName.get('kit')?.args).toEqual(['value', 'player']);
  });

  it('имена команд не содержат плейсхолдеров', () => {
    for (const command of catalogConsoleCommands()) {
      expect(command.name).not.toContain('{');
    }
  });
});
