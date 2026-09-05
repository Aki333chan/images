import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import ru from './catalogs/ru.json';

/**
 * Каждый ключ, который просят из кода панели, в словаре есть.
 *
 * Опечатка в имени ключа не падает и не подсвечивается типами: переводчик
 * честно вернёт сам ключ, и человек увидит на экране «mc.pd.wipeBoby». Такое
 * доживает до продакшена, потому что экран открывается редко, а выглядит
 * почти как текст.
 *
 * У API есть свой такой же тест — на ключи, которые бэкенд отдаёт наружу.
 * Здесь проверяется вторая половина: ключи, которые панель просит сама.
 *
 * Пространства имён перечислены явно. Ловить «любую строку с точкой» нельзя:
 * под это подошли бы права (`minecraft.plugins.install`), пути к файлам,
 * версии игры и имена css-классов. Список ровно совпадает с верхним уровнем
 * словаря, кроме тех имён, что заняты правами: `ai.chat`, `users.manage`,
 * `servers.view`, `tickets.view`, `audit.view`, `files.view`,
 * `backups.view`, `schedules.manage`, `databases.manage`, `startup.manage`
 * — это права RBAC, а не строки.
 */
const NAMESPACES = [
  'mc',
  'market',
  'set',
  'acc',
  'stats',
  'server',
  'onboarding',
  'msg',
  'tab',
  'sec',
  'nav',
  'common',
  'heatmap',
  'network',
  'dummy',
  'login',
  'language',
  'cron',
  'size',
  'role',
  'net',
  'res',
  'app',
  'address',
  'console',
  // С точкой после подпространства: у этих имён верхний уровень занят правами.
  'ai\\.(?:err|sum)',
  'auth\\.err',
  'users\\.err',
];

const PATTERN = new RegExp(`'((?:${NAMESPACES.join('|')})\\.[a-z][\\w.]*)'`, 'gi');

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}

describe('ключи словаря панели', () => {
  const catalog = ru as Record<string, unknown>;

  it('каждый ключ из исходников есть в русском словаре', () => {
    const used = new Set<string>();
    for (const file of walk(join(__dirname, '..'))) {
      if (!/\.tsx?$/.test(file) || /\.spec\.tsx?$/.test(file)) continue;
      for (const m of readFileSync(file, 'utf8').matchAll(PATTERN)) used.add(m[1] as string);
    }
    // Ключи вообще нашлись: пустое множество означало бы, что регулярка
    // перестала совпадать, а тест — проходить впустую.
    expect(used.size).toBeGreaterThan(300);
    expect([...used].filter((key) => !(key in catalog)).sort()).toEqual([]);
  });
});
