/**
 * Разбор ответа GitHub Releases — чистыми функциями, без сети.
 *
 * Отдельным файлом, чтобы проверять его тестом: формат ответа GitHub мы не
 * контролируем, а ошибиться здесь — значит поставить на живой сервер не тот
 * файл или не поставить ничего и не объяснить почему.
 */

/** Минимум того, что нам нужно от релиза. Остальные поля GitHub игнорируем. */
export interface GithubRelease {
  tag_name?: unknown;
  draft?: unknown;
  prerelease?: unknown;
  published_at?: unknown;
  assets?: unknown;
}

export interface AddonRelease {
  /** Тег целиком: «companion-v0.3.1». */
  tag: string;
  /** Версия без префикса: «0.3.1». Показывается человеку и пишется в аудит. */
  version: string;
  fileName: string;
  downloadUrl: string;
}

/**
 * Последний релиз этого аддона.
 *
 * ЧЕРНОВИКИ И ПРЕДРЕЛИЗЫ ПРОПУСКАЮТСЯ. Черновик виден только владельцу
 * репозитория и по ссылке не скачивается вовсе; предрелиз — это то, что
 * автор пометил как «ещё не для всех», и ставить его на чужой сервер молча
 * нельзя. Оба фильтра важнее, чем кажется: релиз-черновик, забытый на день,
 * иначе уехал бы на все свежие сервера.
 *
 * Порядок берётся из ответа GitHub (он отдаёт релизы от новых к старым), но
 * на него не полагаемся: сортируем сами по дате публикации. Тег вида
 * «companion-v0.10.0» сравнивать строкой нельзя — «0.10.0» меньше «0.9.0».
 */
export function latestRelease(
  releases: unknown,
  tagPrefix: string,
): AddonRelease | null {
  if (!Array.isArray(releases)) return null;

  const suitable = releases
    .filter((r): r is GithubRelease => typeof r === 'object' && r !== null)
    .filter((r) => r.draft !== true && r.prerelease !== true)
    .filter((r) => typeof r.tag_name === 'string' && r.tag_name.startsWith(tagPrefix))
    .sort((a, b) => publishedAt(b) - publishedAt(a));

  for (const release of suitable) {
    const tag = String(release.tag_name);
    const asset = pickJar(release.assets);
    // Релиз без jar — не повод сдаваться: у аддона мог выйти релиз с одними
    // исходниками, и брать надо следующий по свежести, а не молчать.
    if (asset) {
      return {
        tag,
        version: tag.slice(tagPrefix.length),
        fileName: asset.name,
        downloadUrl: asset.url,
      };
    }
  }
  return null;
}

function publishedAt(release: GithubRelease): number {
  const raw = typeof release.published_at === 'string' ? Date.parse(release.published_at) : NaN;
  return Number.isNaN(raw) ? 0 : raw;
}

/**
 * Единственный jar релиза.
 *
 * Если их несколько — берётся первый: у наших релизов ассет один, а гадать,
 * какой из двух «правильный», панель не должна. Всё, что не .jar (исходники,
 * контрольные суммы, changelog), отсеивается.
 */
function pickJar(assets: unknown): { name: string; url: string } | null {
  if (!Array.isArray(assets)) return null;
  for (const asset of assets) {
    if (typeof asset !== 'object' || asset === null) continue;
    const record = asset as Record<string, unknown>;
    const name = record['name'];
    const url = record['browser_download_url'];
    if (typeof name !== 'string' || typeof url !== 'string') continue;
    if (!name.toLowerCase().endsWith('.jar')) continue;
    return { name, url };
  }
  return null;
}

/**
 * Стоит ли уже этот плагин.
 *
 * Сравнение по началу имени файла и без учёта регистра и знаков: в plugins/
 * он лежит как «AurumCompanion-0.3.1.jar», а руками его могли положить и как
 * «aurumcompanion.jar». Нестрогость здесь безопасна: цена ложного «стоит» —
 * человек нажмёт кнопку сам, цена ложного «не стоит» — вторая копия плагина
 * в plugins/ и отказ сервера стартовать.
 */
export function isInstalled(pluginName: string, fileNames: string[]): boolean {
  const needle = normalize(pluginName);
  return fileNames.some((file) => normalize(file).startsWith(needle));
}

function normalize(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '');
}
