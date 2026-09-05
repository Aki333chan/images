import { useCallback, useEffect, useState } from 'react';
import type { PteroScheduleDto, ScheduleAction } from '@aurum/shared';
import { SCHEDULE_PRESETS, SCHEDULE_POWER_ACTIONS, describeCron } from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { IconPlay, IconPlus, IconTrash } from '../components/icons';
import type { ServerTabProps } from './registry';
import { useI18n, useT } from '../i18n';

/** Подпись расписания словами; пусто — интерфейс покажет само cron-выражение. */
function cronText(cron: PteroScheduleDto['cron'], t: (k: string, v?: Record<string, string>) => string): string {
  const described = describeCron(cron);
  return described ? `${t(described.key, described.values)} · ` : '';
}

const base = (serverId: string) => `/api/servers/${serverId}/schedules`;

const EMPTY_CRON = { minute: '0', hour: '4', dayOfMonth: '*', month: '*', dayOfWeek: '*' };

const ACTION_LABELS: Record<ScheduleAction, string> = {
  command: 'schedules.action.command',
  power: 'schedules.action.power',
  backup: 'schedules.action.backup',
};

/**
 * Расписания задач сервера.
 *
 * Возможность самого Pterodactyl: он же их и выполняет. Панель заводит,
 * включает и запускает вручную.
 */
