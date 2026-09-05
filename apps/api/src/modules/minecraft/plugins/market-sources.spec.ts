process.env.NODE_ENV = 'test';

import { createServer, type Server } from 'http';
import { AddressInfo } from 'net';
import {
  EMPTY_FILTERS,
  MARKET_SOURCES,
  defaultProjectTypeFor,
  loadersFor,
  passesFilters,
  sortHits,
  sourcesFor,
  type MarketHitDto,
} from '@aurum/shared';

/**
 * Маркет: моды, три источника, сортировка и комбинируемые фильтры.
 *
 * ЧТО ЗДЕСЬ ЗАКРЕПЛЕНО И ПОЧЕМУ ЭТО ВАЖНО:
 *
 *   1. SpiGet — сторонняя обёртка над SpigotMC, у которого своего API нет
 *      вовсе. Она падает, таймаутит и отдаёт мусор чаще остальных. Отдельный
 *      набор тестов проверяет, что её падение НЕ обнуляет весь поиск: выдача
 *      Modrinth и Hangar доезжает, а про упавший источник честно сказано.
 *
 *   2. Фильтры комбинируемые, и пустой фильтр означает «без ограничения».
 *      Перепутать это местами значит показать человеку пустой экран ровно в
 *      тот момент, когда он снял последнюю галочку, чтобы увидеть больше.
 *
 *   3. Моды и плагины разделены по источникам не на глаз: у Hangar в его
 *      собственном OpenAPI перечисление платформ это ровно PAPER, WATERFALL и
 *      VELOCITY, а SpigotMC — площадка Bukkit. Если кто-то соберётся добавить
 *      их на вкладку модов, сначала сломается этот тест.
 */

// ------------------------------------------------------- Стенд источников

interface StubState {
  modrinth: (url: URL) => { status: number; body: unknown };
  hangar: (url: URL) => { status: number; body: unknown };
  spiget: (url: URL) => { status: number; body: unknown; headers?: Record<string, string> };
  seen: string[];
}

let server: Server;
let state: StubState;
let MarketService: typeof import('./market.service').MarketService;
let isSafeDownloadUrl: typeof import('./market.service').isSafeDownloadUrl;

const okHit = {
  project_id: 'ABC',
  slug: 'sodium',
  title: 'Sodium',
  description: 'Оптимизация рендера',
  downloads: 100,
  categories: ['fabric', 'optimization'],
  versions: ['1.21'],
  date_modified: '2025-01-01T00:00:00Z',
};

beforeAll(async () => {
  server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://stub');
    state.seen.push(`${url.pathname}${url.search}`);

    const handler = url.pathname.startsWith('/modrinth')
      ? state.modrinth
      : url.pathname.startsWith('/hangar')
        ? state.hangar
        : state.spiget;

    const answer = handler(url);
    res.writeHead(answer.status, {
      'content-type': 'application/json',
      ...(answer as { headers?: Record<string, string> }).headers,
    });
    res.end(JSON.stringify(answer.body));
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;

  // Адреса подставляются ДО импорта сервиса: он читает их из env один раз,
  // на загрузке модуля.
  process.env.MODRINTH_BASE_URL = `http://127.0.0.1:${port}/modrinth`;
  process.env.HANGAR_BASE_URL = `http://127.0.0.1:${port}/hangar`;
  process.env.SPIGET_BASE_URL = `http://127.0.0.1:${port}/spiget`;

  const mod = await import('./market.service');
  MarketService = mod.MarketService;
  isSafeDownloadUrl = mod.isSafeDownloadUrl;
});

afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));

beforeEach(() => {
  state = {
    seen: [],
    modrinth: () => ({ status: 200, body: { hits: [okHit], total_hits: 1 } }),
    hangar: () => ({
      status: 200,
      body: {
        pagination: { count: 1 },
        result: [
          {
            name: 'LuckPerms',
            namespace: { owner: 'luck', slug: 'luckperms' },
            description: 'Права',
            stats: { downloads: 500 },
            supportedPlatforms: { PAPER: ['1.21'] },
            lastUpdated: '2025-02-01T00:00:00Z',
          },
        ],
      },
    }),
    spiget: () => ({
      status: 200,
      headers: { 'x-page-count': '3' },
      body: [
        {
          id: 6245,
          name: 'Vault',
          tag: 'Абстракция экономики &amp; прав',
          downloads: 900,
          testedVersions: ['1.20', '1.21'],
          icon: { url: 'data/resource_icons/6/6245.jpg' },
          external: false,
          premium: false,
          updateDate: 1_700_000_000,
          version: { id: 42 },
          file: { type: '.jar' },
        },
      ],
    }),
  };
  // Каждый тест начинает с чистой службы: у неё есть кэш справочника версий,
  // и без этого второй тест читал бы ответ первого.
  jest.resetModules();
});

