import type { ServerResourcesDto } from '@aurum/shared';
import { isNewRun, type ServerRuntime } from './server-runtime';

function resources(state: string, uptimeMs: number): ServerResourcesDto {
  return {
    state,
    cpuPercent: 0,
    memoryBytes: 0,
    memoryLimitBytes: 0,
    diskBytes: 0,
    diskLimitBytes: 0,
    networkRxBytes: 0,
    networkTxBytes: 0,
    uptimeMs,
  };
}

function snapshot(state: string | null, uptimeMs: number | null): ServerRuntime {
  return {
    state,
    resources: state === null || uptimeMs === null ? null : resources(state, uptimeMs),
    failed: false,
    runId: 0,
  };
}

/**
 * По этому признаку панель решает, что пора перечитать всё, что зависит от
 * запуска сервера: список плагинов, версию ядра. Ошибка в обе стороны
 * незаметна на глаз — либо вечно устаревшая таблица, либо опрос игрового
 * сервера каждые десять секунд.
 */
describe('isNewRun', () => {
  it('сервер поднялся — новый запуск', () => {
    expect(isNewRun(snapshot('offline', 0), resources('running', 5_000))).toBe(true);
  });

  it('первый ответ по уже работающему серверу — тоже новый запуск', () => {
    // Страницу открыли при работающем сервере: сравнивать не с чем, и
    // прочитать состояние плагинов надо в любом случае.
    expect(isNewRun(snapshot(null, null), resources('running', 3_600_000))).toBe(true);
  });

  it('сервер просто продолжает работать', () => {
    expect(isNewRun(snapshot('running', 60_000), resources('running', 70_000))).toBe(false);
  });

  it('упавший аптайм ловит перезапуск между опросами', () => {
    // Оба замера в running: без этой проверки перезапуск, уложившийся между
    // двумя опросами, остался бы незамеченным.
    expect(isNewRun(snapshot('running', 3_600_000), resources('running', 4_000))).toBe(true);
  });

  it('колебание аптайма на доли секунды перезапуском не считается', () => {
    expect(isNewRun(snapshot('running', 60_500), resources('running', 60_000))).toBe(false);
  });

  it('остановка новым запуском не считается', () => {
    expect(isNewRun(snapshot('running', 60_000), resources('stopping', 61_000))).toBe(false);
    expect(isNewRun(snapshot('stopping', 61_000), resources('offline', 0))).toBe(false);
  });

  it('запуск ещё идёт — ждём running', () => {
    // В starting плагины ещё не загружены: спросив сейчас, панель получила
    // бы пустой список и запомнила бы его как состояние запуска.
    expect(isNewRun(snapshot('offline', 0), resources('starting', 1_000))).toBe(false);
  });
});
