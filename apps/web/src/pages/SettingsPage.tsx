import { useCallback, useEffect, useState } from 'react';
import type {
  AlertSettingsDto,
  AppSettingsDto,
  PendingUserDto,
  SmtpSettingsDto,
  SmtpTestResultDto,
} from '@aurum/shared';
import { ALERT_SETTINGS_LIMITS, DEFAULT_ALERT_SETTINGS, ROLE_LABELS } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { AiSettingsCard } from './AiSettingsCard';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../components/ui';

/**
 * Настройки.
 *
 * Сверху — личные, они есть у всех: свой ник и свой пароль. Ниже — настройки
 * панели, их видит только ГМ: правила создания аккаунтов, очередь заявок,
 * почта и ассистент. Заявки здесь же, а не отдельным пунктом меню:
 * переключатель и очередь, которую он порождает, читаются вместе.
 */
export function SettingsPage() {
  const { hasPermission } = useAuth();
  const isGm = hasPermission('users.manage');

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">Настройки</h1>
      <MyNickname />
      <MyPassword />
      {isGm && (
        <>
          <AccountRules />
          <PendingApprovals />
          <SmtpSettings />
          <AlertSettings />
          <AiSettingsCard />
        </>
      )}
    </div>
  );
}

/**
 * Свой ник.
 *
 * Менять его можно, только когда ГМ разрешил, и разрешение разовое: ник —
 * это то, по чему сотрудника знают коллеги в переписке и чем он подписан в
 * журнале. У ГМ разрешение постоянное — просить его не у кого.
 */
function MyNickname() {
  const { me, hasPermission, refreshMe } = useAuth();
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  const allowed = !!me?.user.nicknameChangeAllowed || hasPermission('users.manage');
  const current = me?.user.nickname ?? '';

  useEffect(() => setValue(current), [current]);

  async function save() {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      await api('/api/auth/nickname', {
        method: 'POST',
        body: JSON.stringify({ nickname: value.trim() }),
      });
      await refreshMe();
      setSaved('Ник изменён.');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-3">
      <h2 className="font-semibold">Ник</h2>
      <p className="text-xs text-muted">
        Под этим ником вас видят коллеги в сообщениях и журнале аудита. Другого имени в панели
        нет.
      </p>

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <Input
          value={value}
          disabled={!allowed || busy}
          onChange={(e) => setValue(e.target.value)}
          placeholder="Ник"
          maxLength={31}
          className="sm:max-w-[260px]"
        />
        <Button
          size="sm"
          disabled={!allowed || busy || !value.trim() || value.trim() === current}
          onClick={() => void save()}
        >
          Сохранить ник
        </Button>
      </div>

      {!allowed && (
        <p className="text-xs text-muted">
          Смену ника открывает ГМ — попросите его. Разрешение действует на один раз.
        </p>
      )}
      {allowed && me?.user.nicknameChangeAllowed && (
        <p className="text-xs text-amber-400">
          ГМ разрешил вам сменить ник. Разрешение сгорит после сохранения.
        </p>
      )}
      {error && <ErrorText>{error}</ErrorText>}
      {saved && <p className="text-xs text-emerald-400">{saved}</p>}
    </Card>
  );
}