const options = (over: Partial<Parameters<InstanceType<typeof MarketService>['search']>[3]> = {}) =>
  ({ type: 'plugin' as const, sort: 'relevance' as const, filters: EMPTY_FILTERS, ...over });

// --------------------------------------------------- Устойчивость к SpiGet

describe('падение одного источника не роняет маркет', () => {
  it('SpiGet ответил 500 — Modrinth и Hangar доезжают', async () => {
    state.spiget = () => ({ status: 500, body: { error: 'boom' } });
    const res = await new MarketService().search('vault', 20, 0, options());

    expect(res.hits.map((h) => h.source).sort()).toEqual(['hangar', 'modrinth']);
    expect(res.sources.find((s) => s.source === 'spiget')).toEqual({
      source: 'spiget',
      ok: false,
      error: 'market.err.sourceSilent',
      total: 0,
    });
    expect(res.sources.filter((s) => s.ok).map((s) => s.source).sort()).toEqual([
      'hangar',
      'modrinth',
    ]);
  });

  it('SpiGet вернул не список, а объект с ошибкой — тоже не роняет', async () => {
    // Реальный случай: обёртка иногда отвечает 200 и телом вида {error: ...}.
    // Слепой .map по такому ответу свалил бы весь поиск.
    state.spiget = () => ({ status: 200, body: { error: 'rate limited' } });
    const res = await new MarketService().search('vault', 20, 0, options());

    expect(res.hits.length).toBe(2);
    expect(res.sources.find((s) => s.source === 'spiget')?.ok).toBe(false);
  });

  it('битый ресурс в списке SpiGet отбрасывается, соседние остаются', async () => {
    state.spiget = () => ({
      status: 200,
      body: [
        { id: 0, name: 'без id' },
        { id: 7, name: '' },
        null,
        { id: 8, name: 'Живой', downloads: 3 },
      ],
    });
    const res = await new MarketService().search('', 20, 0, options());
    const spiget = res.hits.filter((h) => h.source === 'spiget');

    expect(spiget.map((h) => h.title)).toEqual(['Живой']);
    expect(res.sources.find((s) => s.source === 'spiget')?.ok).toBe(true);
  });

  it('легли все три — поиск возвращает пусто, но не бросает', async () => {
    state.modrinth = () => ({ status: 503, body: {} });
    state.hangar = () => ({ status: 503, body: {} });
    state.spiget = () => ({ status: 503, body: {} });

    const res = await new MarketService().search('vault', 20, 0, options());
    expect(res.hits).toEqual([]);
    expect(res.sources.every((s) => !s.ok)).toBe(true);
  });

  it('текст ошибки источника наружу не уезжает', async () => {
    // В сообщении бывает и адрес, и кусок чужого ответа. Человеку важно одно:
    // этот источник молчит.
    state.spiget = () => ({ status: 500, body: { error: 'http://10.0.0.2:8085 refused' } });
    const res = await new MarketService().search('x', 20, 0, options());
    // Наружу уезжает ключ словаря, а не текст: важно, что в нём нет ни
    // адреса, ни куска чужого ответа.
    expect(res.sources.find((s) => s.source === 'spiget')?.error).toBe('market.err.sourceSilent');
  });
});

// --------------------------------------------------------- Разбор SpiGet

