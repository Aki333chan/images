import { useCallback, useEffect, useState } from 'react';
import type { PteroScheduleDto, ScheduleAction } from '@aurum/shared';
import { SCHEDULE_PRESETS, SCHEDULE_POWER_ACTIONS, describeCron } from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { IconPlay, IconPlus, IconTrash } from '../components/icons';
import type { ServerTabProps } from './registry';

const base = (serverId: string) => `/api/servers/${serverId}/schedules`;

const EMPTY_CRON = { minute: '0', hour: '4', dayOfMonth: '*', month: '*', dayOfWeek: '*' };

const ACTION_LABELS: Record<ScheduleAction, string> = {
  command: 'Команда серверу',
  power: 'Питание',
  backup: 'Бэкап',
};

/**
 * Расписания задач сервера.
 *
 * Возможность самого Pterodactyl: он же их и выполняет. Панель заводит,
 * включает и запускает вручную.
 */
export function SchedulesTab({ serverId }: ServerTabProps) {
  const [list, setList] = useState<PteroScheduleDto[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [addingTaskTo, setAddingTaskTo] = useState<PteroScheduleDto | null>(null);

  const load = useCallback(() => {
    setError('');
    return api<PteroScheduleDto[]>(base(serverId))
      .then(setList)
      .catch((e: Error) => setError(e.message));
  }, [serverId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError('');
    try {
      await action();
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!list && !error) return <Spinner />;

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">Расписания</h2>
          <Button size="sm" onClick={() => setCreating(true)} disabled={busy}>
            <IconPlus size={14} /> Создать
          </Button>
        </div>

        <ErrorText>{error}</ErrorText>

        {list && list.length === 0 ? (
          <p className="text-muted">Расписаний нет.</p>
        ) : (
          <ul className="space-y-3">
            {list?.map((s) => (
              <li key={s.id} className="rounded-md border border-border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="truncate font-medium">{s.name}</span>
                      {s.isActive ? (
                        <Badge variant="success">включено</Badge>
                      ) : (
                        <Badge variant="outline">выключено</Badge>
                      )}
                      {s.isProcessing && <Badge variant="outline">выполняется</Badge>}
                      {s.onlyWhenOnline && <Badge variant="outline">только на запущенном</Badge>}
                    </div>
                    <div className="mt-1 text-[11px] text-muted">
                      {/* Человеческую подпись показываем, когда можем; cron —
                          всегда, потому что именно он и выполняется. */}
                      {describeCron(s.cron) && `${describeCron(s.cron)} · `}
                      <span className="font-mono">
                        {s.cron.minute} {s.cron.hour} {s.cron.dayOfMonth} {s.cron.month}{' '}
                        {s.cron.dayOfWeek}
                      </span>
                    </div>
                    <div className="text-[11px] text-muted">
                      {s.nextRunAt
                        ? `Следующий запуск: ${new Date(s.nextRunAt).toLocaleString('ru-RU')}`
                        : 'Следующий запуск не запланирован'}
                      {s.lastRunAt && ` · последний: ${new Date(s.lastRunAt).toLocaleString('ru-RU')}`}
                    </div>
                  </div>

                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        void run(() =>
                          api(`${base(serverId)}/${s.id}/active`, {
                            method: 'PUT',
                            body: JSON.stringify({ isActive: !s.isActive }),
                          }),
                        )
                      }
                    >
                      {s.isActive ? 'Выключить' : 'Включить'}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      title="Запустить прямо сейчас"
                      disabled={busy}
                      onClick={() => {
                        if (!confirm(`Выполнить «${s.name}» сейчас?`)) return;
                        void run(() =>
                          api(`${base(serverId)}/${s.id}/execute`, { method: 'POST' }),
                        );
                      }}
                    >
                      <IconPlay size={14} />
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={busy}
                      onClick={() => {
                        if (!confirm(`Удалить расписание «${s.name}»?`)) return;
                        void run(() => api(`${base(serverId)}/${s.id}`, { method: 'DELETE' }));
                      }}
                    >
                      <IconTrash size={14} />
                    </Button>
                  </div>
                </div>

                {/* Шаги в порядке выполнения — их считает бэкенд, в ответе
                    Pterodactyl порядок не гарантирован. */}
                <ol className="mt-2 space-y-1 border-t border-border pt-2">
                  {s.tasks.length === 0 ? (
                    <li className="text-[11px] text-muted">
                      Шагов нет — расписание ничего не делает.
                    </li>
                  ) : (
                    s.tasks.map((t) => (
                      <li key={t.id} className="flex items-center gap-2 text-xs">
                        <span className="w-5 shrink-0 text-muted">{t.sequenceId}.</span>
                        <span className="shrink-0 text-muted">{ACTION_LABELS[t.action]}</span>
                        {t.payload && <code className="min-w-0 truncate font-mono">{t.payload}</code>}
                        {t.timeOffset > 0 && (
                          <span className="shrink-0 text-muted">через {t.timeOffset} с</span>
                        )}
                        <Button
                          size="sm"
                          variant="ghost"
                          className="ml-auto shrink-0"
                          disabled={busy}
                          onClick={() =>
                            void run(() =>
                              api(`${base(serverId)}/${s.id}/tasks/${t.id}`, { method: 'DELETE' }),
                            )
                          }
                        >
                          <IconTrash size={12} />
                        </Button>
                      </li>
                    ))
                  )}
                </ol>

                <Button
                  size="sm"
                  variant="ghost"
                  className="mt-2"
                  disabled={busy}
                  onClick={() => setAddingTaskTo(s)}
                >
                  <IconPlus size={12} /> Добавить шаг
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {creating && (
        <ScheduleModal
          onClose={() => setCreating(false)}
          onSubmit={(body) => {
            setCreating(false);
            return run(() =>
              api(base(serverId), { method: 'POST', body: JSON.stringify(body) }),
            );
          }}
        />
      )}

      {addingTaskTo && (
        <TaskModal
          scheduleName={addingTaskTo.name}
          onClose={() => setAddingTaskTo(null)}
          onSubmit={(body) => {
            const id = addingTaskTo.id;
            setAddingTaskTo(null);
            return run(() =>
              api(`${base(serverId)}/${id}/tasks`, { method: 'POST', body: JSON.stringify(body) }),
            );
          }}
        />
      )}
    </div>
  );
}

function ScheduleModal({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (body: unknown) => void | Promise<void>;
}) {
  const [name, setName] = useState('Ночной бэкап');
  const [preset, setPreset] = useState(SCHEDULE_PRESETS[0]!.id);
  const [custom, setCustom] = useState(false);
  const [cron, setCron] = useState(EMPTY_CRON);
  const [onlyWhenOnline, setOnlyWhenOnline] = useState(false);

  const chosen = custom
    ? cron
    : (SCHEDULE_PRESETS.find((p) => p.id === preset)?.cron ?? EMPTY_CRON);

  return (
    <Modal title="Новое расписание" onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>Название</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>

        <div>
          <Label>Когда выполнять</Label>
          {/* Пресеты сверху, cron — под галочкой: cron знают не все, а
              «каждый день в 4 утра» понимают все. */}
          <Select
            value={custom ? '__custom' : preset}
            onChange={(v) => {
              if (v === '__custom') setCustom(true);
              else {
                setCustom(false);
                setPreset(v);
              }
            }}
            options={[
              ...SCHEDULE_PRESETS.map((p) => ({ value: p.id, label: p.label })),
              { value: '__custom', label: 'Своё cron-выражение' },
            ]}
          />
        </div>

        {custom && (
          <div className="grid grid-cols-5 gap-2">
            {(
              [
                ['minute', 'мин'],
                ['hour', 'час'],
                ['dayOfMonth', 'день'],
                ['month', 'мес'],
                ['dayOfWeek', 'д/н'],
              ] as const
            ).map(([key, label]) => (
              <div key={key}>
                <Label>{label}</Label>
                <Input
                  value={cron[key]}
                  onChange={(e) => setCron((prev) => ({ ...prev, [key]: e.target.value }))}
                  className="text-center font-mono"
                />
              </div>
            ))}
          </div>
        )}

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 accent-primary"
            checked={onlyWhenOnline}
            onChange={(e) => setOnlyWhenOnline(e.target.checked)}
          />
          Пропускать, если сервер выключен
        </label>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button
            disabled={name.trim() === ''}
            onClick={() =>
              void onSubmit({ name: name.trim(), isActive: true, onlyWhenOnline, cron: chosen })
            }
          >
            Создать
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function TaskModal({
  scheduleName,
  onClose,
  onSubmit,
}: {
  scheduleName: string;
  onClose: () => void;
  onSubmit: (body: unknown) => void | Promise<void>;
}) {
  const [action, setAction] = useState<ScheduleAction>('backup');
  const [payload, setPayload] = useState('');
  const [timeOffset, setTimeOffset] = useState('0');
  const [continueOnFailure, setContinueOnFailure] = useState(false);

  return (
    <Modal title={`Шаг для «${scheduleName}»`} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>Что делать</Label>
          <Select
            value={action}
            onChange={(v) => {
              setAction(v as ScheduleAction);
              // Payload у каждого действия значит своё — оставлять чужой
              // нельзя: сигнал питания попал бы в команду сервера.
              setPayload(v === 'power' ? 'restart' : '');
            }}
            options={[
              { value: 'backup', label: 'Сделать бэкап' },
              { value: 'command', label: 'Выполнить команду' },
              { value: 'power', label: 'Управление питанием' },
            ]}
          />
        </div>

        {action === 'command' && (
          <div>
            <Label>Команда</Label>
            <Input value={payload} onChange={(e) => setPayload(e.target.value)} placeholder="save-all" />
          </div>
        )}

        {action === 'power' && (
          <div>
            <Label>Сигнал</Label>
            <Select
              value={payload || 'restart'}
              onChange={setPayload}
              options={SCHEDULE_POWER_ACTIONS.map((s) => ({ value: s, label: s }))}
            />
          </div>
        )}

        {action === 'backup' && (
          <div>
            <Label>Что не класть в бэкап (необязательно)</Label>
            <Input value={payload} onChange={(e) => setPayload(e.target.value)} placeholder="*.log" />
          </div>
        )}

        <div>
          <Label>Пауза перед шагом, секунд</Label>
          <Input value={timeOffset} onChange={(e) => setTimeOffset(e.target.value)} inputMode="numeric" />
          <p className="mt-1 text-[11px] text-muted">
            Нужна, когда предыдущий шаг что-то запускает: например, дать серверу
            выключиться перед бэкапом.
          </p>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 accent-primary"
            checked={continueOnFailure}
            onChange={(e) => setContinueOnFailure(e.target.checked)}
          />
          Продолжать, даже если шаг не удался
        </label>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button
            onClick={() =>
              void onSubmit({
                action,
                payload: payload.trim(),
                timeOffset: Number(timeOffset) || 0,
                continueOnFailure,
              })
            }
          >
            Добавить
          </Button>
        </div>
      </div>
    </Modal>
  );
}