/** Свой пароль. Доступно всем и всегда — это действие над собой. */
function MyPassword() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  const mismatch = repeat.length > 0 && repeat !== newPassword;
  const canSubmit =
    !busy && currentPassword.length > 0 && newPassword.length >= 10 && repeat === newPassword;

  async function save() {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      await api('/api/auth/password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      setCurrentPassword('');
      setNewPassword('');
      setRepeat('');
      setSaved('Пароль изменён. Остальные ваши входы завершены.');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-3">
      <h2 className="font-semibold">Пароль</h2>
      <div className="grid gap-3 sm:grid-cols-3">
        <div>
          <Label>Текущий пароль</Label>
          <Input
            type="password"
            autoComplete="current-password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
          />
        </div>
        <div>
          <Label>Новый пароль</Label>
          <Input
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
          />
        </div>
        <div>
          <Label>Ещё раз</Label>
          <Input
            type="password"
            autoComplete="new-password"
            value={repeat}
            onChange={(e) => setRepeat(e.target.value)}
          />
        </div>
      </div>
      <p className="text-xs text-muted">
        Не короче 10 символов. После смены все остальные ваши входы завершатся — текущий
        останется.
      </p>
      {mismatch && <ErrorText>Пароли не совпадают</ErrorText>}
      <Button size="sm" disabled={!canSubmit} onClick={() => void save()}>
        Сменить пароль
      </Button>
      {error && <ErrorText>{error}</ErrorText>}
      {saved && <p className="text-xs text-emerald-400">{saved}</p>}
    </Card>
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
                  {p.email} <Badge variant="outline">{ROLE_LABELS[p.role]}</Badge>
                </div>
                {p.createdBy && (
                  <div className="text-xs text-muted">
                    заявку подал {p.createdBy.nickname ?? 'без ника'}
                  </div>
                )}
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
    // Снимок состояния: внутри асинхронной функции TypeScript уже не может
    // ручаться, что settings не обнулился.
    const current = settings;
    if (!current) return;
    setBusy(true);
    setError('');
    setTest(null);
    try {
      // Отправляем ровно те поля, которые принимает эндпоинт, а не весь
      // объект настроек. В ответе GET есть ещё configured и hasPassword —
      // это состояние, доступное только на чтение, и API отвергает запрос
      // целиком, если они в него попадают («property configured should not
      // exist»). Перечисляем поля явно, чтобы новое поле в ответе GET
      // снова молча не сломало сохранение.
      const next = await api<SmtpSettingsDto>('/api/settings/smtp', {
        method: 'PUT',
        body: JSON.stringify({
          host: current.host.trim(),
          port: current.port,
          secure: current.secure,
          user: current.user.trim(),
          from: current.from.trim(),
          // Пустое поле означает «оставить сохранённый пароль».
          ...(password ? { password } : {}),
        }),
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

/**
 * Алерты о перегрузке серверов.
 *
 * ПОРОГИ — В ПРОЦЕНТАХ ОТ ЛИМИТА СЕРВЕРА, а не от абстрактных 100. У
 * Pterodactyl лимит CPU задаётся в процентах от одного ядра (200 — два ядра),
 * и сырое значение сравнивать не с чем: 150% это перегрузка на сервере с
 * одним ядром и половина выделенного на сервере с тремя.
 *
 * ЗАДЕРЖКА — НЕ УКРАШЕНИЕ. Сервер уходит в потолок на запуске, на генерации
 * чанков, на распаковке бэкапа. Письмо на каждый такой всплеск сделает почту
 * нечитаемой за неделю, и тогда пропущенным окажется настоящий инцидент.
 */
function AlertSettings() {
  const [value, setValue] = useState<AlertSettingsDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  useEffect(() => {
    api<AlertSettingsDto>('/api/settings/alerts')
      .then(setValue)
      .catch(() => setValue({ ...DEFAULT_ALERT_SETTINGS }));
  }, []);

  async function save(next: AlertSettingsDto) {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      setValue(
        await api<AlertSettingsDto>('/api/settings/alerts', {
          method: 'PUT',
          body: JSON.stringify(next),
        }),
      );
      setSaved('Сохранено');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!value) return <Card><Spinner /></Card>;

  const L = ALERT_SETTINGS_LIMITS;
  const patch = (over: Partial<AlertSettingsDto>) => setValue({ ...value, ...over });

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">Алерты о перегрузке</h2>
        <Badge variant={value.enabled ? 'success' : 'outline'}>
          {value.enabled ? 'включены' : 'выключены'}
        </Badge>
      </div>

      <p className="text-xs text-muted">
        Письмо уходит тем, у кого есть доступ к этому серверу. ГМ получает письма по всем
        серверам — у него доступ ко всем по определению роли. Отправка идёт через тот же SMTP,
        что и письма с паролями: пока он не настроен, алерты никуда не уйдут.
      </p>

      <label className="flex cursor-pointer items-start gap-2 text-sm">
        <input
          type="checkbox"
          className="mt-1 h-4 w-4"
          checked={value.enabled}
          onChange={(e) => patch({ enabled: e.target.checked })}
        />
        <span>Присылать письма при перегрузке</span>
      </label>

      <div className="grid gap-3 sm:grid-cols-2">
        <ThresholdField
          label="Порог ЦПУ, % от лимита"
          value={value.cpuThresholdPercent}
          onChange={(v) => patch({ cpuThresholdPercent: v })}
        />
        <ThresholdField
          label="Порог памяти, % от лимита"
          value={value.memoryThresholdPercent}
          onChange={(v) => patch({ memoryThresholdPercent: v })}
        />
        <div>
          <Label>Держится дольше, мин</Label>
          <Input
            type="number"
            min={L.minSustainedMinutes}
            max={L.maxSustainedMinutes}
            value={value.sustainedMinutes}
            onChange={(e) => patch({ sustainedMinutes: Number(e.target.value) })}
          />
          <p className="mt-1 text-[11px] text-muted">
            Короткие всплески при запуске сервера — это норма, а не авария.
          </p>
        </div>
        <div>
          <Label>Не чаще одного письма в, мин</Label>
          <Input
            type="number"
            min={L.minCooldownMinutes}
            max={L.maxCooldownMinutes}
            value={value.cooldownMinutes}
            onChange={(e) => patch({ cooldownMinutes: Number(e.target.value) })}
          />
          <p className="mt-1 text-[11px] text-muted">
            Затянувшаяся перегрузка — одна проблема, а не письмо каждую минуту.
          </p>
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {saved && <p className="text-xs text-emerald-400">{saved}</p>}

      <div className="flex justify-end">
        <Button disabled={busy} onClick={() => void save(value)}>
          {busy ? 'Сохраняем…' : 'Сохранить'}
        </Button>
      </div>
    </Card>
  );
}

/**
 * Порог по одному ресурсу. Пустое поле означает «по этому ресурсу не следим» —
 * это осмысленный выбор, а не «забыли заполнить», поэтому значение nullable.
 */
function ThresholdField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number | null;
  onChange: (value: number | null) => void;
}) {
  const L = ALERT_SETTINGS_LIMITS;
  return (
    <div>
      <Label>{label}</Label>
      <Input
        type="number"
        min={L.minThreshold}
        max={L.maxThreshold}
        value={value ?? ''}
        placeholder="не следить"
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
      />
    </div>
  );
}
