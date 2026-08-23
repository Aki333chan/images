import { BadRequestException } from '@nestjs/common';

/**
 * Работа с путями внутри файлового пространства сервера.
 *
 * ЗАЧЕМ ЭТО ОТДЕЛЬНО И С ТЕСТАМИ. Путь приходит из браузера, а уходит в
 * Wings, который работает с настоящей файловой системой. «..» в середине
 * строки — классический способ выйти за пределы каталога сервера. Wings
 * такое, скорее всего, отклонит и сам, но полагаться на то, что чужая
 * защита не даст сбоя, здесь неуместно: цена ошибки — чужие файлы на
 * машине.
 *
 * Правило одно: наружу и внутрь ходит только нормализованный абсолютный
 * путь от корня сервера, начинающийся со слэша и без «..» вовсе.
 */

/** Максимальная длина пути. Больше — почти наверняка попытка что-то сломать. */
const MAX_PATH_LENGTH = 1024;

/**
 * Приводит путь к каноническому виду: «/», «/plugins», «/plugins/config.yml».
 *
 * Пустой путь означает корень. Хвостовой слэш убирается, повторные
 * схлопываются.
 */
export function normalizePath(raw: string | undefined | null): string {
  const input = (raw ?? '').trim();
  if (input.length > MAX_PATH_LENGTH) {
    throw new BadRequestException('Слишком длинный путь');
  }
  // Нулевой байт обрывает строку в системных вызовах: «файл.txt\0.jpg»
  // прошёл бы проверку расширения и открыл бы совсем другой файл.
  if (input.includes('\0')) {
    throw new BadRequestException('Недопустимый символ в пути');
  }

  const parts: string[] = [];
  for (const segment of input.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      // Не «поднимаемся на уровень выше», а отказываем. Подъём молча
      // превратил бы «/../../etc/passwd» в «/etc/passwd» — путь валидный
      // с виду, но означающий совсем не то, что просил пользователь.
      throw new BadRequestException('Переход вверх по дереву в пути запрещён');
    }
    parts.push(segment);
  }
  return '/' + parts.join('/');
}

/** Имя файла или папки: без слэшей и без «..». */
export function normalizeName(raw: string | undefined | null): string {
  const name = (raw ?? '').trim();
  if (name === '') throw new BadRequestException('Пустое имя');
  if (name.length > 255) throw new BadRequestException('Слишком длинное имя');
  if (name === '.' || name === '..') throw new BadRequestException('Недопустимое имя');
  if (name.includes('/') || name.includes('\0')) {
    throw new BadRequestException('Имя не может содержать «/»');
  }
  return name;
}

/** Каталог, в котором лежит путь. Для «/a/b.txt» — «/a». */
export function parentOf(path: string): string {
  const normalized = normalizePath(path);
  const at = normalized.lastIndexOf('/');
  return at <= 0 ? '/' : normalized.slice(0, at);
}

/** Последний сегмент пути. Для «/a/b.txt» — «b.txt», для «/» — пусто. */
export function baseName(path: string): string {
  const normalized = normalizePath(path);
  return normalized === '/' ? '' : normalized.slice(normalized.lastIndexOf('/') + 1);
}

/** Склейка каталога и имени с нормализацией. */
export function joinPath(directory: string, name: string): string {
  const dir = normalizePath(directory);
  return normalizePath(dir === '/' ? `/${name}` : `${dir}/${name}`);
}

/**
 * Хлебные крошки от корня до текущего каталога.
 *
 * Считаются на бэкенде, а не в интерфейсе: тогда обе стороны одинаково
 * понимают, что такое путь, и не расходятся на первом же необычном имени.
 */
export function breadcrumbsFor(path: string): { name: string; path: string }[] {
  const normalized = normalizePath(path);
  const crumbs = [{ name: 'Корень', path: '/' }];
  if (normalized === '/') return crumbs;

  let current = '';
  for (const segment of normalized.split('/').filter(Boolean)) {
    current += '/' + segment;
    crumbs.push({ name: segment, path: current });
  }
  return crumbs;
}
