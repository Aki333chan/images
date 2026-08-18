import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { ROLE_LABELS } from '@aurum/shared';
import { useAuth } from '../lib/auth';
import { cn } from '../lib/cn';
import { Badge, Button } from './ui';
import { AiAssistant } from './AiAssistant';

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
 *
 * РАСКЛАДКА. С lg и шире — привычные две колонки: меню слева, содержимое
 * справа. Уже lg меню занимало бы больше трети экрана, поэтому оно уезжает
 * за кнопку-гамбургер и открывается поверх содержимого.
 */
export function Layout() {
  const { me, ticketsBadge, messagesBadge, hasPermission, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();

  // Переход на другой экран закрывает меню. Отдельно от обработчика на
  // пункте: сюда попадают и переходы «мимо меню» — например, редирект
  // с недоступного сервера обратно к списку.
  useEffect(() => setMenuOpen(false), [location.pathname]);

  // Escape закрывает меню — привычно для всего, что открывается поверх.
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenuOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [menuOpen]);

  // Пока меню открыто, страница под ним не должна прокручиваться: иначе
  // на телефоне палец «проваливается» в содержимое за панелью.
  useEffect(() => {
    if (!menuOpen) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [menuOpen]);

  if (!me) return null;

  const items: NavItem[] = [
    { to: '/servers', label: 'Серверы', permission: 'servers.view' },
    { to: '/tickets', label: 'Тикеты', permission: 'tickets.view', badge: ticketsBadge },
    { to: '/messages', label: 'Сообщения', permission: null, badge: messagesBadge },
    // Доступы видны и Админу: у него есть право заводить Модераторов.
    { to: '/access', label: 'Доступы', permission: 'users.create.moderator' },
    // Без права: внутри есть личный блок — свой ник и пароль.
    { to: '/settings', label: 'Настройки', permission: null },
    { to: '/audit', label: 'Аудит', permission: 'audit.view' },
    { to: '/security', label: 'Безопасность', permission: null },
  ];

  const visible = items.filter((i) => i.permission === null || hasPermission(i.permission));
  /** Непрочитанное в скрытом меню — иначе о нём никак не узнать. */
  const totalBadge = visible.reduce((sum, i) => sum + (i.badge ?? 0), 0);

  const sidebar = (
    <>
      <div className="mb-6">
        <div className="text-lg font-bold text-primary">Aurum Panel</div>
        <div className="mt-1 text-xs text-muted">
          {me.user.nickname ?? me.user.email} ·{' '}
          <Badge variant="outline">{ROLE_LABELS[me.user.role]}</Badge>
        </div>
      </div>
      <nav className="flex flex-1 flex-col gap-1">
        {visible.map((i) => (
          <NavLink
            key={i.to}
            to={i.to}
            onClick={() => setMenuOpen(false)}
            className={({ isActive }) =>
              cn(
                // min-h-11 — по тому же правилу, что и кнопки: попасть
                // пальцем в пункт меню с первого раза.
                'flex min-h-11 items-center justify-between rounded-md px-3 py-2 text-sm transition-colors',
                isActive
                  ? 'bg-primary/15 text-primary'
                  : 'text-muted hover:bg-white/5 hover:text-neutral-100',
              )
            }
          >
            <span>{i.label}</span>
            {i.badge !== undefined && i.badge > 0 && <Badge variant="destructive">{i.badge}</Badge>}
          </NavLink>
        ))}
      </nav>
      <Button variant="outline" size="sm" onClick={() => void logout()}>
        Выйти
      </Button>
    </>
  );

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      {/* Шапка с гамбургером — только пока меню скрыто (уже lg). */}
      <header
        className="sticky top-0 z-30 flex items-center gap-3 border-b border-border bg-card/95 px-3 py-2 backdrop-blur lg:hidden"
        style={{ paddingTop: 'max(0.5rem, env(safe-area-inset-top))' }}
      >
        <button
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label="Открыть меню"
          aria-expanded={menuOpen}
          className="relative flex h-11 w-11 shrink-0 items-center justify-center rounded-md hover:bg-white/5"
        >
          <BurgerIcon />
          {totalBadge > 0 && (
            <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-destructive" />
          )}
        </button>
        <span className="truncate text-base font-bold text-primary">Aurum Panel</span>
      </header>

      {/* Затемнение под панелью: клик по нему закрывает меню. */}
      {menuOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/60 lg:hidden"
          onClick={() => setMenuOpen(false)}
          aria-hidden
        />
      )}

      <aside
        className={cn(
          'flex flex-col border-border bg-card p-4',
          // Мобильный: панель поверх содержимого, выезжает слева.
          'fixed inset-y-0 left-0 z-50 w-72 max-w-[85vw] border-r',
          // visibility, а не только сдвиг: уехавшая за край панель остаётся
          // в DOM ради анимации, и без этого её пункты попадали бы под Tab
          // и читались скринридером. Переход навешан и на visibility, чтобы
          // скрытие сработало в конце анимации, а не оборвало её.
          'transition-[transform,visibility] duration-200',
          menuOpen ? 'visible translate-x-0' : 'invisible -translate-x-full',
          // Десктоп: обычная колонка в потоке, всегда на месте и видима —
          // состояние menuOpen на этой ширине ни на что не влияет.
          'lg:visible lg:static lg:z-auto lg:w-56 lg:max-w-none lg:translate-x-0 lg:bg-card/50',
        )}
      >
        <button
          type="button"
          onClick={() => setMenuOpen(false)}
          aria-label="Закрыть меню"
          className="absolute right-2 top-2 flex h-11 w-11 items-center justify-center rounded-md text-muted hover:bg-white/5 lg:hidden"
        >
          ✕
        </button>
        {sidebar}
      </aside>

      {/* min-w-0 обязателен: без него флекс-элемент не сжимается уже своего
          содержимого, и широкая таблица или длинная строка растягивают всю
          страницу — появляется горизонтальная прокрутка. */}
      <main className="min-w-0 flex-1 p-3 sm:p-4 lg:p-6">
        <Outlet />
      </main>

      {/* Ассистент живёт в раскладке, а не на странице: кнопка должна быть
          видна на всех экранах панели. Сам компонент прячется, если у роли
          нет права ai.chat. */}
      <AiAssistant />
    </div>
  );
}

function BurgerIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M4 7h16M4 12h16M4 17h16"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  );
}