describe('разбор ответа SpiGet', () => {
  it('даты в секундах превращаются в ISO, сущности разворачиваются', async () => {
    const res = await new MarketService().search('vault', 20, 0, options());
    const hit = res.hits.find((h) => h.source === 'spiget')!;

    expect(hit.updatedAt).toBe(new Date(1_700_000_000 * 1000).toISOString());
    expect(hit.description).toBe('Абстракция экономики & прав');
    expect(hit.title).toBe('Vault');
  });

  it('относительный адрес иконки достраивается до spigotmc.org', async () => {
    const res = await new MarketService().search('vault', 20, 0, options());
    const hit = res.hits.find((h) => h.source === 'spiget')!;
    expect(hit.iconUrl).toBe('https://www.spigotmc.org/data/resource_icons/6/6245.jpg');
  });

  it('ядер у SpigotMC нет — loaders пустой, а не выдуманный', async () => {
    const res = await new MarketService().search('vault', 20, 0, options());
    expect(res.hits.find((h) => h.source === 'spiget')!.loaders).toEqual([]);
  });

  it('платный ресурс помечается, но из выдачи не исчезает', async () => {
    // Знать, что такой плагин существует, полезно. Прячется кнопка установки,
    // а не сам результат.
    state.spiget = () => ({
      status: 200,
      body: [{ id: 9, name: 'Платный', downloads: 1, premium: true }],
    });
    const res = await new MarketService().search('', 20, 0, options());
    const hit = res.hits.find((h) => h.source === 'spiget')!;
    expect(hit.premium).toBe(true);
  });

  it('offset пересчитывается в страницу, которая считается с единицы', async () => {
    await new MarketService().search('vault', 20, 40, options());
    const call = state.seen.find((u) => u.startsWith('/spiget'))!;
    expect(call).toContain('page=3');
    expect(call).toContain('size=20');
  });

  it('без строки поиска берётся список бесплатных', async () => {
    // Платный ресурс панель всё равно не установит; первым экраном показывать
    // то, чего нельзя сделать, — обман.
    await new MarketService().search('', 20, 0, options());
    expect(state.seen.some((u) => u.startsWith('/spiget/resources/free'))).toBe(true);
  });
});

// ------------------------------------------------------------- Моды

describe('моды', () => {
  it('на вкладке модов опрашивается только Modrinth', async () => {
    await new MarketService().search('sodium', 20, 0, options({ type: 'mod' }));
    expect(state.seen.some((u) => u.startsWith('/hangar'))).toBe(false);
    expect(state.seen.some((u) => u.startsWith('/spiget'))).toBe(false);
    expect(state.seen.some((u) => u.startsWith('/modrinth'))).toBe(true);
  });

  it('запрос модов сужается загрузчиками, а не одним project_type', async () => {
    // project_type:mod у Modrinth покрывает и плагины Bukkit: «plugin» там
    // выводится из загрузчика. Без второго условия вкладка модов показывала бы
    // плагины вперемешку.
    await new MarketService().search('sodium', 20, 0, options({ type: 'mod' }));
    const call = decodeURIComponent(state.seen.find((u) => u.startsWith('/modrinth'))!);

    expect(call).toContain('"project_type:mod"');
    for (const loader of loadersFor('mod')) {
      expect(call).toContain(`categories:${loader}`);
    }
  });

  it('отмеченный загрузчик сужает OR-группу до него', async () => {
    await new MarketService().search(
      '',
      20,
      0,
      options({ type: 'mod', filters: { ...EMPTY_FILTERS, loaders: ['fabric'] } }),
    );
    const call = decodeURIComponent(state.seen.find((u) => u.startsWith('/modrinth'))!);
    expect(call).toContain('["categories:fabric"]');
    expect(call).not.toContain('categories:forge');
  });

  it('загрузчик из categories попадает в loaders, а не остаётся категорией', async () => {
    const res = await new MarketService().search('sodium', 20, 0, options({ type: 'mod' }));
    const hit = res.hits[0]!;
    expect(hit.loaders).toEqual(['fabric']);
    expect(hit.categories).toEqual(['optimization']);
    expect(hit.projectType).toBe('mod');
  });
});

// ---------------------------------------------------------- Фильтры

