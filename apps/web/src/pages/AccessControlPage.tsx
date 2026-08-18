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
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busyUserId, setBusyUserId] = useState<string | null>(null);

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

  /** Разовое разрешение сменить себе ник. */
  async function toggleNicknameChange(user: UserAdminDto) {
    const updated = await api<UserAdminDto>(`/api/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ nicknameChangeAllowed: !user.nicknameChangeAllowed }),
    });
    setUsers((prev) => prev?.map((u) => (u.id === user.id ? updated : u)) ?? null);
  }

  /**
   * Сброс пароля: человеку уходит новый временный пароль письмом.
   *
   * Прежние входы обрываются сразу — сброс нужен и когда пароль забыт, и
   * когда есть подозрение, что им кто-то завладел.
   */
  async function resetPassword(user: UserAdminDto) {
    const who = user.nickname ?? user.email;
    if (!confirm(`Сбросить пароль ${who}? Все его текущие входы завершатся.`)) return;
    setBusyUserId(user.id);
    setNotice('');
    setError('');
    try {
      const result = await api<{ emailSent: boolean; emailError?: string }>(
        `/api/users/${user.id}/reset-password`,
        { method: 'POST' },
      );
      setNotice(
        result.emailSent
          ? `Временный пароль отправлен на ${user.email}.`
          : `Пароль сброшен, но письмо не ушло: ${result.emailError ?? 'проверьте настройки почты'}.`,
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyUserId(null);
    }
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
      {notice && <p className="mb-3 text-xs text-emerald-400">{notice}</p>}
      {error && <div className="mb-3"><ErrorText>{error}</ErrorText></div>}
      <div className="space-y-4">
        {users.map((user) => (
          <Card key={user.id}>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-semibold">
                  {/* Ник — единственное имя сотрудника. Пока он не выбран
                      (человек ещё не входил), показываем email: иначе строку
                      было бы не отличить от соседней. */}
                  {user.nickname ?? (
                    <span className="text-muted">{user.email} · ник ещё не выбран</span>
                  )}{' '}
                  {!user.isActive && <Badge variant="destructive">деактивирован</Badge>}{' '}
                  {user.totpEnabled && <Badge variant="success">2FA</Badge>}{' '}
                  {user.nicknameChangeAllowed && <Badge variant="outline">смена ника открыта</Badge>}
                </div>
                {user.nickname && <div className="text-xs text-muted">{user.email}</div>}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Select
                  value={user.role}
                  onChange={(v) => void setRole(user, v as Role)}
                  options={ROLES.map((r) => ({ value: r, label: ROLE_LABELS[r] }))}
                />
                <Button size="sm" variant="outline" onClick={() => void toggleActive(user)}>
                  {user.isActive ? 'Деактивировать' : 'Активировать'}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busyUserId === user.id}
                  title="Отправить новый временный пароль письмом"
                  onClick={() => void resetPassword(user)}
                >
                  Сбросить пароль
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  title="Разовое разрешение сменить себе ник"
                  onClick={() => void toggleNicknameChange(user)}
                >
                  {user.nicknameChangeAllowed ? 'Закрыть смену ника' : 'Разрешить смену ника'}
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
 * Создание учётной записи: адрес и роль, больше ничего.
 *
 * Пароль здесь не вводится: панель генерирует одноразовый и отправляет его
 * письмом. Так пароль не проходит через руки создающего и не оседает в
 * переписке, а при первом входе человек всё равно задаёт свой.
 *
 * Имени тоже нет: сотрудник придумывает себе ник сам при первом входе.
 * Имя, назначенное кем-то другим, всё равно разошлось бы с тем, как человек
 * подписывается в переписке, и панели пришлось бы показывать оба.
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

  const canSubmit = email.includes('@') && !busy;

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
        Ни имени, ни пароля придумывать не нужно: панель сгенерирует временный пароль и отправит его
        письмом, а ник человек выберет себе сам при первом входе — под ним его и будут знать
        коллеги. Доступ к серверам выдаётся ниже, после создания: по умолчанию его нет ни к одному.
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
