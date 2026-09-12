import { useCallback, useEffect, useRef, useState } from 'react';
import type { MinecraftEconomyAuditDto, MinecraftEconomyAuditSection } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, Card, ErrorText, Input, Spinner } from '../../components/ui';
import { useT } from '../../i18n';

const SECTIONS: MinecraftEconomyAuditSection[] = [
  'overview', 'ledger', 'policies', 'exchanges', 'holds', 'claims',
];

/** On-demand native ledger audit. It never polls while collapsed or in the background. */
export function EconomyAuditPanel({ serverId }: { serverId: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [section, setSection] = useState<MinecraftEconomyAuditSection>('overview');
  const [accountDraft, setAccountDraft] = useState('');
  const [account, setAccount] = useState('');
  const [data, setData] = useState<MinecraftEconomyAuditDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const requestId = useRef(0);

  const load = useCallback(() => {
    if (!open) return;
    const currentRequest = ++requestId.current;
    setBusy(true);
    setError('');
    const query = new URLSearchParams({ limit: '50' });
    if (section === 'ledger' && account) query.set('account', account);
    api<MinecraftEconomyAuditDto>(
      `/api/modules/minecraft/servers/${serverId}/economy/audit/${section}?${query.toString()}`,
    )
      .then((value) => {
        if (requestId.current === currentRequest) setData(value);
      })
      .catch((failure: Error) => {
        if (requestId.current !== currentRequest) return;
        setData(null);
        setError(failure.message);
      })
      .finally(() => {
        if (requestId.current === currentRequest) setBusy(false);
      });
  }, [account, open, section, serverId]);

  useEffect(() => {
    load();
    return () => {
      requestId.current += 1;
    };
  }, [load]);

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{t('mc.audit.title')}</h3>
          <p className="mt-1 text-xs text-muted">{t('mc.audit.hint')}</p>
        </div>
        <Button size="sm" variant="outline" onClick={() => setOpen((value) => !value)}>
          {t(open ? 'mc.audit.hide' : 'mc.audit.show')}
        </Button>
      </div>

      {open && (
        <>
          <div className="flex flex-wrap gap-1.5">
            {SECTIONS.map((value) => (
              <Button
                key={value}
                size="sm"
                variant={section === value ? 'default' : 'ghost'}
                onClick={() => setSection(value)}
              >
                {t(`mc.audit.section.${value}`)}
              </Button>
            ))}
            <Button size="sm" variant="outline" disabled={busy} onClick={load}>
              {t('common.refresh')}
            </Button>
          </div>

          {section === 'ledger' ? (
            <form
              className="flex flex-wrap items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                const next = accountDraft.trim();
                if (next === account) load();
                else setAccount(next);
              }}
            >
              <Input
                className="min-w-64 flex-1"
                value={accountDraft}
                onChange={(event) => setAccountDraft(event.target.value)}
                placeholder={t('mc.audit.account.placeholder')}
                maxLength={160}
              />
              <Button size="sm" type="submit" disabled={busy}>
                {t('mc.audit.account.apply')}
              </Button>
              {account ? (
                <Button
                  size="sm"
                  type="button"
                  variant="ghost"
                  disabled={busy}
                  onClick={() => {
                    setAccountDraft('');
                    setAccount('');
                  }}
                >
                  {t('mc.audit.account.all')}
                </Button>
              ) : null}
            </form>
          ) : null}

          {busy && !data ? <Spinner /> : null}
          {error ? <ErrorText>{error}</ErrorText> : null}
          {data && data.section === section ? (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted">
                <span>{data.currency}</span>
                <span>{new Date(data.generatedAt).toLocaleString()}</span>
                {busy ? <span>…</span> : null}
              </div>
              <Summary values={data.summary} />
              {data.records.length === 0 && section !== 'overview' ? (
                <p className="text-xs text-muted">{t('mc.audit.empty')}</p>
              ) : (
                <div className="space-y-2">
                  {data.records.map((record, index) => (
                    <details
                      key={`${record.type}:${record.fields.id ?? record.fields.key ?? index}`}
                      className="rounded-md border border-neutral-800 bg-neutral-950/40 px-3 py-2"
                    >
                      <summary className="cursor-pointer text-xs font-medium text-neutral-200">
                        {recordTitle(record.type, record.fields)}
                      </summary>
                      <dl className="mt-2 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-[minmax(110px,0.35fr)_1fr]">
                        {Object.entries(record.fields).map(([key, value]) => (
                          <div key={key} className="contents">
                            <dt className="text-muted">{fieldLabel(t, key)}</dt>
                            <dd className="break-all font-mono text-[11px] text-neutral-300">{value || '—'}</dd>
                          </div>
                        ))}
                      </dl>
                    </details>
                  ))}
                </div>
              )}
            </div>
          ) : null}
        </>
      )}
    </Card>
  );
}

function Summary({ values }: { values: Record<string, string> }) {
  const t = useT();
  if (Object.keys(values).length === 0) return null;
  return (
    <dl className="grid gap-2 sm:grid-cols-3 lg:grid-cols-6">
      {Object.entries(values).map(([key, value]) => (
        <div key={key} className="rounded-md border border-neutral-800 px-2.5 py-2">
          <dt className="text-[10px] uppercase tracking-wide text-muted">{fieldLabel(t, key)}</dt>
          <dd className="mt-0.5 break-all text-xs font-semibold text-neutral-100">{value || '—'}</dd>
        </div>
      ))}
    </dl>
  );
}

function recordTitle(type: string, fields: Record<string, string>): string {
  return [fields.id ?? fields.key ?? type, fields.category ?? fields.kind, fields.status]
    .filter(Boolean)
    .join(' · ');
}

function fieldLabel(t: (key: string) => string, key: string): string {
  const translated = t(`mc.audit.field.${key}`);
  return translated === `mc.audit.field.${key}` ? key : translated;
}
