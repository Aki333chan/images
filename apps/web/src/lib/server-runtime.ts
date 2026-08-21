import { useEffect, useState } from 'react';
import type { ServerResourcesDto } from '@aurum/shared';
import { api } from './api';

/**
 * Живое состояние сервера — один опрос на всех.
 *
 * Состояние питания нужно сразу нескольким блокам страницы: шапке (значок
 * running/offline), полосе метрик и списку поддерживаемых плагинов, который
 * должен обновиться после запуска сервера. Если бы каждый спрашивал сам, на
 * одну открытую страницу приходилось бы три запроса в те же десять секунд, а
 * блоки показывали бы состояние с разбегом до интервала опроса.
 *
 * Поэтому опрос ровно один на сервер, а подписчики получают один и тот же
 * снимок. Пока подписчиков нет, опрос не идёт вовсе.
 */

/** Реже нет смысла: Pterodactyl сам снимает показания с интервалом. */
export const RUNTIME_POLL_MS = 10_000;

export interface ServerRuntime {
  /** Показания Pterodactyl или null, пока ответа не было. */
  resources: ServerResourcesDto | null;
  /** running / offline / starting / stopping; null — ещё не спрашивали. */
  state: string | null;
  /** Последний опрос не удался. Прошлые показания при этом сохраняются. */
  failed: boolean;
  /**
   * Номер запуска сервера. Растёт на каждом старте — по нему видно, что
   * сервер перезапустили, и можно перечитать всё, что зависит от запуска:
   * список плагинов, версию ядра.
   */
  runId: number;
}

const EMPTY: ServerRuntime = { resources: null, state: null, failed: false, runId: 0 };

/**
 * Тот же сервер или уже другой запуск.
 *
 * Две приметы, и обе нужны. Переход в running ловит обычный старт. Упавший
 * аптайм ловит случай, когда сервер успел выключиться и подняться между
 * двумя опросами: состояние в обоих снимках running, и по нему одному
 * перезапуск неотличим от простого продолжения работы.
 *
 * Чистая функция и экспортируется ради тестов: ошибка здесь означала бы
 * либо вечно устаревший список плагинов, либо его перечитывание каждые
 * десять секунд, и заметить это на глаз тяжело.
 */
export function isNewRun(prev: ServerRuntime, next: ServerResourcesDto): boolean {
  const isUp = next.state === 'running';
  if (!isUp) return false;
  if (prev.state !== 'running') return true;
  // Секунда допуска: аптайм приходит округлённым, и соседние замеры могут
  // разойтись на доли секунды в обратную сторону без всякого перезапуска.
  return prev.resources !== null && next.uptimeMs + 1_000 < prev.resources.uptimeMs;
}

interface Entry {
  snapshot: ServerRuntime;
  listeners: Set<(value: ServerRuntime) => void>;
  timer: ReturnType<typeof setInterval> | null;
  /** Запрос уже в пути: на медленной сети опросы иначе наложатся друг на друга. */
  inFlight: boolean;
}

const entries = new Map<string, Entry>();

function emit(entry: Entry) {
  for (const listener of entry.listeners) listener(entry.snapshot);
}

async function poll(serverId: string) {
  const entry = entries.get(serverId);
  if (!entry || entry.inFlight) return;
  entry.inFlight = true;
  try {
    const resources = await api<ServerResourcesDto>(`/api/servers/${serverId}/resources`);
    const prev = entry.snapshot;
    entry.snapshot = {
      resources,
      state: resources.state,
      failed: false,
      runId: prev.runId + (isNewRun(prev, resources) ? 1 : 0),
    };
    emit(entry);
  } catch {
    // Прошлые показания намеренно остаются на месте: одна неудачная попытка
    // — это чаще всего моргнувшая сеть, и гасить из-за неё всю полосу метрик
    // значит пугать человека там, где ничего не случилось.
    entry.snapshot = { ...entry.snapshot, failed: true };
    emit(entry);
  } finally {
    entry.inFlight = false;
  }
}

function startTimer(serverId: string, entry: Entry) {
  if (entry.timer !== null) return;
  entry.timer = setInterval(() => void poll(serverId), RUNTIME_POLL_MS);
}

function stopTimer(entry: Entry) {
  if (entry.timer === null) return;
  clearInterval(entry.timer);
  entry.timer = null;
}

/**
 * На скрытой вкладке опрос останавливается целиком.
 *
 * Дело не только в экономии: браузер притормаживает таймеры фоновой вкладки,
 * и накопленные за это время запросы уходят пачкой в момент возврата — а
 * вместе с ними приходит и пачка ошибок, если за время отсутствия что-то
 * успело оборваться. Возврат на вкладку вместо этого делает ровно один
 * свежий опрос.
 */
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    for (const [serverId, entry] of entries) {
      if (entry.listeners.size === 0) continue;
      if (document.hidden) {
        stopTimer(entry);
      } else {
        void poll(serverId);
        startTimer(serverId, entry);
      }
    }
  });
}

/**
 * Спросить состояние немедленно, не дожидаясь очередного тика.
 *
 * Нужно после нажатия «Старт» или «Стоп»: ждать до десяти секунд, чтобы
 * значок наконец сменился, — это ровно то ощущение «кнопка не сработала»,
 * из-за которого её жмут второй раз.
 */
export function refreshServerRuntime(serverId: string): void {
  void poll(serverId);
}

/** Живое состояние сервера с подпиской на общий опрос. */
export function useServerRuntime(serverId: string): ServerRuntime {
  const [value, setValue] = useState<ServerRuntime>(
    () => entries.get(serverId)?.snapshot ?? EMPTY,
  );

  useEffect(() => {
    const existing = entries.get(serverId);
    const entry: Entry = existing ?? {
      snapshot: EMPTY,
      listeners: new Set(),
      timer: null,
      inFlight: false,
    };
    if (!existing) entries.set(serverId, entry);
    const listener = (next: ServerRuntime) => setValue(next);
    entry.listeners.add(listener);
    // Уже известное отдаём сразу — второй блок на той же странице не должен
    // ждать следующего тика, чтобы узнать то, что первый уже выяснил.
    setValue(entry.snapshot);

    if (!document.hidden) {
      void poll(serverId);
      startTimer(serverId, entry);
    }

    return () => {
      entry.listeners.delete(listener);
      if (entry.listeners.size === 0) {
        stopTimer(entry);
        // Снимок оставляем: при возврате на страницу того же сервера он
        // покажется сразу, а не после первого ответа.
      }
    };
  }, [serverId]);

  return value;
}
