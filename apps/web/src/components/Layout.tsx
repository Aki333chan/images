import { NavLink, Outlet } from 'react-router-dom';
import { ROLE_LABELS } from '@aurum/shared';
import { useAuth } from '../lib/auth';
import { cn } from '../lib/cn';
import { Badge, Button } from './ui';

interface NavItem {
  to: string;
  label: string;
  permission: string | null;
  badge?: number;
}

/**
 * Навигация строится из текущих прав (me.permissions). При событии
 * permissions.updated контекст перезапрашивает /auth/me — пункты меню
 * появляются/исчезают без перезагрузки страницы.
 */
export function Layout() {
  const { me, ticketsBadge, messagesBadge, hasPermission, logout } = useAuth();
  if (!me) return null;

  const items: NavItem[] = [
    { to: '/servers', label: 'Серверы', permission: 'servers.view' },
    { to: '/tickets', label: 'Тикеты', permission: 'tickets.view', badge: ticketsBadge },
    { to: '/messages', label: 'Сообщения', permission: null, badge: messagesBadge },
    // Доступы видны и Админу: у него есть право заводить Модераторов.
    { to: '/access', label: 'Доступы', permission: 'users.create.moderator' },
    { to: '/settings', label: 'Настройки', permission: 'users.manage' },
    { to: '/audit', label: 'Аудит', permission: 'audit.view' },
    { to: '/security', label: 'Безопасность', permission: null },
  ];

  return (
    <div className="flex min-h-screen">
      <aside className="flex w-56 flex-col border-r border-border bg-card/50 p-4">
        <div className="mb-6">
          <div className="text-lg font-bold text-primary">Aurum Panel</div>
          <div className="mt-1 text-xs text-muted">
            {me.user.nickname ?? me.user.displayName} ·{' '}
            <Badge variant="outline">{ROLE_LABELS[me.user.role]}</Badge>
          </div>
        </div>
        <nav className="flex flex-1 flex-col gap-1">
          {items
            .filter((i) => i.permission === null || hasPermission(i.permission))
            .map((i) => (
              <NavLink
                key={i.to}
                to={i.to}
                className={({ isActive }) =>
                  cn(
                    'flex items-center justify-between rounded-md px-3 py-2 text-sm transition-colors',
                    isActive ? 'bg-primary/15 text-primary' : 'text-muted hover:bg-white/5 hover:text-neutral-100',
                  )
                }
              >
                <span>{i.label}</span>
                {i.badge !== undefined && i.badge > 0 && (
                  <Badge variant="destructive">{i.badge}</Badge>
                )}
              </NavLink>
            ))}
        </nav>
        <Button variant="outline" size="sm" onClick={() => void logout()}>
          Выйти
        </Button>
      </aside>
      <main className="flex-1 p-6">
        <Outlet />
      </main>
    </div>
  );
}
