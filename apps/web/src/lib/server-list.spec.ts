import type { ServerDto, ServerMetricsDto } from '@aurum/shared';
import { filterServers, reorder, sortServers, type ServerRow } from './server-list';

/**
 * Сортировка и фильтр списка серверов.
 *
 * Проверяются в первую очередь случаи «данных нет»: у сервера может не быть
 * снимка (крон ещё не дошёл), модуля (не назначен) или счётчика игроков (игра
 * его не отдаёт). Именно на них сортировка обычно и ломается — молча
 * приравнивая «не знаем» к нулю и выталкивая живой сервер вниз.
 */

function row(
  name: string,
  over: Partial<ServerMetricsDto> | null = {},
  server: Partial<ServerDto> = {},
): ServerRow {
  return {
    server: {
      id: name,
      pteroIdentifier: name,
      name,
      description: null,
      node: null,
      address: null,
      ip: null,
      port: null,
      status: 'active',
      moduleId: 'minecraft',
      ...server,
    },
    metrics:
      over === null
        ? null
        : {
            serverId: name,
            state: 'running',
            cpuAbsolutePercent: 0,
            cpuLimitPercent: 100,
            memoryBytes: 0,
            memoryLimitBytes: 0,
            playersOnline: 0,
            playersMax: 20,
            sampledAt: '2026-08-24T12:00:00Z',
            ...over,
          },
  };
}

const names = (rows: ServerRow[]) => rows.map((r) => r.server.name);

describe('сортировка списка серверов', () => {
  it('по имени — по-русски и без учёта регистра', () => {
    const rows = [row('Яма'), row('альфа'), row('Бета')];
    expect(names(sortServers(rows, 'name', []))).toEqual(['альфа', 'Бета', 'Яма']);
  });

  it('«сначала онлайн» поднимает запущенные', () => {
    const rows = [row('выключен', { state: 'offline' }), row('работает', { state: 'running' })];
    expect(names(sortServers(rows, 'status', []))).toEqual(['работает', 'выключен']);
  });

  it('внутри одного состояния порядок стабильный — по имени', () => {
    // Без этого одинаково выключенные серверы прыгали бы местами при каждом
    // обновлении метрик.
    const rows = [row('Гамма', { state: 'offline' }), row('Альфа', { state: 'offline' })];
    expect(names(sortServers(rows, 'status', []))).toEqual(['Альфа', 'Гамма']);
  });

  it('сервер без снимка считается не онлайн', () => {
    const rows = [row('без данных', null), row('работает', { state: 'running' })];
    expect(names(sortServers(rows, 'status', []))).toEqual(['работает', 'без данных']);
  });

  it('по игрокам — от большего', () => {
    const rows = [row('мало', { playersOnline: 2 }), row('много', { playersOnline: 30 })];
    expect(names(sortServers(rows, 'players', []))).toEqual(['много', 'мало']);
  });

  it('«игроков не знаем» уходит вниз, а не приравнивается к нулю', () => {
    // Сервер, до которого не достучались, не должен оказаться выше живого
    // сервера с двумя игроками только потому, что null посчитали нулём.
    const rows = [
      row('неизвестно', { playersOnline: null }),
      row('пустой', { playersOnline: 0 }),
      row('живой', { playersOnline: 2 }),
    ];
    expect(names(sortServers(rows, 'players', []))).toEqual(['живой', 'пустой', 'неизвестно']);
  });

  it('по игре, внутри игры — по имени', () => {
    const rows = [
      row('Б', {}, { moduleId: 'palworld' }),
      row('А', {}, { moduleId: 'palworld' }),
      row('В', {}, { moduleId: 'minecraft' }),
    ];
    expect(names(sortServers(rows, 'game', []))).toEqual(['В', 'А', 'Б']);
  });

  it('серверы без модуля — в конце', () => {
    const rows = [row('без модуля', {}, { moduleId: null }), row('с модулем')];
    expect(names(sortServers(rows, 'game', []))).toEqual(['с модулем', 'без модуля']);
  });
});

describe('свой порядок', () => {
  it('карточки идут так, как их расставили', () => {
    const rows = [row('А'), row('Б'), row('В')];
    expect(names(sortServers(rows, 'manual', ['В', 'А', 'Б']))).toEqual(['В', 'А', 'Б']);
  });

  it('новый сервер не исчезает, а встаёт в конец', () => {
    // Появившийся после синхронизации сервер в сохранённом порядке
    // отсутствует. Спрятать его до первого перетаскивания значило бы потерять
    // сервер из виду ровно тогда, когда он появился.
    const rows = [row('А'), row('Б'), row('новый')];
    expect(names(sortServers(rows, 'manual', ['Б', 'А']))).toEqual(['Б', 'А', 'новый']);
  });

  it('пропавшие id в сохранённом порядке не мешают', () => {
    const rows = [row('А'), row('Б')];
    expect(names(sortServers(rows, 'manual', ['удалённый', 'Б', 'А']))).toEqual(['Б', 'А']);
  });

  it('порядок не задан — по алфавиту, а не как попало', () => {
    const rows = [row('Б'), row('А')];
    expect(names(sortServers(rows, 'manual', []))).toEqual(['А', 'Б']);
  });
});

describe('перестановка карточки', () => {
  it('перенос вниз не промахивается на единицу', () => {
    // Классическая ошибка: элемент сначала вынимается, и всё правее сдвигается.
    expect(reorder(['а', 'б', 'в', 'г'], 0, 2)).toEqual(['б', 'в', 'а', 'г']);
  });

  it('перенос вверх', () => {
    expect(reorder(['а', 'б', 'в'], 2, 0)).toEqual(['в', 'а', 'б']);
  });

  it('перенос на себя ничего не меняет', () => {
    const list = ['а', 'б'];
    expect(reorder(list, 1, 1)).toBe(list);
  });

  it('индекс за пределами списка не ломает порядок', () => {
    const list = ['а', 'б'];
    expect(reorder(list, 0, 9)).toBe(list);
    expect(reorder(list, -1, 0)).toBe(list);
  });
});

describe('поиск по списку', () => {
  it('находит по части имени без учёта регистра', () => {
    const rows = [row('Выживание'), row('Креатив')];
    expect(names(filterServers(rows, 'выжив'))).toEqual(['Выживание']);
  });

  it('ищет и по адресу: половину серверов помнят именно по нему', () => {
    const rows = [row('А', {}, { address: 'play.aurumgg.ovh:25565' }), row('Б')];
    expect(names(filterServers(rows, 'aurumgg'))).toEqual(['А']);
  });

  it('пустой запрос возвращает всё, а не ничего', () => {
    const rows = [row('А'), row('Б')];
    expect(filterServers(rows, '   ')).toHaveLength(2);
  });
});
