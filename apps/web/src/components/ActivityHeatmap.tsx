import { useEffect, useMemo, useState } from 'react';
import type { ServerActivityDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Card, ErrorText, Select, Spinner } from './ui';
import { buildRows } from '../lib/heatmap';

const DAY_OPTIONS = [
  { value: '5', label: '5 дней' },
  { value: '7', label: '7 дней' },
  { value: '14', label: '14 дней' },
  { value: '30', label: '30 дней' },
];

/**
 * Цвет ячейки. Ноль игроков и «нет данных» намеренно выглядят по-разному:
 * пустой час — это «сервер не отвечал или замеров не было», и путать его с
 * «никого не было» нельзя, иначе график врёт про посещаемость.
 */
function cellStyle(value: number | null, peak: number): { className: string; title: string } {
  if (value === null) {
    return { className: 'bg-white/[0.04]', title: 'нет данных' };
  }
  if (value === 0) {
    return { className: 'bg-white/10', title: 'никого' };
  }
  const ratio = peak > 0 ? value / peak : 0;
  // Четыре ступени: глазу проще сравнивать пять состояний, чем градиент.
  const step = ratio > 0.75 ? 3 : ratio > 0.5 ? 2 : ratio > 0.25 ? 1 : 0;
  const colors = ['bg-fuchsia-500/30', 'bg-fuchsia-500/55', 'bg-fuchsia-500/80', 'bg-fuchsia-400'];
  return {
    className: colors[step] ?? colors[0]!,
    title: `${value} ${value === 1 ? 'игрок' : value < 5 ? 'игрока' : 'игроков'}`,
  };
}

function formatDate(iso: string): string {
  const [, month, day] = iso.split('-');
  return `${day}.${month}`;
}

/**
 * Активность игроков: сутки по строкам, часы по столбцам, цвет — по пику
 * онлайна в этот час. Данные копит сборщик, замер раз в 5 минут.
 */
export function ActivityHeatmap({ serverId }: { serverId: string }) {
  const [days, setDays] = useState('7');
  const [data, setData] = useState<ServerActivityDto | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setData(null);
    api<ServerActivityDto>(`/api/servers/${serverId}/activity?days=${days}`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [serverId, days]);

  const rows = useMemo(() => (data ? buildRows(data.samples, data.days) : []), [data]);

  if (error) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  const hasAnyData = rows.some((r) => r.hours.some((h) => h !== null));

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold">Активность по часам</h2>
          <p className="text-xs text-muted">
            Цвет — пик онлайна за час. Время местное, ваше.
            {data.peak > 0 && <> Максимум за период: {data.peak}.</>}
          </p>
        </div>
        <Select value={days} onChange={setDays} options={DAY_OPTIONS} />
      </div>

      {!hasAnyData ? (
        <p className="text-xs text-muted">
          Замеров пока нет. Панель опрашивает сервер раз в 5 минут — первые точки появятся в течение
          часа после того, как заработает RCON.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="border-separate border-spacing-[3px]">
            <thead>
              <tr>
                <th />
                {Array.from({ length: 24 }, (_, hour) => (
                  <th key={hour} className="text-[10px] font-normal text-muted">
                    {/* Подписываем каждый третий час, иначе шапка не читается. */}
                    {hour % 3 === 0 ? hour : ''}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.date}>
                  <td className="pr-2 text-right text-[10px] text-muted">{formatDate(row.date)}</td>
                  {row.hours.map((value, hour) => {
                    const { className, title } = cellStyle(value, data.peak);
                    return (
                      <td key={hour}>
                        <div
                          className={`h-4 w-4 rounded-sm ${className}`}
                          title={`${formatDate(row.date)}, ${String(hour).padStart(2, '0')}:00 — ${title}`}
                        />
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex items-center gap-2 text-[11px] text-muted">
        <span>меньше</span>
        <div className="h-3 w-3 rounded-sm bg-white/[0.04]" title="нет данных" />
        <div className="h-3 w-3 rounded-sm bg-white/10" title="никого" />
        <div className="h-3 w-3 rounded-sm bg-fuchsia-500/30" />
        <div className="h-3 w-3 rounded-sm bg-fuchsia-500/55" />
        <div className="h-3 w-3 rounded-sm bg-fuchsia-500/80" />
        <div className="h-3 w-3 rounded-sm bg-fuchsia-400" />
        <span>больше</span>
        <span className="ml-2">первый квадрат — данных нет, второй — никого не было</span>
      </div>
    </Card>
  );
}