export function SchedulesTab({ serverId }: ServerTabProps) {
  const { t, formatDateTime } = useI18n();
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
          <h2 className="font-semibold">{t('schedules.title')}</h2>
          <Button size="sm" onClick={() => setCreating(true)} disabled={busy}>
            <IconPlus size={14} /> {t('schedules.create')}
          </Button>
        </div>

        <ErrorText>{error}</ErrorText>

        {list && list.length === 0 ? (
          <p className="text-muted">{t('schedules.empty')}</p>
        ) : (
          <ul className="space-y-3">
            {list?.map((s) => (
              <li key={s.id} className="rounded-md border border-border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="truncate font-medium">{s.name}</span>
                      {s.isActive ? (
                        <Badge variant="success">{t('schedules.enabled')}</Badge>
                      ) : (
                        <Badge variant="outline">{t('schedules.disabled')}</Badge>
                      )}
                      {s.isProcessing && <Badge variant="outline">{t('schedules.running')}</Badge>}
                      {s.onlyWhenOnline && <Badge variant="outline">{t('schedules.onlyOnline')}</Badge>}
                    </div>
                    <div className="mt-1 text-[11px] text-muted">
                      {/* Человеческую подпись показываем, когда можем; cron —
                          всегда, потому что именно он и выполняется. */}
                      {cronText(s.cron, t)}
                      <span className="font-mono">
                        {s.cron.minute} {s.cron.hour} {s.cron.dayOfMonth} {s.cron.month}{' '}
                        {s.cron.dayOfWeek}
                      </span>
                    </div>
                    <div className="text-[11px] text-muted">
                      {s.nextRunAt
                        ? t('schedules.nextRun', { date: formatDateTime(s.nextRunAt) })
                        : t('schedules.noNextRun')}
                      {s.lastRunAt && t('schedules.lastRun', { date: formatDateTime(s.lastRunAt) })}
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
                      {t(s.isActive ? 'schedules.disable' : 'schedules.enable')}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      title={t('schedules.runNow')}
                      disabled={busy}
                      onClick={() => {
                        if (!confirm(t('schedules.confirmRun', { name: s.name }))) return;
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
                        if (!confirm(t('schedules.confirmDelete', { name: s.name }))) return;
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
                      {t('schedules.noSteps')}
                    </li>
                  ) : (
                    s.tasks.map((step) => (
                      <li key={step.id} className="flex items-center gap-2 text-xs">
                        <span className="w-5 shrink-0 text-muted">{step.sequenceId}.</span>
                        <span className="shrink-0 text-muted">{t(ACTION_LABELS[step.action])}</span>
                        {step.payload && <code className="min-w-0 truncate font-mono">{step.payload}</code>}
                        {step.timeOffset > 0 && (
                          <span className="shrink-0 text-muted">{t('schedules.after', { seconds: step.timeOffset })}</span>
                        )}
                        <Button
                          size="sm"
                          variant="ghost"
                          className="ml-auto shrink-0"
                          disabled={busy}
                          onClick={() =>
                            void run(() =>
                              api(`${base(serverId)}/${s.id}/tasks/${step.id}`, { method: 'DELETE' }),
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
                  <IconPlus size={12} /> {t('schedules.addStep')}
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
  const t = useT();
  const [name, setName] = useState(t('schedules.defaultName'));
  const [preset, setPreset] = useState(SCHEDULE_PRESETS[0]!.id);
  const [custom, setCustom] = useState(false);
  const [cron, setCron] = useState(EMPTY_CRON);
  const [onlyWhenOnline, setOnlyWhenOnline] = useState(false);

  const chosen = custom
    ? cron
    : (SCHEDULE_PRESETS.find((p) => p.id === preset)?.cron ?? EMPTY_CRON);

  return (
    <Modal title={t('schedules.new')} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>{t('schedules.name')}</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>

        <div>
          <Label>{t('schedules.when')}</Label>
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
              ...SCHEDULE_PRESETS.map((p) => ({ value: p.id, label: t(p.labelKey) })),
              { value: '__custom', label: t('schedules.customCron') },
            ]}
          />
        </div>

        {custom && (
          <div className="grid grid-cols-5 gap-2">
            {(
              [
                ['minute', t('schedules.cron.minute')],
                ['hour', t('schedules.cron.hour')],
                ['dayOfMonth', t('schedules.cron.day')],
                ['month', t('schedules.cron.month')],
                ['dayOfWeek', t('schedules.cron.weekday')],
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
          {t('schedules.skipOffline')}
        </label>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            disabled={name.trim() === ''}
            onClick={() =>
              void onSubmit({ name: name.trim(), isActive: true, onlyWhenOnline, cron: chosen })
            }
          >
            {t('schedules.create')}
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
  const t = useT();
  const [action, setAction] = useState<ScheduleAction>('backup');
  const [payload, setPayload] = useState('');
  const [timeOffset, setTimeOffset] = useState('0');
  const [continueOnFailure, setContinueOnFailure] = useState(false);

  return (
    <Modal title={t('schedules.stepFor', { name: scheduleName })} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>{t('schedules.what')}</Label>
          <Select
            value={action}
            onChange={(v) => {
              setAction(v as ScheduleAction);
              // Payload у каждого действия значит своё — оставлять чужой
              // нельзя: сигнал питания попал бы в команду сервера.
              setPayload(v === 'power' ? 'restart' : '');
            }}
            options={[
              { value: 'backup', label: t('schedules.do.backup') },
              { value: 'command', label: t('schedules.do.command') },
              { value: 'power', label: t('schedules.do.power') },
            ]}
          />
        </div>

        {action === 'command' && (
          <div>
            <Label>{t('schedules.command')}</Label>
            <Input value={payload} onChange={(e) => setPayload(e.target.value)} placeholder="save-all" />
          </div>
        )}

        {action === 'power' && (
          <div>
            <Label>{t('schedules.signal')}</Label>
            <Select
              value={payload || 'restart'}
              onChange={setPayload}
              options={SCHEDULE_POWER_ACTIONS.map((s) => ({ value: s, label: s }))}
            />
          </div>
        )}

        {action === 'backup' && (
          <div>
            <Label>{t('schedules.ignore')}</Label>
            <Input value={payload} onChange={(e) => setPayload(e.target.value)} placeholder="*.log" />
          </div>
        )}

        <div>
          <Label>{t('schedules.delay')}</Label>
          <Input value={timeOffset} onChange={(e) => setTimeOffset(e.target.value)} inputMode="numeric" />
          <p className="mt-1 text-[11px] text-muted">
            {t('schedules.delayHint')}
          </p>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 accent-primary"
            checked={continueOnFailure}
            onChange={(e) => setContinueOnFailure(e.target.checked)}
          />
          {t('schedules.continueOnFail')}
        </label>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
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
            {t('schedules.add')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
