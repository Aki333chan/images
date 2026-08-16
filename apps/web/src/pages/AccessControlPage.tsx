import { useEffect, useState } from 'react';
import {
  ROLE_LABELS,
  ROLES,
  type CreateUserResultDto,
  type Role,
  type ServerDto,
  type UserAdminDto,
} from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner } from '../components/ui';

/**
 * Экран управления доступом (только ГМ): смена ролей и привязка серверов.
 * Изменения мгновенно доезжают до затронутого пользователя по WS
 * (permissions.updated) — без релогина.
 */
export function AccessControlPage() {
  const { hasPermission } = useAuth();
  const [users, setUsers] = useState<UserAdminDto[] | null>(null);
  const [servers, setServers] = useState<ServerDto[] | null>(null);

  // Списком и ролями распоряжается только ГМ. Админ сюда попадает ради
  // одной кнопки — завести Модератора, — и запрашивать список ему нечем.
  const canManage = hasPermission('users.manage');

  useEffect(() => {
    if (!canManage) return;
    void api<UserAdminDto[]>('/api/users').then(setUsers);
    void api<ServerDto[]>('/api/servers').then(setServers);
  }, [canManage]);

  async function setRole(user: UserAdminDto, role: Role) {
    const updated = await api<UserAdminDto>(`/api/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ role }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  async function toggleActive(user: UserAdminDto) {
    const updated = await api<UserAdminDto>(`/api/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ isActive: !user.isActive }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  async function toggleServer(user: UserAdminDto, serverId: string) {
    const next = user.serverIds.includes(serverId)
      ? user.serverIds.filter((id) => id !== serverId)
      : [...user.serverIds, serverId];
    const updated = await api<UserAdminDto>(`/api/users/${user.id}/servers`, {
      method: 'PUT',
      body: JSON.stringify({ serverIds: next }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  if (!canManage) {
    return (
      <div>
        <h1 className="mb-4 text-xl font-bold">Управление доступом</h1>
        <CreateUserForm canManage={false} onCreated={() => undefined} />
        <p className="mt-3 text-xs text-muted">
          Вы можете заводить учётные записи с ролью Модератор. Список сотрудников и выдача доступов
          к серверам — за ГМ.
        </p>
      </div>
    );
  }

  if (!users || !servers) return <Spinner />;

  return (
    <div>
      <h1 className="mb-4 text-xl font-bold">Управление доступом</h1>
      <CreateUserForm canManage onCreated={(user) => setUsers((prev) => [...(prev ?? []), user])} />
      <div className="space-y-4">
        {users.map((user) => (
          <Card key={user.id}>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-semibold">
                  {user.displayName}{' '}
                  {!user.isActive && <Badge variant="destructive">деактивирован</Badge>}{' '}
                  {user.totpEnabled && <Badge variant="success">2FA</Badge>}
                </div>
                <div className="text-xs text-muted">{user.email}</div>
              </div>
              <div className="flex items-center gap-2">
                <Select
                  value={user.role}
                  onChange={(v) => void setRole(user, v as Role)}
                  options={ROLES.map((r) => ({ value: r, label: ROLE_LABELS[r] }))}
                />
                <Button size="sm" variant="outline" onClick={() => void toggleActive(user)}>
                  {user.isActive ? 'Деактивировать' : 'Активировать'}
                </Button>
              </div>
            </div>

            {user.role !== 'OWNER' && (
              <div className="mt-3 border-t border-border pt-3">
                <p className="mb-2 text-xs text-muted">
                  Доступные сервера (
                  {user.serverIds.length === 0 ? 'нет доступа' : user.serverIds.length}):
                </p>
                <div className="flex flex-wrap gap-2">
                  {servers.map((s) => {
                    const attached = user.serverIds.includes(s.id);
                    return (
                      // Плашка сама по себе высотой 24 px — это переключатель
                      // доступа к серверу, и промахнуться по нему нельзя.
                      // Область нажатия увеличена до 40 px, вид плашки прежний.
                      <button
                        key={s.id}
                        className="flex min-h-10 items-center rounded-md px-1 hover:bg-white/5 sm:min-h-0 sm:px-0"
                        onClick={() => void toggleServer(user, s.id)}
                      >
                        <Badge variant={attached ? 'default' : 'outline'}>
                          {attached ? '✓ ' : ''}
                          {s.name}
                        </Badge>
                      </button>
                    );
                  })}
                  {servers.length === 0 && (
                    <span className="text-xs text-muted">Сервера ещё не синхронизированы</span>
                  )}
                </div>
              </div>
            )}
          </Card>
        ))}
      </div>
    </div>
  );
}

/**
 * Создание учётной записи.
 *
 * Пароль здесь не вводится: панель генерирует одноразовый и отправляет его
 * письмом. Так пароль не проходит через руки создающего и не оседает в
 * переписке, а при первом входе человек всё равно задаёт свой.
 */
function CreateUserForm({
  canManage,
  onCreated,
}: {
  /** true — полное право (ГМ): можно выбрать роль. */
  canManage: boolean;
  onCreated: (user: UserAdminDto) => void;
}) {
  const [open, setOpen] = useState(false);
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<Role>('MODERATOR');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await api<CreateUserResultDto>('/api/users', {
        method: 'POST',
        body: JSON.stringify({
          email: email.trim(),
          displayName: displayName.trim(),
          // Админ заводит только Модераторов — селектора у него нет.
          role: canManage ? role : 'MODERATOR',
        }),
      });

      if (!result.activated) {
        setNotice(
          'Заявка отправлена ГМ. Аккаунт заработает и письмо с паролем уйдёт после подтверждения.',
        );
      } else if (result.emailSent) {
        setNotice(`Аккаунт создан, письмо с временным паролем отправлено на ${email.trim()}.`);
      } else {
        setNotice(
          `Аккаунт создан, но письмо не ушло: ${result.emailError ?? 'проверьте настройки почты'}. ` +
            'Настройте почту в разделе «Настройки» и выдайте пароль повторно.',
        );
      }

      if (result.activated) onCreated(result.user);
      setEmail('');
      setDisplayName('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <div className="mb-4 space-y-2">
        <Button onClick={() => setOpen(true)}>Добавить пользователя</Button>
        {notice && <p className="text-xs text-emerald-400">{notice}</p>}
      </div>
    );
  }

  const canSubmit = email.includes('@') && displayName.trim().length > 0 && !busy;

  return (
    <Card className="mb-4 space-y-3">
      <h2 className="font-semibold">Новый пользователь</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Email (логин)</Label>
          <Input
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="off"
            placeholder="friend@example.com"
          />
        </div>
        <div>
          <Label>Отображаемое имя</Label>
          <Input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Как показывать в панели"
          />
        </div>
        <div>
          <Label>Роль</Label>
          {canManage ? (
            <Select
              value={role}
              onChange={(v) => setRole(v as Role)}
              options={ROLES.filter((r) => r !== 'OWNER').map((r) => ({
                value: r,
                label: ROLE_LABELS[r],
              }))}
            />
          ) : (
            <div className="pt-1 text-sm">
              {ROLE_LABELS.MODERATOR}
              <span className="ml-2 text-xs text-muted">роли выше выдаёт ГМ</span>
            </div>
          )}
        </div>
      </div>

      {error && <ErrorText>{error}</ErrorText>}

      <p className="text-xs text-muted">
        Пароль придумывать не нужно: панель сгенерирует временный и отправит его письмом. При первом
        входе человек задаст свой пароль и выберет ник. Доступ к серверам выдаётся ниже, после
        создания: по умолчанию его нет ни к одному.
      </p>

      <div className="flex gap-2">
        <Button onClick={() => void submit()} disabled={!canSubmit}>
          {busy ? 'Создаём…' : 'Создать'}
        </Button>
        <Button variant="outline" onClick={() => setOpen(false)} disabled={busy}>
          Отмена
        </Button>
      </div>
    </Card>
  );
}
