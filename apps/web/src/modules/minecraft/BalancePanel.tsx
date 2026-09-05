import { useCallback, useEffect, useState } from 'react';
import type { MinecraftBalanceChangeDto, MinecraftBalanceDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { Button, ErrorText, Input, Label, Spinner } from '../../components/ui';
import { useApiText, useT } from '../../i18n';

/**
 * Блок «Валюта» в карточке игрока.
 *
 * Работает через Vault: панель не разговаривает ни с одним конкретным
 * плагином экономики и знать про него не обязана — за Vault может стоять
 * EssentialsX, CMI или любой другой. Если Vault (или провайдера за ним) нет,
 * блок не прячется, а показывает, чего именно не хватает: возможность есть,
 * её нужно доустановить на игровой сервер.
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

  const canEdit = hasPermission('minecraft.economy.edit');
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
    setBusy(true);
    setError('');
    setResult('');
    try {
      const res = await api<MinecraftBalanceChangeDto>(`${base}/${direction}`, {
        method: 'POST',
        body: JSON.stringify({
          // Округление до копеек — то же, что делает бэкенд: пусть в поле и
          // в журнале будет одна и та же величина.
          amount: Math.round(value * 100) / 100,
          ...(reason.trim() ? { reason: reason.trim() } : {}),
        }),
      });
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
              disabled={unavailable || busy || !amount.trim()}
              title={unavailable ? `${t('mc.bal.deposit')} — ${shortHint}` : t('mc.bal.depositHint')}
              onClick={() => void change('deposit')}
            >
              {t('mc.bal.deposit')}
              {unavailable && <span className="ml-1 opacity-60">·{shortHint}</span>}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={unavailable || busy || !amount.trim()}
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
          <p className="text-xs text-muted">
            Любое начисление и списание записывается в журнал аудита: кто, кому, сколько и за что.
          </p>
        </div>
      )}

      {error && <ErrorText>{error}</ErrorText>}
      {result && <p className="text-xs text-emerald-400">{result}</p>}
    </div>
  );
}
