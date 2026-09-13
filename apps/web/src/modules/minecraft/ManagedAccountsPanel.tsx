import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  MinecraftManagedAccountDto,
  MinecraftManagedAccountMutationDto,
  MinecraftManagedAccountPageDto,
} from '@aurum/shared';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../../components/ui';
import { useAuth } from '../../lib/auth';
import { api } from '../../lib/api';
import { useT } from '../../i18n';

/** On-demand registry browser and named-fund administration. It never polls. */
export function ManagedAccountsPanel({ serverId }: { serverId: string }) {
  const t = useT();
  const { hasPermission } = useAuth();
  const admin = hasPermission('minecraft.economy.admin');
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState<MinecraftManagedAccountPageDto | null>(null);
  const [selectedKey, setSelectedKey] = useState('');
  const [search, setSearch] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [technical, setTechnical] = useState(false);
  const [offset, setOffset] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [fundId, setFundId] = useState('');
  const [fundName, setFundName] = useState('');
  const [purpose, setPurpose] = useState('');
  const [target, setTarget] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('');
  const [reason, setReason] = useState('');
  const [createReason, setCreateReason] = useState('');

  const selected = page?.accounts.find((account) => account.key === selectedKey) ?? null;
  const currencies = useMemo(() => selected ? Object.keys(selected.balances).sort() : [], [selected]);
  const amountValid = /^(?:0|[1-9]\d{0,12})(?:\.\d{1,8})?$/.test(amount)
    && Number(amount) > 0;

  const load = useCallback(async (newOffset = offset) => {
    setBusy(true);
    setError('');
    try {
      const query = new URLSearchParams({ offset: String(newOffset), limit: '25' });
      if (search.trim()) query.set('search', search.trim());
      if (type) query.set('type', type);
      if (status) query.set('status', status);
      if (technical && admin) query.set('technical', 'true');
      const result = await api<MinecraftManagedAccountPageDto>(
        `/api/modules/minecraft/servers/${serverId}/economy/accounts?${query.toString()}`,
      );
      setPage(result);
      setOffset(result.offset);
      setSelectedKey((current) => result.accounts.some((account) => account.key === current)
        ? current : (result.accounts[0]?.key ?? ''));
    } catch (failure) {
      setPage(null);
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }, [admin, offset, search, serverId, status, technical, type]);

  useEffect(() => { if (open) void load(0); }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (selected && !currency) setCurrency(Object.keys(selected.balances).sort()[0] ?? '');
  }, [currency, selected]);

  async function mutate(body: Record<string, unknown>, mutationReason = reason) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await api<MinecraftManagedAccountMutationDto>(
        `/api/modules/minecraft/servers/${serverId}/economy/accounts/action`,
        {
          method: 'POST',
          body: JSON.stringify({ ...body, idempotencyKey: crypto.randomUUID(), reason: mutationReason.trim() }),
        },
      );
      setNotice(`${result.status}: ${result.message}`);
      await load(offset);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{t('mc.accounts.title')}</h3>
          <p className="mt-1 text-xs text-muted">{t('mc.accounts.hint')}</p>
        </div>
        <div className="flex gap-2">
          {open ? <Button size="sm" variant="outline" disabled={busy} onClick={() => void load(offset)}>{t('common.refresh')}</Button> : null}
          <Button size="sm" variant="outline" onClick={() => setOpen((value) => !value)}>
            {t(open ? 'mc.accounts.hide' : 'mc.accounts.show')}
          </Button>
        </div>
      </div>

      {open ? <>
        <div className="grid gap-2 md:grid-cols-[1fr_170px_150px_auto]">
          <Input value={search} placeholder={t('mc.accounts.search')} onChange={(event) => setSearch(event.target.value)} />
          <Select value={type} onChange={setType} options={[
            { value: '', label: t('mc.accounts.allTypes') },
            ...['TREASURY', 'PLAYER', 'GUILD', 'ARENA', 'SLOTS', 'NPC_SHOP', 'NPC_BUYER']
              .map((value) => ({ value, label: value })),
          ]} />
          <Select value={status} onChange={setStatus} options={[
            { value: '', label: t('mc.accounts.allStatuses') },
            ...['active', 'frozen', 'closing', 'closed'].map((value) => ({ value, label: value })),
          ]} />
          <Button size="sm" disabled={busy} onClick={() => void load(0)}>{t('common.search')}</Button>
        </div>
        {admin ? <label className="flex items-center gap-2 text-xs text-muted">
          <input type="checkbox" checked={technical} onChange={(event) => setTechnical(event.target.checked)} />
          {t('mc.accounts.technical')}
        </label> : null}

        {busy && !page ? <Spinner /> : null}
        <ErrorText>{error}</ErrorText>
        {notice ? <p className="text-sm text-emerald-300">{notice}</p> : null}

        {page ? <div className="grid gap-3 lg:grid-cols-[minmax(260px,0.8fr)_1.2fr]">
          <div className="space-y-2">
            {page.accounts.map((account) => (
              <button key={account.key} type="button" onClick={() => setSelectedKey(account.key)}
                className={`w-full rounded-md border p-3 text-left ${selectedKey === account.key ? 'border-primary bg-primary/10' : 'border-border bg-neutral-950/30'}`}>
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm font-medium">{account.name}</span>
                  <Badge>{account.status}</Badge>
                </div>
                <div className="mt-1 truncate text-xs text-muted">{account.key}</div>
                <Balances account={account} />
              </button>
            ))}
            {!page.accounts.length ? <p className="text-sm text-muted">{t('mc.accounts.empty')}</p> : null}
            <div className="flex items-center justify-between">
              <Button size="sm" variant="ghost" disabled={offset === 0 || busy}
                onClick={() => void load(Math.max(0, offset - 25))}>{t('common.back')}</Button>
              <span className="text-xs text-muted">{offset + 1}–{Math.min(offset + page.limit, page.total)} / {page.total}</span>
              <Button size="sm" variant="ghost" disabled={offset + page.limit >= page.total || busy}
                onClick={() => void load(offset + page.limit)}>{t('common.next')}</Button>
            </div>
          </div>

          {selected ? <div className="space-y-3 rounded-md border border-border bg-neutral-950/30 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div><h4 className="font-semibold">{selected.name}</h4><code className="text-xs text-muted">{selected.key}</code></div>
              <Badge>{selected.type} · {selected.status}</Badge>
            </div>
            {selected.purpose ? <p className="text-sm text-muted">{selected.purpose}</p> : null}
            <Balances account={selected} large />
            <dl className="grid gap-x-3 gap-y-1 text-xs sm:grid-cols-[140px_1fr]">
              <dt className="text-muted">{t('mc.accounts.owner')}</dt><dd>{selected.ownerKind}:{selected.ownerId}</dd>
              <dt className="text-muted">{t('mc.accounts.founder')}</dt><dd className="break-all">{selected.founderUuid || '—'}</dd>
              <dt className="text-muted">{t('mc.accounts.source')}</dt><dd>{selected.sourcePlugin}</dd>
              <dt className="text-muted">{t('mc.accounts.destination')}</dt><dd>{selected.closeDestination || t('mc.accounts.serverDefault')}</dd>
            </dl>

            {admin && selected.type === 'TREASURY' && selected.status !== 'closed' ? <>
              <div className="border-t border-border pt-3">
                <Label>{t('mc.accounts.reason')}</Label>
                <Input value={reason} maxLength={255} onChange={(event) => setReason(event.target.value)} />
              </div>
              <div className="grid gap-2 sm:grid-cols-3">
                <Select value={target} onChange={setTarget} options={[
                  { value: '', label: t('mc.accounts.target') },
                  ...page.accounts.filter((account) => account.type === 'TREASURY'
                    && account.key !== selected.key && account.status === 'active')
                    .map((account) => ({ value: account.key, label: account.name })),
                ]} />
                <Input value={amount} inputMode="decimal" placeholder={t('mc.accounts.amount')}
                  onChange={(event) => setAmount(event.target.value)} />
                <Select value={currency} onChange={setCurrency}
                  options={currencies.map((value) => ({ value, label: value }))} />
              </div>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" disabled={!target || !amountValid || !currency || reason.trim().length < 3 || busy}
                  onClick={() => void mutate({ operation: 'transfer', profileKey: selected.key,
                    secondaryProfile: target, amount, currency })}>{t('mc.accounts.transfer')}</Button>
                <Button size="sm" variant="outline" disabled={reason.trim().length < 3 || busy}
                  onClick={() => void mutate({ operation: selected.status === 'frozen' ? 'unfreeze' : 'freeze', profileKey: selected.key })}>
                  {t(selected.status === 'frozen' ? 'mc.accounts.unfreeze' : 'mc.accounts.freeze')}
                </Button>
                {selected.key !== 'treasury:global' ? <Button size="sm" variant="destructive"
                  disabled={reason.trim().length < 3 || busy}
                  onClick={() => {
                    if (window.confirm(t('mc.accounts.closeConfirm'))) void mutate({ operation: 'close',
                      profileKey: selected.key, secondaryProfile: target });
                  }}>{t('mc.accounts.close')}</Button> : null}
              </div>
            </> : null}
          </div> : null}
        </div> : null}

        {admin ? <div className="space-y-2 border-t border-border pt-3">
          <h4 className="text-sm font-semibold">{t('mc.accounts.create')}</h4>
          <div className="grid gap-2 md:grid-cols-3">
            <Input value={fundId} maxLength={64} placeholder="procurement" onChange={(event) => setFundId(event.target.value)} />
            <Input value={fundName} maxLength={128} placeholder={t('mc.accounts.name')} onChange={(event) => setFundName(event.target.value)} />
            <Input value={purpose} maxLength={255} placeholder={t('mc.accounts.purpose')} onChange={(event) => setPurpose(event.target.value)} />
          </div>
          <div>
            <Label>{t('mc.accounts.reason')}</Label>
            <Input value={createReason} maxLength={255} onChange={(event) => setCreateReason(event.target.value)} />
          </div>
          <Button size="sm" disabled={!/^[a-z0-9][a-z0-9._:-]{0,63}$/.test(fundId) || !fundName.trim()
            || createReason.trim().length < 3 || busy} onClick={() => void mutate({ operation: 'create-fund',
              profileKey: `treasury:${fundId}`, displayName: fundName.trim(), purpose: purpose.trim() }, createReason)}>
            {t('mc.accounts.create')}
          </Button>
        </div> : null}
      </> : null}
    </Card>
  );
}

function Balances({ account, large = false }: { account: MinecraftManagedAccountDto; large?: boolean }) {
  return <div className={`mt-2 flex flex-wrap gap-1 ${large ? 'text-sm' : 'text-xs'}`}>
    {Object.entries(account.balances).sort(([a], [b]) => a.localeCompare(b)).map(([currency, balance]) =>
      <span key={currency} className="rounded bg-neutral-800 px-2 py-0.5">{balance} {currency}</span>)}
  </div>;
}
