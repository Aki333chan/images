import { useCallback, useEffect, useState } from 'react';
import type {
  AppSettingsDto,
  PendingUserDto,
  SmtpSettingsDto,
  SmtpTestResultDto,
} from '@aurum/shared';
import { ROLE_LABELS } from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../components/ui';

/**
 * Настройки панели — только для ГМ.
 *
 * Три блока: правила создания аккаунтов, ожидающие подтверждения заявки и
 * настройки почты. Заявки здесь же, а не отдельным пунктом меню: переключатель
 * и очередь, которую он порождает, читаются вместе.
 */
export function SettingsPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">Настройки</h1>
      <AccountRules />
      <PendingApprovals />
      <SmtpSettings />
    </div>
  );
}

function AccountRules() {
  const [settings, setSettings] = useState<AppSettingsDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api<AppSettingsDto>('/api/settings')
      .then(setSettings)
      .catch((e: Error) => setError(e.message));
  }, []);

  async function toggle(value: boolean) {
    setBusy(true);
    setError('');
    try {
      setSettings(
        await api<AppSettingsDto>('/api/settings', {
          method: 'PUT',
          body: JSON.stringify({ requireGmApprovalForAdminCreatedAccounts: value }),
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!settings) return <Card>{error ? <ErrorText>{error}</ErrorText> : <Spinner />}</Card>;

  const on = settings.requireGmApprovalForAdminCreatedAccounts;
  return (
    <Card className="space-y-3">
      <h2 className="font-semibold">Создание учётных записей</h2>
      {/* Вся строка — цель нажатия: нажать в текст так же надёжно, как
          в саму галочку (13×13 пальцем не берётся). */}
      <label className="-mx-2 flex cursor-pointer items-start gap-3 rounded-md px-2 py-2 hover:bg-white/5">
        <input
          type="checkbox"
          className="mt-0.5 h-5 w-5 shrink-0 accent-primary"
          checked={on}
          disabled={busy}
          onChange={(e) => void toggle(e.target.checked)}
        />
        <span className="text-sm">
          Подтверждать аккаунты, созданные Админами
          <span className="mt-1 block text-xs text-muted">
            {on
              ? 'Аккаунт, заведённый Админом, ждёт вашего решения: он неактивен, письмо с паролем не отправляется, пока вы не подтвердите.'
              : 'Аккаунты Админов активируются сразу — так же, как если бы их создали вы. Письмо с паролем уходит немедленно.'}
          </span>
        </span>
      </label>
      <p className="text-xs text-muted">
        Админ в любом случае может создавать только Модераторов. Роли выше выдаёте вы.
      </p>
      {error && <ErrorText>{error}</ErrorText>}
    </Card>
  );
}

function PendingApprovals() {
  const [pending, setPending] = useState<PendingUserDto[] | null>(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(() => {
    api<PendingUserDto[]>('/api/users/pending')
      .then(setPending)
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

  async function decide(id: string, action: 'approve' | 'reject') {
    setBusy(id);
    setError('');
    setNotice('');
    try {
      const res = await api<{ emailSent?: boolean; emailError?: string }>(
        `/api/users/pending/${id}/${action}`,
        { method: 'POST' },
      );
      if (action === 'approve') {
        setNotice(
          res.emailSent
            ? 'Аккаунт активирован, письмо с временным паролем отправлено.'
            : `Аккаунт активирован, но письмо не ушло: ${res.emailError ?? 'проверьте настройки почты'}. Передайте пароль лично — для этого выдайте новый на экране «Доступы».`,
        );
      }
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy('');
    }
  }

  if (!pending) return null;

  return (
    <Card className="space-y-3">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">Ожидают подтверждения</h2>
        {pending.length > 0 && <Badge variant="destructive">{pending.length}</Badge>}
      </div>

      {pending.length === 0 ? (
        <p className="text-xs text-muted">Заявок нет.</p>
      ) : (
        <ul className="space-y-2">
          {pending.map((p) => (
            <li
              key={p.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded border border-border px-3 py-2"
            >
              <div>
                <div className="text-sm font-medium">
                  {p.displayName} <Badge variant="outline">{ROLE_LABELS[p.role]}</Badge>
                </div>
                <div className="text-xs text-muted">
                  {p.email}
                  {p.createdBy && <> · заявку подал {p.createdBy.nickname ?? p.createdBy.displayName}</>}
                </div>
              </div>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  disabled={busy === p.id}
                  onClick={() => void decide(p.id, 'approve')}
                >
                  Подтвердить
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={busy === p.id}
                  onClick={() => void decide(p.id, 'reject')}
                >
                  Отклонить
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {notice && <p className="text-xs text-emerald-400">{notice}</p>}
      {error && <ErrorText>{error}</ErrorText>}
    </Card>
  );
}

function SmtpSettings() {
  const [settings, setSettings] = useState<SmtpSettingsDto | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [test, setTest] = useState<SmtpTestResultDto | null>(null);

  useEffect(() => {
    api<SmtpSettingsDto>('/api/settings/smtp')
      .then(setSettings)
      .catch((e: Error) => setError(e.message));
  }, []);

  if (!settings) return null;

  async function save() {
    setBusy(true);
    setError('');
    setTest(null);
    try {
      const next = await api<SmtpSettingsDto>('/api/settings/smtp', {
        method: 'PUT',
        body: JSON.stringify({ ...settings, password: password || undefined }),
      });
      setSettings(next);
      setPassword('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function runTest() {
    setBusy(true);
    setTest(null);
    try {
      setTest(await api<SmtpTestResultDto>('/api/settings/smtp/test', { method: 'POST' }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const patch = (p: Partial<SmtpSettingsDto>) => setSettings({ ...settings, ...p });

  return (
    <Card className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">Почта (SMTP)</h2>
        <Badge variant={settings.configured ? 'success' : 'outline'}>
          {settings.configured ? 'настроена' : 'не настроена'}
        </Badge>
      </div>
      <p className="text-xs text-muted">
        Через этот ящик уходят письма с временными паролями. Используйте обычный почтовый ящик на
        своём домене — сторонние сервисы рассылок не нужны.
      </p>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Сервер (host)</Label>
          <Input
            value={settings.host}
            onChange={(e) => patch({ host: e.target.value })}
            placeholder="mail.aurumgg.ovh"
          />
        </div>
        <div>
          <Label>Порт</Label>
          <Select
            value={String(settings.port)}
            onChange={(v) =>
              // 465 — TLS сразу, 587 — STARTTLS. Их всегда путают, поэтому
              // выбор шифрования привязан к порту, а не оставлен отдельно.
              patch({ port: Number(v), secure: Number(v) === 465 })
            }
            options={[
              { value: '587', label: '587 — STARTTLS (обычно этот)' },
              { value: '465', label: '465 — SSL/TLS' },
              { value: '25', label: '25 — без шифрования' },
            ]}
          />
        </div>
        <div>
          <Label>Логин</Label>
          <Input
            value={settings.user}
            onChange={(e) => patch({ user: e.target.value })}
            placeholder="panel@aurumgg.ovh"
            autoComplete="off"
          />
        </div>
        <div>
          <Label>Пароль</Label>
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            placeholder={settings.hasPassword ? 'сохранён — введите, чтобы заменить' : 'пароль ящика'}
          />
        </div>
        <div className="sm:col-span-2">
          <Label>Адрес отправителя (from)</Label>
          <Input
            value={settings.from}
            onChange={(e) => patch({ from: e.target.value })}
            placeholder="Aurum Panel &lt;panel@aurumgg.ovh&gt;"
          />
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {test && (
        <p className={`text-xs ${test.ok ? 'text-emerald-400' : 'text-red-400'}`}>
          {test.ok ? 'Соединение с SMTP установлено.' : `Не удалось: ${test.error}`}
        </p>
      )}

      {/* flex-wrap: две кнопки и пояснение в одну строку на телефоне не
          помещаются, и без переноса пояснение сжималось в столбик по слову
          у самого края. Пояснение уводим на свою строку — basis-full. */}
      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => void save()} disabled={busy}>
          {busy ? 'Сохраняем…' : 'Сохранить'}
        </Button>
        <Button variant="outline" onClick={() => void runTest()} disabled={busy}>
          Проверить соединение
        </Button>
        <span className="basis-full text-xs text-muted sm:basis-auto">
          Проверка не отправляет письмо.
        </span>
      </div>
    </Card>
  );
}
