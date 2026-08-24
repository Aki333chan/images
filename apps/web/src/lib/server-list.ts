import type { ServerDto, ServerMetricsDto, ServerSort } from '@aurum/shared';

/**
 * Сортировка и фильтрация списка серверов.
 *
 * Чистые функции без React: список сортируется по живым метрикам, а это ровно
 * тот код, который ломается тихо — «сначала онлайн» незаметно перестаёт
 * работать, когда у сервера нет снимка. Проверять его надо тестами, а не
 * глазами по экрану.
 *
 * ФИЛЬТР РАБОТАЕТ НА ФРОНТЕ, и это осознанный выбор масштаба: серверов
 * десятки, а не тысячи. Ходить на бэкенд на каждое нажатие клавиши значило бы
 * добавить задержку и мигание там, где мгновенный отклик достижим даром.
 */

export interface ServerRow {
  server: ServerDto;
  metrics: ServerMetricsDto | null;
}

/** Онлайн ли сервер по последнему снимку. */
export function isOnline(row: ServerRow): boolean {
  return row.metrics?.state === 'running';
}

/**
 * Фильтр по имени.
 *
 * Ищем и по имени, и по адресу: когда серверов много, половину из них помнят
 * именно по адресу, а не по названию. Регистр и лишние пробелы игнорируются.
 */
export function filterServers(rows: ServerRow[], query: string): ServerRow[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return rows;
  return rows.filter((row) => {
    const haystack = [row.server.name, row.server.address, row.server.description]
      .filter((v): v is string => !!v)
      .join(' ')
      .toLowerCase();
    return haystack.includes(needle);
  });
}

export function sortServers(
  rows: ServerRow[],
  sort: ServerSort,
  manualOrder: string[],
): ServerRow[] {
  const list = [...rows];
  const byName = (a: ServerRow, b: ServerRow) =>
    a.server.name.localeCompare(b.server.name, 'ru', { sensitivity: 'base' });

  switch (sort) {
    case 'name':
      return list.sort(byName);

    case 'status':
      // Сначала онлайн, внутри группы — по имени: иначе одинаково выключенные
      // серверы прыгали бы местами между обновлениями метрик.
      return list.sort((a, b) => {
        const diff = Number(isOnline(b)) - Number(isOnline(a));
        return diff !== 0 ? diff : byName(a, b);
      });

    case 'players':
      // Серверы без данных об онлайне — вниз, а не в начало с нулём: «не
      // знаем» это не «пусто», и ставить их выше живого сервера с двумя
      // игроками было бы неправдой.
      return list.sort((a, b) => {
        const pa = a.metrics?.playersOnline;
        const pb = b.metrics?.playersOnline;
        if (pa === null || pa === undefined) return pb === null || pb === undefined ? byName(a, b) : 1;
        if (pb === null || pb === undefined) return -1;
        return pb - pa || byName(a, b);
      });

    case 'game':
      // По игре, а внутри игры — по имени. Серверы без модуля в конце:
      // «модуль не назначен» это не название игры.
      return list.sort((a, b) => {
        const ga = a.server.moduleId ?? '';
        const gb = b.server.moduleId ?? '';
        if (!ga && gb) return 1;
        if (ga && !gb) return -1;
        return ga.localeCompare(gb) || byName(a, b);
      });

    case 'manual':
      return sortManually(list, manualOrder, byName);
  }
}

/**
 * Свой порядок: сначала то, что человек расставил, потом всё остальное.
 *
 * Новые серверы (появившиеся после синхронизации) в сохранённом списке
 * отсутствуют и показываются в конце по алфавиту — но показываются
 * обязательно. Прятать их до тех пор, пока человек не перетащит карточку,
 * значило бы терять сервер из виду ровно в тот момент, когда он появился.
 */
function sortManually(
  rows: ServerRow[],
  order: string[],
  fallback: (a: ServerRow, b: ServerRow) => number,
): ServerRow[] {
  const position = new Map(order.map((id, index) => [id, index]));
  const known = rows.filter((r) => position.has(r.server.id));
  const fresh = rows.filter((r) => !position.has(r.server.id));
  known.sort((a, b) => position.get(a.server.id)! - position.get(b.server.id)!);
  fresh.sort(fallback);
  return [...known, ...fresh];
}

/**
 * Новый порядок после перетаскивания карточки с позиции from на позицию to.
 *
 * Отдельной функцией, потому что промах на единицу при перемещении вниз —
 * классическая ошибка: элемент сначала вынимается, и все, кто был правее,
 * сдвигаются влево.
 */
export function reorder<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) return list;
  const next = [...list];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved!);
  return next;
}