describe('комбинируемые фильтры', () => {
  const hit = (over: Partial<MarketHitDto>): MarketHitDto => ({
    source: 'modrinth',
    id: 'x',
    slug: 'x',
    title: 'X',
    description: '',
    author: null,
    downloads: 0,
    iconUrl: null,
    categories: [],
    gameVersions: [],
    loaders: [],
    updatedAt: null,
    pageUrl: '',
    projectType: 'plugin',
    ...over,
  });

  it('пустой фильтр пропускает всё', () => {
    expect(passesFilters(hit({}), EMPTY_FILTERS)).toBe(true);
  });

  it('внутри группы значения складываются по «или»', () => {
    const filters = { ...EMPTY_FILTERS, gameVersions: ['1.20', '1.21'] };
    expect(passesFilters(hit({ gameVersions: ['1.21'] }), filters)).toBe(true);
    expect(passesFilters(hit({ gameVersions: ['1.19'] }), filters)).toBe(false);
  });

  it('между группами — по «и»', () => {
    const filters = { ...EMPTY_FILTERS, gameVersions: ['1.21'], loaders: ['paper'] };
    expect(passesFilters(hit({ gameVersions: ['1.21'], loaders: ['paper'] }), filters)).toBe(true);
    expect(passesFilters(hit({ gameVersions: ['1.21'], loaders: ['velocity'] }), filters)).toBe(
      false,
    );
  });

  it('версия сравнивается по ветке: 1.21 покрывает 1.21.4', () => {
    const filters = { ...EMPTY_FILTERS, gameVersions: ['1.21.4'] };
    expect(passesFilters(hit({ gameVersions: ['1.21'] }), filters)).toBe(true);
  });

  it('фильтр по ядру не выкидывает результаты без объявленных ядер', () => {
    // Ровно случай SpigotMC: ядер он не хранит, и строгий фильтр спрятал бы
    // источник целиком по одной галочке.
    const filters = { ...EMPTY_FILTERS, loaders: ['paper'] };
    expect(passesFilters(hit({ source: 'spiget', loaders: [] }), filters)).toBe(true);
  });

  it('фильтр по источнику отсекает по источнику', () => {
    const filters = { ...EMPTY_FILTERS, sources: ['hangar' as const] };
    expect(passesFilters(hit({ source: 'hangar' }), filters)).toBe(true);
    expect(passesFilters(hit({ source: 'modrinth' }), filters)).toBe(false);
  });

  it('выбранный источник сужает опрос, но не выводит за пределы типа', async () => {
    await new MarketService().search(
      '',
      20,
      0,
      options({ type: 'mod', filters: { ...EMPTY_FILTERS, sources: ['hangar'] } }),
    );
    // Hangar модов не отдаёт: спрашивать его нечего, и «пустой источник» в
    // ответе тоже не появляется.
    expect(state.seen.length).toBe(0);
  });
});

// -------------------------------------------------------- Сортировка

describe('сортировка сведённой выдачи', () => {
  const make = (over: Partial<MarketHitDto>): MarketHitDto => ({
    source: 'modrinth',
    id: over.title ?? 'x',
    slug: 'x',
    title: 'X',
    description: '',
    author: null,
    downloads: 0,
    iconUrl: null,
    categories: [],
    gameVersions: [],
    loaders: [],
    updatedAt: null,
    pageUrl: '',
    projectType: 'plugin',
    ...over,
  });

  const list = [
    make({ title: 'Бета', downloads: 10, updatedAt: '2024-01-01', source: 'modrinth' }),
    make({ title: 'Альфа', downloads: 300, updatedAt: '2023-01-01', source: 'hangar' }),
    make({ title: 'Гамма', downloads: 200, updatedAt: '2025-01-01', source: 'spiget' }),
  ];

  it('по загрузкам — от большего', () => {
    expect(sortHits(list, 'downloads').map((h) => h.downloads)).toEqual([300, 200, 10]);
  });

  it('по дате обновления — свежие сверху', () => {
    expect(sortHits(list, 'updated').map((h) => h.title)).toEqual(['Гамма', 'Бета', 'Альфа']);
  });

  it('по алфавиту — по-русски, без учёта регистра', () => {
    expect(sortHits(list, 'name').map((h) => h.title)).toEqual(['Альфа', 'Бета', 'Гамма']);
  });

  it('по совпадению — источники чередуются, а не идут блоками', () => {
    // Иначе первые двадцать строк были бы из Modrinth просто потому, что его
    // ответ пришёл первым, и человек решил бы, что остальные ничего не нашли.
    const many = [
      make({ title: 'm1', source: 'modrinth' }),
      make({ title: 'm2', source: 'modrinth' }),
      make({ title: 'h1', source: 'hangar' }),
      make({ title: 's1', source: 'spiget' }),
    ];
    expect(sortHits(many, 'relevance').map((h) => h.title)).toEqual(['m1', 'h1', 's1', 'm2']);
  });

  it('сортировка не теряет и не дублирует результаты', () => {
    for (const sort of ['relevance', 'downloads', 'updated', 'name'] as const) {
      expect(sortHits(list, sort)).toHaveLength(list.length);
    }
  });
});

