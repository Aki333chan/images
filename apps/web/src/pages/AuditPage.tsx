import { useEffect, useState } from 'react';
import type { AuditLogDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Button, Card, Input, Spinner } from '../components/ui';

export function AuditPage() {
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
      <h1 className="mb-4 text-xl font-bold">Аудит-лог</h1>
      <Card className="mb-4 flex flex-wrap items-end gap-3">
        <div>
          <p className="mb-1 text-xs text-muted">Действие содержит</p>
          <Input value={action} onChange={(e) => setAction(e.target.value)} placeholder="POST /api/tickets" />
        </div>
        <div>
          <p className="mb-1 text-xs text-muted">Тип объекта</p>
          <Input value={targetType} onChange={(e) => setTargetType(e.target.value)} placeholder="servers" />
        </div>
        <Button
          size="sm"
          onClick={() => {
            setPage(1);
            load(1);
          }}
        >
          Фильтровать
        </Button>
      </Card>

      <Card className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted">
            <tr>
              <th className="pb-2 pr-4">Время</th>
              <th className="pb-2 pr-4">Актор</th>
              <th className="pb-2 pr-4">Действие</th>
              <th className="pb-2 pr-4">Объект</th>
              <th className="pb-2">Метаданные</th>
            </tr>
          </thead>
          <tbody>
            {items.map((row) => (
              <tr key={row.id} className="border-t border-border align-top">
                <td className="whitespace-nowrap py-2 pr-4 text-xs text-muted">
                  {new Date(row.createdAt).toLocaleString('ru-RU')}
                </td>
                <td className="py-2 pr-4">
                  {row.actorType === 'ai' ? '🤖 AI' : (row.actorEmail ?? row.actorId ?? 'система')}
                </td>
                <td className="py-2 pr-4 font-mono text-xs">{row.action}</td>
                <td className="py-2 pr-4 text-xs">
                  {row.targetType ?? '—'}
                  {row.targetId ? ` / ${row.targetId.slice(0, 8)}` : ''}
                </td>
                <td className="max-w-md truncate py-2 font-mono text-[10px] text-muted">
                  {row.metadata ? JSON.stringify(row.metadata) : ''}
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={5} className="py-4 text-center text-muted">
                  Записей нет
                </td>
              </tr>
            )}
          </tbody>
        </table>
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
          Назад
        </Button>
        <span>
          Стр. {page} из {Math.max(1, Math.ceil(total / pageSize))}
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
          Вперёд
        </Button>
      </div>
    </div>
  );
}
