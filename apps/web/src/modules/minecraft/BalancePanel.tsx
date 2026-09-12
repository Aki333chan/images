import { useCallback, useEffect, useRef, useState } from 'react';
import type { MinecraftBalanceChangeDto, MinecraftBalanceDto } from '@aurum/shared';
import { api, ApiError } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Button, ErrorText, Input, Label, Spinner } from '../../components/ui';
import { useApiText, useT } from '../../i18n';

/**
 * Блок «Валюта» в карточке игрока.
 *
 * При ACTIVE работает прямо через AurumCore, иначе — через совместимый Vault
 * fallback. Панель не разговаривает с конкретным старым провайдером и
 * показывает точную причину, если экономика недоступна.
 *
 * Каждое начисление и списание уходит в журнал аудита на бэкенде — вместе с
 * суммой, причиной и балансом до и после. Здесь это только сообщается
 * человеку, чтобы поле «за что» не выглядело необязательной формальностью.
 */
export function BalancePanel({ serverId, uuid }: { serverId: string; uuid: string }) {
  const t = useT();
  const apiText = useApiText();
  const { hasPermission } = useAuth();
  const [data, setData] = useState<MinecraftBalanceDto | null>(null);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [busy, setBusy] = useState(false);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);

  const canEdit = hasPermission('minecraft.economy.admin');
  const base = `/api/modules/minecraft/servers/${serverId}/players/${uuid}/balance`;

  const load = useCallback(() => {
    setError('');
    api<MinecraftBalanceDto>(base)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [base]);

  useEffect(load, [load]);

  async function change(direction: 'deposit' | 'withdraw') {
    const value = Number(amount.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) {
      setError(t('mc.bal.positive'));
      return;
    }
    const why = reason.trim();
    if (!why) {
      setError(t('mc.bal.reason'));
      return;
    }
    const rounded = Math.round(value * 100) / 100;
    const fingerprint = `${direction}\n${rounded}\n${why}`;
    const idempotencyKey = pending.current?.fingerprint === fingerprint
      ? pending.current.key
      : crypto.randomUUID();
    pending.current = { fingerprint, key: idempotencyKey };
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<MinecraftBalanceChangeDto>(`${base}/${direction}`, {
        method: 'POST',
        body: JSON.stringify({
          // Округление до копеек — то же, что делает бэкенд: пусть в поле и
          // в журнале будет одна и та же величина.
          amount: rounded,
          reason: why,
          idempotencyKey,
        }),
      });
      // A successful API round-trip is definitive (including ok:false from
      // the economy provider). A thrown timeout/unavailable response keeps
      // the key for the next click with unchanged fields.
      pending.current = null;
      if (!res.ok) {
        // Отказ провайдера («недостаточно средств») — это его текст, а не
        // сбой панели, и подменять его своим было бы неправдой.
        setError(apiText(res.error) || t('mc.bal.rejected'));
      } else {
        setResult(
          t(direction === 'deposit' ? 'mc.bal.deposited' : 'mc.bal.withdrawn', {
            value,
            before: res.balanceBefore,
            after: res.balanceAfter,
          }),
        );
        setAmount('');
        setReason('');
      }
      // Баланс обновляем в любом случае: даже отказ мог прийти после того,
      // как кто-то другой изменил счёт.
      setData((prev) =>
        prev && prev.available
          ? { ...prev, balance: res.balanceAfter, formatted: res.formatted ?? prev.formatted }
          : prev,
      );
    } catch (e) {
      // Conflict is definitive: this key can never describe this operation.
      // Unavailable/transport errors deliberately retain it for safe retry.
      if (e instanceof ApiError && e.code === 'idempotency-conflict') {
        pending.current = null;
      }
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!data && !error) return <Spinner />;

  // Валюты на сервере нет. Показываем причину и оставляем поля видимыми, но
  // неактивными — так понятно, что появится после установки Vault.
  const unavailable = !data?.available;
  const hint = apiText(data?.reason) || t('mc.bal.needVault');
  const shortHint = t(data?.code === 'no-companion' ? 'mc.bal.needCompanion' : 'mc.bal.needVault');

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <Label className="mb-0">{t('mc.bal.title')}</Label>
        <span className="text-lg font-semibold">
          {unavailable ? '—' : (data?.formatted ?? String(data?.balance ?? 0))}
        </span>
        {!unavailable && data?.currency && data.formatted === undefined && (
          <span className="text-xs text-muted">{data.currency}</span>
        )}
      </div>

      {unavailable && <p className="text-sm text-muted">{hint}</p>}

      {!canEdit && !unavailable && (
        <p className="text-xs text-muted">
          {t('mc.bal.readOnly')}
        </p>
      )}

      {canEdit && (
        <div className="space-y-2">
          {/* Поля в столбец на телефоне и в строку на десктопе: сумма узкая,
              причина занимает остаток ширины. */}
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              type="text"
              inputMode="decimal"
              value={amount}
              disabled={unavailable || busy}
              onChange={(e) => setAmount(e.target.value)}
              placeholder={t('mc.bal.amount')}
              className="sm:w-[140px]"
            />
            <Input
              type="text"
              value={reason}
              disabled={unavailable || busy}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t('mc.bal.reason')}
              maxLength={200}
              className="min-w-0 flex-1"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={unavailable || busy || !amount.trim() || !reason.trim()}
              title={unavailable ? `${t('mc.bal.deposit')} — ${shortHint}` : t('mc.bal.depositHint')}
              onClick={() => void change('deposit')}
            >
              {t('mc.bal.deposit')}
              {unavailable && <span className="ml-1 opacity-60">·{shortHint}</span>}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={unavailable || busy || !amount.trim() || !reason.trim()}
              title={unavailable ? `${t('mc.bal.withdraw')} — ${shortHint}` : t('mc.bal.withdrawHint')}
              onClick={() => void change('withdraw')}
            >
              {t('mc.bal.withdraw')}
              {unavailable && <span className="ml-1 opacity-60">·{shortHint}</span>}
            </Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={load}>
              {t('common.refresh')}
            </Button>
          </div>
          <p className="text-xs text-muted">{t('mc.economy.audited')}</p>
        </div>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {result && <p className="text-xs text-emerald-400">{result}</p>}
    </div>
  );
}
