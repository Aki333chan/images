import { useEffect, useState } from 'react';
import type { AuditLogDto, Values } from '@aurum/shared';
import { api } from '../lib/api';
import { useI18n } from '../i18n';
import { Button, Card, Input, Spinner } from '../components/ui';

/**
 * Метаданные записи одной строкой — и не длиннее, чем можно прочесть.
 *
 * Предел не про красоту, а про то, чтобы страница открылась. До исправления
 * интерцептора в metadata записи о загрузке файла попадало содержимое файла
 * целиком — сотни мегабайт JSON, — и попытка отрисовать такую строку вешала
 * браузер. Старые записи чистятся скриптом (deploy/scripts/clean-audit-blobs.sh),
 * но журнал должен открываться и до того, как до него дошли руки.
 */
const MAX_METADATA_CHARS = 500;

/**
 * Метаданные записи одной строкой.
 *
 * Переводчик приходит аргументом: функция живёт вне компонента, а два её
 * служебных пояснения человек всё-таки читает.
 */
function metadataText(metadata: unknown, t: (key: string, values?: Values) => string): string {
  if (metadata == null) return '';
  let text: string;
  try {
    text = JSON.stringify(metadata);
  } catch {
    // Циклическая ссылка или что-то ещё неожиданное: журнал всё равно должен
    // показаться, пусть и без этой ячейки.
    return t('audit.unreadable');
  }
  if (text.length <= MAX_METADATA_CHARS) return text;
  return `${text.slice(0, MAX_METADATA_CHARS)}… ${t('audit.more', {
    count: text.length - MAX_METADATA_CHARS,
  })}`;
}

export function AuditPage() {
  const { t, formatDateTime } = useI18n();
  const [items, setItems] = useState<AuditLogDto[] | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [action, setAction] = useState('');
  const [targetType, setTargetType] = useState('');
  const pageSize = 50;

  function load(p = page) {
    const qs = new URLSearchParams({
      page: String(p),
      pageSize: String(pageSize),
      ...(action ? { action } : {}),
      ...(targetType ? { targetType } : {}),
    });
    void api<{ items: AuditLogDto[]; total: number }>(`/api/audit?${qs}`).then((r) => {
      setItems(r.items);
      setTotal(r.total);
    });
  }

  useEffect(() => load(1), []); // eslint-disable-line react-hooks/exhaustive-deps

  if (!items) return <Spinner />;

  return (
    <div>
      <h1 className="mb-4 text-xl font-bold">{t('nav.audit')}</h1>
      <Card className="mb-4 flex flex-wrap items-end gap-3">
        {/* min-w-0 + flex-1: поля делят строку на десктопе и занимают всю
            ширину на телефоне, а не вылезают за край фиксированной шириной. */}
        <div className="min-w-[12rem] flex-1">
          <p className="mb-1 text-xs text-muted">{t('audit.actionContains')}</p>
          <Input
            value={action}
            onChange={(e) => setAction(e.target.value)}
            placeholder="POST /api/tickets"
          />
        </div>
        <div className="min-w-[12rem] flex-1">
          <p className="mb-1 text-xs text-muted">{t('audit.targetType')}</p>
          <Input
            value={targetType}
            onChange={(e) => setTargetType(e.target.value)}
            placeholder="servers"
          />
        </div>
        <Button
          size="sm"
          onClick={() => {
            setPage(1);
            load(1);
          }}
        >
          {t('audit.filter')}
        </Button>
      </Card>

      <Card>
        {items.length === 0 && <p className="py-4 text-center text-muted">{t('audit.empty')}</p>}

        {/* Пять колонок, из которых три — технические строки: на телефоне
            такая таблица читается только прокруткой вбок. С lg показываем
            её как есть, ниже — карточками. */}
        <div className="hidden overflow-x-auto lg:block">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="pb-2 pr-4">{t('audit.time')}</th>
                <th className="pb-2 pr-4">{t('audit.actor')}</th>
                <th className="pb-2 pr-4">{t('audit.action')}</th>
                <th className="pb-2 pr-4">{t('audit.target')}</th>
                <th className="pb-2">{t('audit.metadata')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => (
                <tr key={row.id} className="border-t border-border align-top">
                  <td className="whitespace-nowrap py-2 pr-4 text-xs text-muted">
                    {formatDateTime(row.createdAt)}
                  </td>
                  <td className="py-2 pr-4">
                    {row.actorType === 'ai'
                      ? '🤖 AI'
                      : (row.actorEmail ?? row.actorId ?? t('audit.system'))}
                  </td>
                  <td className="py-2 pr-4 font-mono text-xs">{row.action}</td>
                  <td className="py-2 pr-4 text-xs">
                    {row.targetType ?? '—'}
                    {row.targetId ? ` / ${row.targetId.slice(0, 8)}` : ''}
                  </td>
                  <td className="max-w-md truncate py-2 font-mono text-[10px] text-muted">
                    {metadataText(row.metadata, t)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <ul className="space-y-2 lg:hidden">
          {items.map((row) => (
            <li key={row.id} className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-baseline justify-between gap-x-2 gap-y-1">
                <span className="break-all font-mono text-xs">{row.action}</span>
                <span className="shrink-0 text-[11px] text-muted">
                  {formatDateTime(row.createdAt)}
                </span>
              </div>
              <p className="mt-1 break-all text-xs text-muted">
                {row.actorType === 'ai'
                  ? '🤖 AI'
                  : (row.actorEmail ?? row.actorId ?? t('audit.system'))}
                {row.targetType ? ` → ${row.targetType}` : ''}
                {row.targetId ? ` / ${row.targetId.slice(0, 8)}` : ''}
              </p>
              {/* Именно != null, а не просто row.metadata: тип поля — unknown,
                  и в JSX такое значение попасть не может. */}
              {row.metadata != null && (
                // break-all и перенос: метаданные — это JSON одной строкой,
                // и без переноса он растянул бы страницу по горизонтали.
                <p className="mt-1 whitespace-pre-wrap break-all font-mono text-[10px] text-muted">
                  {metadataText(row.metadata, t)}
                </p>
              )}
            </li>
          ))}
        </ul>
      </Card>

      <div className="mt-3 flex items-center gap-3 text-sm text-muted">
        <Button
          size="sm"
          variant="outline"
          disabled={page <= 1}
          onClick={() => {
            const p = page - 1;
            setPage(p);
            load(p);
          }}
        >
          {t('audit.prev')}
        </Button>
        <span>
          {t('audit.page', { page, pages: Math.max(1, Math.ceil(total / pageSize)) })}
        </span>
        <Button
          size="sm"
          variant="outline"
          disabled={page >= Math.ceil(total / pageSize)}
          onClick={() => {
            const p = page + 1;
            setPage(p);
            load(p);
          }}
        >
          {t('audit.next')}
        </Button>
      </div>
    </div>
  );
}
