import { useRef, useState } from 'react';
import type { PteroScheduleDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useI18n } from '../../i18n';
import { Button, ErrorText, Input, Select } from '../../components/ui';

export function SevenDaysAutomation({ serverId }: { serverId: string }) {
  const { t } = useI18n();
  const [kind, setKind] = useState('save'),
    [period, setPeriod] = useState('hourly'),
    [message, setMessage] = useState(''),
    [name, setName] = useState('');
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [created, setCreated] = useState<number | null>(null),
    [done, setDone] = useState(false);
  const [attempted, setAttempted] = useState(false);
  const lock = useRef(false);
  async function create() {
    if (lock.current || attempted) return;
    lock.current = true;
    setBusy(true);
    setError('');
    try {
      const payload = kind === 'save' ? 'saveworld' : `say "${message.trim()}"`;
      // eslint-disable-next-line no-control-regex -- prohibit command separators and control bytes
      if (kind === 'announce' && (!message.trim() || /["\x00-\x1f\x7f]/.test(message)))
        throw Error(t('sdtd.tools.messageInvalid'));
      const base = `/api/servers/${serverId}/schedules`;
      setAttempted(true);
      const schedule = await api<PteroScheduleDto>(base, {
        method: 'POST',
        body: JSON.stringify({
          name: name.trim(),
          isActive: false,
          onlyWhenOnline: true,
          cron: {
            minute: period === 'hourly' ? '0' : `*/${period}`,
            hour: '*',
            dayOfMonth: '*',
            month: '*',
            dayOfWeek: '*',
          },
        }),
      });
      setCreated(schedule.id);
      await api(`${base}/${schedule.id}/tasks`, {
        method: 'POST',
        body: JSON.stringify({
          action: 'command',
          payload,
          timeOffset: 0,
          continueOnFailure: false,
        }),
      });
      setDone(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      lock.current = false;
    }
  }
  return (
    <div className="space-y-3">
      <p className="max-w-prose text-sm text-muted">{t('sdtd.tools.automationNote')}</p>
      <ErrorText>{error}</ErrorText>
      {attempted && error && <p className="text-sm">{t('sdtd.tools.schedulePartial')}</p>}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void create();
        }}
        className="space-y-3"
      >
        <fieldset disabled={busy || attempted} className="space-y-3">
          <label className="block text-sm">
            {t('sdtd.tools.name')}
            <Input
              required
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label htmlFor="sdtd-automation-kind" className="block text-sm">
            {t('sdtd.tools.action')}
            <Select
              id="sdtd-automation-kind"
              value={kind}
              onChange={setKind}
              options={['save', 'announce'].map((value) => ({
                value,
                label: t(`sdtd.tools.${value}`),
              }))}
            />
          </label>
          {kind === 'announce' && (
            <label className="block text-sm">
              {t('sdtd.tools.message')}
              <Input
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                required
                maxLength={200}
              />
            </label>
          )}
          <label htmlFor="sdtd-automation-period" className="block text-sm">
            {t('sdtd.tools.period')}
            <Select
              id="sdtd-automation-period"
              value={period}
              onChange={setPeriod}
              options={['15', '30', 'hourly'].map((value) => ({
                value,
                label: t(`sdtd.tools.period.${value}`),
              }))}
            />
          </label>
          <Button type="submit" disabled={!name.trim()}>
            {t('sdtd.tools.createSchedule')}
          </Button>
        </fieldset>
      </form>
      {created !== null && (
        <p role="status" className="text-sm">
          {t(done ? 'sdtd.tools.scheduleCreated' : 'sdtd.tools.schedulePartial')} #{created}
        </p>
      )}
    </div>
  );
}
