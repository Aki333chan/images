import { useState } from 'react';
import type { MinecraftEconomyRuleDto, MinecraftEconomyRulePreviewDto, MinecraftEconomyRuleApplyDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { useT } from '../../i18n';
import { Button, Card, ErrorText, Input, Label } from '../../components/ui';

/** Uses the existing actor-bound preview/apply contract; no new polling or bypass route. */
export function StartingBalancePanel({ serverId }: { serverId: string }) {
  const t = useT();
  const { hasPermission } = useAuth();
  const canEdit = hasPermission('minecraft.economy.admin');
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState<MinecraftEconomyRuleDto | null>(null);
  const [fields, setFields] = useState({ enabled: 'false', amount: '100', currency: 'coins' });
  const [preview, setPreview] = useState<MinecraftEconomyRulePreviewDto | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const base = `/api/modules/minecraft/servers/${serverId}/economy/rules`;

  async function load() {
    setBusy(true); setError(''); setPreview(null);
    try {
      const values = await api<MinecraftEconomyRuleDto[]>(`${base}/starting_balance`);
      const value = values.find((rule) => rule.id === 'global');
      if (!value) throw new Error(t('mc.starter.unavailable'));
      const { enabled, amount, currency } = value.fields;
      if ((enabled !== 'true' && enabled !== 'false') || typeof amount !== 'string' || typeof currency !== 'string')
        throw new Error(t('mc.starter.unavailable'));
      setCurrent(value);
      setFields({ enabled, amount, currency });
    } catch (failure) { setCurrent(null); setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  function update(key: keyof typeof fields, value: string) {
    setFields((old) => ({ ...old, [key]: value })); setPreview(null); setSaved(false);
  }
  async function check() {
    if (!current) return;
    setBusy(true); setError(''); setSaved(false); setPreview(null);
    try {
      const value = await api<MinecraftEconomyRulePreviewDto>(`${base}/starting_balance/preview`, {
        method: 'POST', body: JSON.stringify({ id: 'global', expectedRevision: current.revision, fields }),
      });
      if (value.status !== 'ready') throw new Error(t(`mc.rules.status.${value.status}`) + ': ' + value.message);
      setPreview(value);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function apply() {
    if (!preview?.token || reason.trim().length < 3) return;
    setBusy(true); setError('');
    try {
      const result = await api<MinecraftEconomyRuleApplyDto>(`${base}/apply`, {
        method: 'POST', body: JSON.stringify({ token: preview.token, reason: reason.trim() }),
      });
      setPreview(null);
      if (result.status !== 'applied') throw new Error(t(`mc.rules.status.${result.status}`) + ': ' + result.message);
      await load(); setSaved(true); setReason('');
    } catch (failure) { setError((failure as Error).message); setPreview(null); }
    finally { setBusy(false); }
  }
  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{t('mc.starter.title')}</h3>
          <p className="mt-1 max-w-3xl text-xs text-muted">{t('mc.starter.hint')}</p>
        </div>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => {
          if (!open) { setOpen(true); void load(); } else setOpen(false);
        }}>{t(open ? 'mc.plug.hide' : 'mc.starter.open')}</Button>
      </div>
      {open ? <>
        <ErrorText>{error}</ErrorText>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void load()}>{t('common.refresh')}</Button>
        {current ? <>
          <fieldset disabled={!canEdit || busy} className="grid gap-3 sm:grid-cols-3">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={fields.enabled === 'true'}
                onChange={(event) => update('enabled', String(event.target.checked))} />
              {t('mc.starter.enabled')}
            </label>
            <div><Label>{t('mc.starter.amount')}</Label>
              <Input inputMode="decimal" value={fields.amount} maxLength={24}
                onChange={(event) => update('amount', event.target.value)} /></div>
            <div><Label>{t('mc.starter.currency')}</Label>
              <Input value={fields.currency} maxLength={32}
                onChange={(event) => update('currency', event.target.value.trim())} /></div>
          </fieldset>
          <p className="text-xs text-muted">{t('mc.starter.behavior')}</p>
          {canEdit ? <Button size="sm" disabled={busy} onClick={() => void check()}>{t('mc.rules.preview')}</Button> : null}
        </> : null}
        {preview?.proposed ? <div className="space-y-3 rounded-md border border-neutral-800 bg-neutral-950/40 p-3">
          <h4 className="text-sm font-medium">{t('mc.starter.confirm')}</h4>
          {(['enabled', 'amount', 'currency'] as const).map((key) => (
            <p key={key} className="text-xs">
              {t(`mc.starter.${key}`)}: {key === 'enabled'
                ? t(preview.current?.fields[key] === 'true' ? 'mc.starter.on' : 'mc.starter.off')
                : preview.current?.fields[key]} → {key === 'enabled'
                ? t(preview.proposed?.fields[key] === 'true' ? 'mc.starter.on' : 'mc.starter.off')
                : preview.proposed?.fields[key]}
            </p>
          ))}
          <Label>{t('mc.rules.reason')}</Label>
          <Input value={reason} maxLength={255} onChange={(event) => setReason(event.target.value)} />
          <Button size="sm" disabled={busy || reason.trim().length < 3} onClick={() => void apply()}>{t('mc.rules.apply')}</Button>
        </div> : null}
        {saved && !error ? <p className="text-sm text-emerald-400">{t('mc.starter.saved')}</p> : null}
      </> : null}
    </Card>
  );
}
