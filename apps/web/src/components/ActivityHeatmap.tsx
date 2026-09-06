import { useEffect, useMemo, useState } from 'react';
import type { Locale, ServerActivityDto } from '@aurum/shared';
import { LOCALE_TAGS } from '@aurum/shared';
import { useI18n } from '../i18n';
import { api } from '../lib/api';
import { Card, ErrorText, Select, Spinner } from './ui';
import { buildRows } from '../lib/heatmap';

/** Глубина истории. Подпись — форма множественного числа: у «5 дней» и «1
 * дня» она разная в русском и польском, а в английском одинаковая. */
const DAY_OPTIONS = ['5', '7', '14', '30'];

/** Переводчик аргументом: cellStyle зовётся из разметки, но живёт вне компонента. */
type Translate = (key: string, values?: Record<string, string | number>) => string;

/**
 * Цвет ячейки. Ноль игроков и «нет данных» намеренно выглядят по-разному:
 * пустой час — это «сервер не отвечал или замеров не было», и путать его с
 * «никого не было» нельзя, иначе график врёт про посещаемость.
 */
function cellStyle(
  value: number | null,
  peak: number,
  t: Translate,
): { className: string; title: string } {
  if (value === null) {
    return { className: 'bg-white/[0.04]', title: t('heatmap.noData') };
  }
  if (value === 0) {
    return { className: 'bg-white/10', title: t('heatmap.nobody') };
  }
  const ratio = peak > 0 ? value / peak : 0;
  // Четыре ступени: глазу проще сравнивать пять состояний, чем градиент.
  const step = ratio > 0.75 ? 3 : ratio > 0.5 ? 2 : ratio > 0.25 ? 1 : 0;
  // Ступени акцента, а не отдельный цвет: график активности — часть той же
  // системы, и собственная фуксия выбивалась из неё единственным пятном
  // чужого оттенка на весь интерфейс.
  const colors = ['bg-primary/30', 'bg-primary/55', 'bg-primary/80', 'bg-primary-400'];
  return {
    className: colors[step] ?? colors[0]!,
    title: t('stats.players', { count: value }),
  };
}

/**
 * День и месяц для подписи строки.
 *
 * Порядок задаёт язык, а не мы: 05.09 в России, 05/09 в Британии, 9/5 в
 * США. Зашитое «день.месяц» превратило бы сентябрь в май для половины
 * читателей — и молча, без единого признака ошибки на экране.
 */
function makeDayLabel(locale: Locale): (iso: string) => string {
  const format = new Intl.DateTimeFormat(LOCALE_TAGS[locale], {
    day: '2-digit',
    month: '2-digit',
  });
  return (iso) => format.format(new Date(`${iso}T00:00:00`));
}

/**
 * Активность игроков: сутки по строкам, часы по столбцам, цвет — по пику
 * онлайна в этот час. Данные копит сборщик, замер раз в 5 минут.
 */
export function ActivityHeatmap({ serverId }: { serverId: string }) {
  const { t, locale } = useI18n();
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
  const formatDate = useMemo(() => makeDayLabel(locale), [locale]);
  const dayOptions = DAY_OPTIONS.map((value) => ({
    value,
    label: t('heatmap.days', { count: Number(value) }),
  }));

  if (error) return <ErrorText>{error}</ErrorText>;
  if (!data) return <Spinner />;

  const hasAnyData = rows.some((r) => r.hours.some((h) => h !== null));

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold">{t('heatmap.title')}</h2>
          <p className="text-xs text-muted">
            {t('heatmap.hint')}
            {data.peak > 0 && <> {t('heatmap.peak', { peak: data.peak })}</>}
          </p>
        </div>
        <Select value={days} onChange={setDays} options={dayOptions} />
      </div>

      {!hasAnyData ? (
        <p className="text-xs text-muted">{t('heatmap.empty')}</p>
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
                    const { className, title } = cellStyle(value, data.peak, t);
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
        <span>{t('heatmap.less')}</span>
        <div className="h-3 w-3 rounded-sm bg-white/[0.04]" title={t('heatmap.noData')} />
        <div className="h-3 w-3 rounded-sm bg-white/10" title={t('heatmap.nobody')} />
        <div className="h-3 w-3 rounded-sm bg-primary/30" />
        <div className="h-3 w-3 rounded-sm bg-primary/55" />
        <div className="h-3 w-3 rounded-sm bg-primary/80" />
        <div className="h-3 w-3 rounded-sm bg-primary-400" />
        <span>{t('heatmap.more')}</span>
        <span className="ml-2">{t('heatmap.legend')}</span>
      </div>
    </Card>
  );
}