// ---------------------------------------------- Источники и тип проекта

describe('какой источник что отдаёт', () => {
  it('моды — только Modrinth', () => {
    expect(sourcesFor('mod')).toEqual(['modrinth']);
  });

  it('плагины — все три', () => {
    expect(sourcesFor('plugin')).toEqual(['modrinth', 'hangar', 'spiget']);
  });

  it('у каждого источника объявлено, что он отдаёт', () => {
    for (const source of MARKET_SOURCES) {
      expect(source.provides.length).toBeGreaterThan(0);
    }
  });

  it('вкладка по ядру сервера', () => {
    expect(defaultProjectTypeFor('fabric')).toBe('mod');
    expect(defaultProjectTypeFor('NeoForge')).toBe('mod');
    expect(defaultProjectTypeFor('paper')).toBe('plugin');
    // Ядро неизвестно — плагины: их на свете больше, и Paper самый частый.
    expect(defaultProjectTypeFor(null)).toBe('plugin');
  });
});

// ------------------------------------------------- Куда панель ходит за файлом

describe('адрес внешнего файла', () => {
  it('внутренняя сеть закрыта', () => {
    // По ту сторону туннеля стоит домашний сервер с игрой; ссылка в
    // метаданных чужого ресурса не должна превращаться в поход туда.
    for (const url of [
      'https://10.0.0.2:8085/x.jar',
      'https://127.0.0.1/x.jar',
      'https://192.168.1.10/x.jar',
      'https://172.16.0.4/x.jar',
      'https://169.254.169.254/latest/meta-data',
      'https://100.64.0.1/x.jar',
      'https://localhost/x.jar',
      'https://[::1]/x.jar',
    ]) {
      expect(isSafeDownloadUrl(url)).toBe(false);
    }
  });

  it('http закрыт даже наружу', () => {
    expect(isSafeDownloadUrl('http://example.com/x.jar')).toBe(false);
  });

  it('обычная внешняя ссылка проходит', () => {
    expect(isSafeDownloadUrl('https://github.com/o/r/releases/download/v1/x.jar')).toBe(true);
    expect(isSafeDownloadUrl('https://cdn.modrinth.com/data/x/versions/1/x.jar')).toBe(true);
  });

  it('мусор вместо адреса не проходит', () => {
    expect(isSafeDownloadUrl('не адрес')).toBe(false);
    expect(isSafeDownloadUrl('')).toBe(false);
  });
});

// ------------------------------------------- Что именно легло на сервер

describe('проверка сигнатуры скачанного', () => {
  // Единственная защита для источников без хэша: SpigotMC не отдаёт его
  // вообще. Без неё страница «404» легла бы в plugins/ под именем плагина,
  // и сервер молча не загрузил бы его.
  let looksLikeJar: typeof import('./plugin-files.service').looksLikeJar;

  beforeAll(async () => {
    ({ looksLikeJar } = await import('./plugin-files.service'));
  });

  it('обычный jar принимается', () => {
    expect(looksLikeJar(Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00]))).toBe(true);
  });

  it('пустой архив и разбитый на тома тоже архивы', () => {
    expect(looksLikeJar(Buffer.from([0x50, 0x4b, 0x05, 0x06]))).toBe(true);
    expect(looksLikeJar(Buffer.from([0x50, 0x4b, 0x07, 0x08]))).toBe(true);
  });

  it('html-страница вместо файла отклоняется', () => {
    expect(looksLikeJar(Buffer.from('<!DOCTYPE html><html>404', 'utf8'))).toBe(false);
  });

  it('пустой ответ отклоняется', () => {
    expect(looksLikeJar(Buffer.alloc(0))).toBe(false);
    expect(looksLikeJar(Buffer.from([0x50]))).toBe(false);
  });
});
