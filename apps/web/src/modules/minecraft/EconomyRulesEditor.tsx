import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  MinecraftEconomyRuleApplyDto,
  MinecraftEconomyRuleDto,
  MinecraftEconomyRulePreviewDto,
  MinecraftEconomyRuleType,
} from '@aurum/shared';
import { api } from '../../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../../components/ui';
import { useT } from '../../i18n';

/** On-demand two-phase editor. It never polls and never sends partial patches. */
export function EconomyRulesEditor({ serverId }: { serverId: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [type, setType] = useState<MinecraftEconomyRuleType>('policy');
  const [rules, setRules] = useState<MinecraftEconomyRuleDto[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [draftId, setDraftId] = useState('');
  const [fields, setFields] = useState<Record<string, string>>(template('policy'));
  const [preview, setPreview] = useState<MinecraftEconomyRulePreviewDto | null>(null);
  const [reason, setReason] = useState('');
  const [newKey, setNewKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const selected = rules.find((rule) => rule.id === selectedId) ?? null;

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const values = await api<MinecraftEconomyRuleDto[]>(
        `/api/modules/minecraft/servers/${serverId}/economy/rules/${type}`,
      );
      setRules(values);
      setSelectedId((current) => (values.some((rule) => rule.id === current) ? current : ''));
    } catch (failure) {
      setRules([]);
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }, [serverId, type]);

  useEffect(() => {
    if (open) void load();
  }, [load, open]);

  useEffect(() => {
    const rule = rules.find((value) => value.id === selectedId);
    setDraftId(rule?.id ?? '');
    setFields(rule ? { ...rule.fields } : template(type));
    setPreview(null);
    setReason('');
  }, [rules, selectedId, type]);

  const changed = useMemo(() => diff(preview?.current ?? null, preview?.proposed ?? null), [preview]);

  async function createPreview() {
    setBusy(true);
    setError('');
    setPreview(null);
    try {
      const result = await api<MinecraftEconomyRulePreviewDto>(
        `/api/modules/minecraft/servers/${serverId}/economy/rules/${type}/preview`,
        {
          method: 'POST',
          body: JSON.stringify({
            id: draftId.trim(),
            expectedRevision: selected?.revision ?? 0,
            fields,
          }),
        },
      );
      setPreview(result);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function applyPreview() {
    if (!preview?.token) return;
    setBusy(true);
    setError('');
    try {
      const result = await api<MinecraftEconomyRuleApplyDto>(
        `/api/modules/minecraft/servers/${serverId}/economy/rules/apply`,
        { method: 'POST', body: JSON.stringify({ token: preview.token, reason: reason.trim() }) },
      );
      if (result.status === 'applied' || result.status === 'applied_reload_failed') {
        setPreview(null);
        setReason('');
        setSelectedId(result.current?.id ?? draftId.trim());
        await load();
        if (result.status === 'applied_reload_failed') setError(t('mc.rules.reloadFailed'));
      } else if (result.status === 'expired') {
        setPreview(null);
        setError(t('mc.rules.status.expired'));
      } else {
        setPreview({
          status: result.status,
          token: '',
          current: result.current,
          proposed: null,
          warnings: [],
          message: result.message,
          expiresAt: null,
        });
      }
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function updateField(key: string, value: string) {
    setFields((current) => ({ ...current, [key]: value }));
    setPreview(null);
  }

  function addField() {
    const key = newKey.trim();
    if (!key || Object.hasOwn(fields, key)) return;
    setFields((current) => ({ ...current, [key]: '' }));
    setNewKey('');
    setPreview(null);
  }

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{t('mc.rules.title')}</h3>
          <p className="mt-1 text-xs text-muted">{t('mc.rules.hint')}</p>
        </div>
        <div className="flex gap-2">
          {open ? (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void load()}>
              {t('common.refresh')}
            </Button>
          ) : null}
          <Button size="sm" variant="outline" onClick={() => setOpen((value) => !value)}>
            {t(open ? 'mc.rules.hide' : 'mc.rules.show')}
          </Button>
        </div>
      </div>

      {open ? (
        <>
          <div className="grid gap-2 sm:grid-cols-2">
            <div>
              <Label>{t('mc.rules.type')}</Label>
              <Select
                value={type}
                onChange={(value) => {
                  setType(value as MinecraftEconomyRuleType);
                  setSelectedId('');
                  setRules([]);
                }}
                options={[
                  { value: 'policy', label: t('mc.rules.policy') },
                  { value: 'exchange', label: t('mc.rules.exchange') },
                ]}
              />
            </div>
            <div>
              <Label>{t('mc.rules.rule')}</Label>
              <Select
                value={selectedId}
                onChange={setSelectedId}
                options={[
                  { value: '', label: t('mc.rules.new') },
                  ...rules.map((rule) => ({ value: rule.id, label: `${rule.id} · r${rule.revision}` })),
                ]}
              />
            </div>
          </div>

          {busy && rules.length === 0 ? <Spinner /> : null}
          <ErrorText>{error}</ErrorText>

          <div>
            <Label>{t('mc.rules.id')}</Label>
            <Input
              value={draftId}
              disabled={selected !== null}
              maxLength={64}
              placeholder={type === 'policy' ? 'sales-tax' : 'coins-to-tokens'}
              onChange={(event) => {
                setDraftId(event.target.value);
                setPreview(null);
              }}
            />
          </div>

          <div className="space-y-2">
            {Object.keys(fields).sort().map((key) => (
              <div key={key} className="grid items-center gap-2 sm:grid-cols-[minmax(150px,0.42fr)_1fr_auto]">
                <code className="break-all text-[11px] text-muted">{key}</code>
                <Input value={fields[key]} onChange={(event) => updateField(key, event.target.value)} />
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setFields((current) => Object.fromEntries(Object.entries(current).filter(([name]) => name !== key)));
                    setPreview(null);
                  }}
                >
                  {t('mc.rules.remove')}
                </Button>
              </div>
            ))}
          </div>

          <div className="flex flex-wrap gap-2">
            <Input
              className="min-w-56 flex-1"
              value={newKey}
              maxLength={96}
              placeholder={type === 'policy' ? 'definition.recipient-type' : 'condition.account-type'}
              onChange={(event) => setNewKey(event.target.value)}
            />
            <Button type="button" size="sm" variant="outline" onClick={addField}>
              {t('mc.rules.addField')}
            </Button>
            <Button type="button" size="sm" disabled={busy || !draftId.trim()} onClick={() => void createPreview()}>
              {t('mc.rules.preview')}
            </Button>
          </div>

          {preview ? (
            <div className="space-y-3 rounded-md border border-neutral-800 bg-neutral-950/40 p-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant={preview.status === 'ready' ? 'success' : preview.status === 'conflict' ? 'warn' : 'destructive'}>
                  {t(`mc.rules.status.${preview.status}`)}
                </Badge>
                {preview.expiresAt ? <span className="text-[11px] text-muted">{new Date(preview.expiresAt).toLocaleTimeString()}</span> : null}
              </div>
              {preview.message && preview.message !== 'ready' ? <p className="text-xs text-muted">{preview.message}</p> : null}
              {preview.warnings.length ? (
                <div className="flex flex-wrap gap-1.5">
                  {preview.warnings.map((warning) => <Badge key={warning} variant="warn">{t(`mc.rules.warning.${warning}`)}</Badge>)}
                </div>
              ) : null}
              {changed.length ? (
                <dl className="space-y-1 text-xs">
                  {changed.map(([key, before, after]) => (
                    <div key={key} className="grid gap-1 sm:grid-cols-[minmax(130px,0.35fr)_1fr]">
                      <dt className="break-all text-muted">{key}</dt>
                      <dd className="break-all font-mono text-[11px]">{before || '—'} → {after || '—'}</dd>
                    </div>
                  ))}
                </dl>
              ) : null}
              {preview.status === 'ready' ? (
                <div className="flex flex-wrap gap-2">
                  <Input
                    className="min-w-64 flex-1"
                    value={reason}
                    maxLength={255}
                    placeholder={t('mc.rules.reason')}
                    onChange={(event) => setReason(event.target.value)}
                  />
                  <Button disabled={busy || reason.trim().length < 3} onClick={() => void applyPreview()}>
                    {t('mc.rules.apply')}
                  </Button>
                </div>
              ) : null}
            </div>
          ) : null}
        </>
      ) : null}
    </Card>
  );
}

function template(type: MinecraftEconomyRuleType): Record<string, string> {
  return type === 'policy'
    ? {
        kind: 'TAX', handlerVersion: '1', categories: 'NPC_PURCHASE,NPC_SALE',
        'definition.rate': '0.10', 'definition.mode': 'INCLUDED', priority: '100',
        enabled: 'false', effectiveFrom: '', effectiveUntil: '',
      }
    : {
        fromCurrency: 'coins', toCurrency: 'tokens', rate: '1', feeRate: '0',
        minimum: '', maximum: '', settlement: 'RESERVE', priority: '100',
        enabled: 'false', effectiveFrom: '', effectiveUntil: '',
      };
}

function diff(
  current: MinecraftEconomyRuleDto | null,
  proposed: MinecraftEconomyRuleDto | null,
): [string, string, string][] {
  if (!proposed) return [];
  const before = current?.fields ?? {};
  return [...new Set([...Object.keys(before), ...Object.keys(proposed.fields)])]
    .sort()
    .filter((key) => before[key] !== proposed.fields[key])
    .map((key) => [key, before[key] ?? '', proposed.fields[key] ?? '']);
}
