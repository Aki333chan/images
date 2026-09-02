/**
 * История перемещения по папкам внутри вкладки «Файлы».
 *
 * <h2>Почему своя, а не история браузера</h2>
 *
 * Кнопки «назад» и «вперёд» на мыши (mouse4 и mouse5) — это обычная навигация
 * браузера, и в файловом менеджере она уводит со страницы сервера целиком,
 * хотя человек всего лишь хотел подняться на папку выше.
 *
 * Напрашивается решение «складывать папки в историю браузера через
 * pushState» — но активная вкладка сервера в адресе не хранится, она живёт в
 * состоянии компонента. Значит, записи в истории пришлось бы делать с тем же
 * адресом, а поверх этого адресом уже распоряжается react-router, который
 * держит в {@code history.state} свой служебный индекс. Дописывать туда своё
 * — способ однажды сломать роутеру восстановление прокрутки и переходы назад
 * во всей панели ради удобства на одной вкладке.
 *
 * Поэтому история папок своя и живёт в состоянии вкладки, а кнопки мыши
 * перехватываются, пока вкладка открыта. Уходит это вместе с вкладкой, и
 * ничего в панели больше не задевает.
 *
 * <h2>Как ведёт себя</h2>
 *
 * Как в любом браузере: переход после «назад» отрезает всё, что было впереди.
 * Возвращаться в ветку, из которой ушли в сторону, некуда — и делать вид, что
 * есть, значило бы кнопку «вперёд», которая ведёт непонятно куда.
 */
export interface DirHistory {
  /** Пути в порядке посещения. Всегда непустой. */
  readonly entries: readonly string[];
  /** Где мы сейчас. */
  readonly index: number;
}

export function initialHistory(path: string): DirHistory {
  return { entries: [path], index: 0 };
}

export function currentPath(history: DirHistory): string {
  return history.entries[history.index] ?? '/';
}

export function canGoBack(history: DirHistory): boolean {
  return history.index > 0;
}

export function canGoForward(history: DirHistory): boolean {
  return history.index < history.entries.length - 1;
}

/**
 * Перейти в папку.
 *
 * Повторный переход в ту же папку историю не трогает. Это не мелочь: вкладка
 * перечитывает текущую папку после загрузки файла, удаления и переименования,
 * и без этой проверки история копила бы десяток одинаковых записей, по
 * которым «назад» ходило бы на месте.
 */
export function visit(history: DirHistory, path: string): DirHistory {
  if (currentPath(history) === path) return history;
  const kept = history.entries.slice(0, history.index + 1);
  return { entries: [...kept, path], index: kept.length };
}

/** Шаг назад или {@code null}, если идти некуда. */
export function goBack(history: DirHistory): DirHistory | null {
  return canGoBack(history) ? { entries: history.entries, index: history.index - 1 } : null;
}

/** Шаг вперёд или {@code null}. */
export function goForward(history: DirHistory): DirHistory | null {
  return canGoForward(history) ? { entries: history.entries, index: history.index + 1 } : null;
}
