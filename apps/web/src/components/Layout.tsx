import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { ROLE_LABELS } from '@aurum/shared';
import { useAuth } from '../lib/auth';
import { cn } from '../lib/cn';
import { Badge, Button } from './ui';
import {
  IconAccess,
  IconAudit,
  IconClose,
  IconLogout,
  IconMarket,
  IconMenu,
  IconMessages,
  IconSecurity,
  IconServers,
  IconSettings,
  IconTickets,
  type IconProps,
} from './icons';
import { AiAssistant } from './AiAssistant';

interface NavItem {
  to: string;
  label: string;
  permission: string | null;
  badge?: number;
  /** Иконка пункта. По ней меню читается боковым зрением, без чтения подписей. */
  icon: (p: IconProps) => JSX.Element;
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
    { to: '/servers', label: 'Серверы', permission: 'servers.view', icon: IconServers },
    {
      to: '/tickets',
      label: 'Тикеты',
      permission: 'tickets.view',
      badge: ticketsBadge,
      icon: IconTickets,
    },
    {
      to: '/messages',
      label: 'Сообщения',
      permission: null,
      badge: messagesBadge,
      icon: IconMessages,
    },
    // Установка плагина — запуск чужого кода на сервере, поэтому право
    // по умолчанию только у ГМ и Админа.
    { to: '/market', label: 'Маркет', permission: 'minecraft.plugins.install', icon: IconMarket },
    // Доступы видны и Админу: у него есть право заводить Модераторов.
    { to: '/access', label: 'Доступы', permission: 'users.create.moderator', icon: IconAccess },
    // Без права: внутри есть личный блок — свой ник и пароль.
    { to: '/settings', label: 'Настройки', permission: null, icon: IconSettings },
    { to: '/audit', label: 'Аудит', permission: 'audit.view', icon: IconAudit },
    { to: '/security', label: 'Безопасность', permission: null, icon: IconSecurity },
  ];

  const visible = items.filter((i) => i.permission === null || hasPermission(i.permission));
  /** Непрочитанное в скрытом меню — иначе о нём никак не узнать. */
  const totalBadge = visible.reduce((sum, i) => sum + (i.badge ?? 0), 0);

  const sidebar = (
    <>
      <div className="mb-5 flex items-center gap-2.5 pr-10 lg:pr-0">
        <img
          src="/logo-128.png"
          alt=""
          width={32}
          height={32}
          className="h-8 w-8 shrink-0 object-contain drop-shadow-[0_2px_7px_rgba(0,0,0,.55)]"
        />
        <div className="min-w-0">
          <div className="text-sm font-medium tracking-wide text-primary-200">Aurum Panel</div>
          {/* Ник и роль одной строкой под названием: это ответ на вопрос
              «под кем я сейчас», который возникает у тех, кто держит две
              учётки — свою и служебную. */}
          <div className="mt-0.5 flex items-center gap-1.5 text-[10.5px] text-muted">
            <span className="truncate">{me.user.nickname ?? me.user.email}</span>
            <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[9.5px]">
              {ROLE_LABELS[me.user.role]}
            </Badge>
          </div>
        </div>
      </div>

      <nav className="flex flex-1 flex-col gap-0.5">
        {visible.map((i) => (
          <NavLink
            key={i.to}
            to={i.to}
            onClick={() => setMenuOpen(false)}
            className={({ isActive }) =>
              cn(
                // min-h-11 — по тому же правилу, что и кнопки: попасть
                // пальцем в пункт меню с первого раза.
                'group relative flex min-h-11 items-center gap-2.5 rounded-md py-2 pl-3.5 pr-2.5',
                'text-sm transition-[color,background-color] duration-200',
                isActive
                  ? 'bg-primary/[0.13] text-primary-200 shadow-[inset_0_0_0_1px_rgba(145,132,217,.28)]'
                  : 'text-muted hover:bg-white/5 hover:text-neutral-100',
              )
            }
          >
            {({ isActive }) => (
              <>
                {/* Засечка у активного пункта: на узкой панели подложка
                    читается слабо, а светящаяся полоска у края — сразу. */}
                {isActive && (
                  <span className="absolute left-0 top-3 bottom-3 w-0.5 rounded-r-sm bg-primary shadow-[0_0_11px_1px_rgba(145,132,217,.65)]" />
                )}
                <i.icon size={16} className="shrink-0" />
                <span className="truncate">{i.label}</span>
                {i.badge !== undefined && i.badge > 0 && (
                  <Badge variant="destructive" className="ml-auto font-mono">
                    {i.badge}
                  </Badge>
                )}
              </>
            )}
          </NavLink>
        ))}
      </nav>

      <Button variant="outline" size="sm" className="mt-3 w-full" onClick={() => void logout()}>
        <IconLogout size={14} />
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
          className="relative flex h-11 w-11 shrink-0 items-center justify-center rounded-md border border-neutral-800 transition-colors hover:border-primary hover:bg-primary/10"
        >
          <IconMenu size={18} />
          {totalBadge > 0 && (
            <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-destructive" />
          )}
        </button>
        <img src="/logo-128.png" alt="" width={26} height={26} className="h-[26px] w-[26px] object-contain" />
        <span className="truncate text-[15px] font-medium text-primary-200">Aurum Panel</span>
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
          'lg:visible lg:static lg:z-auto lg:w-56 lg:max-w-none lg:translate-x-0 lg:bg-card/60',
        )}
      >
        <button
          type="button"
          onClick={() => setMenuOpen(false)}
          aria-label="Закрыть меню"
          className="absolute right-2 top-2 flex h-11 w-11 items-center justify-center rounded-md text-muted transition-colors hover:bg-white/5 hover:text-neutral-100 lg:hidden"
        >
          <IconClose size={18} />
        </button>
        {sidebar}
      </aside>

      {/* min-w-0 обязателен: без него флекс-элемент не сжимается уже своего
          содержимого, и широкая таблица или длинная строка растягивают всю
          страницу — появляется горизонтальная прокрутка. */}
      {/* key на pathname: короткое появление содержимого при каждом переходе.
          Смена экрана иначе читается как мигание — особенно там, где шапка и
          меню на месте, а меняется только середина. */}
      <main key={location.pathname} className="aurum-rise min-w-0 flex-1 p-3 sm:p-4 lg:p-6">
        <Outlet />
      </main>

      {/* Ассистент живёт в раскладке, а не на странице: кнопка должна быть
          видна на всех экранах панели. Сам компонент прячется, если у роли
          нет права ai.chat. */}
      <AiAssistant />
    </div>
  );
}
