import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { io, type Socket } from 'socket.io-client';
import { WS_EVENTS, type MeResponse, type ModulesResponse } from '@aurum/shared';
import { api, getAccessToken, setAccessToken, setSessionExpiredHandler, tryRefresh } from './api';

interface AuthState {
  me: MeResponse | null;
  modules: ModulesResponse | null;
  loading: boolean;
  ticketsBadge: number;
  /** Непрочитанные личные сообщения — живой счётчик в навигации. */
  messagesBadge: number;
  /** Инкрементируется на каждое messages.updated: экран переписки слушает его. */
  messagesVersion: number;
  hasPermission: (key: string) => boolean;
  canSeeServer: (serverId: string) => boolean;
  loginDone: (accessToken: string, me: MeResponse) => void;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  /** Счётчик, инкрементируется на каждом tickets.updated — страницы подписываются на него. */
  ticketsVersion: number;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<MeResponse | null>(null);
  const [modules, setModules] = useState<ModulesResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [ticketsBadge, setTicketsBadge] = useState(0);
  const [messagesBadge, setMessagesBadge] = useState(0);
  const [messagesVersion, setMessagesVersion] = useState(0);
  const [ticketsVersion, setTicketsVersion] = useState(0);
  const socketRef = useRef<Socket | null>(null);

  const refreshMe = useCallback(async () => {
    const next = await api<MeResponse>('/api/auth/me');
    setMe(next);
  }, []);

  const refreshBadge = useCallback(async (permissions: string[]) => {
    if (!permissions.includes('tickets.view')) {
      setTicketsBadge(0);
      return;
    }
    const { open } = await api<{ open: number }>('/api/tickets/badge');
    setTicketsBadge(open);
  }, []);

  // Восстановление сессии при загрузке страницы.
  useEffect(() => {
    (async () => {
      try {
        if (await tryRefresh()) {
          const meRes = await api<MeResponse>('/api/auth/me');
          setMe(meRes);
        }
      } finally {
        setLoading(false);
      }
    })();
    setSessionExpiredHandler(() => setMe(null));
  }, []);

  // Догружаем манифесты модулей и бейдж после логина.
  useEffect(() => {
    if (!me) return;
    void api<ModulesResponse>('/api/modules').then(setModules);
    void refreshBadge(me.permissions);
  }, [me?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // WebSocket: живое обновление прав и бейджа тикетов.
  useEffect(() => {
    if (!me) {
      socketRef.current?.disconnect();
      socketRef.current = null;
      return;
    }
    if (socketRef.current) return;
    // path (не namespace) — эндпоинт socket.io на том же origin, что и фронт;
    // прокси на /ws настроен в vite.config.ts и в nginx.
    const socket = io({ path: '/ws', auth: { token: getAccessToken() }, transports: ['websocket'] });
    socketRef.current = socket;

    socket.on(WS_EVENTS.PERMISSIONS_UPDATED, () => {
      // Права изменились — перезапрашиваем /auth/me; меню/вкладки перерисуются сами.
      void refreshMe().catch(() => setMe(null));
    });
    socket.on(WS_EVENTS.TICKETS_UPDATED, () => {
      setTicketsVersion((v) => v + 1);
    });
    socket.on(WS_EVENTS.MESSAGES_UPDATED, () => {
      setMessagesVersion((v) => v + 1);
    });
    return () => {
      socket.disconnect();
      socketRef.current = null;
    };
  }, [me?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Пересчёт бейджа при каждом событии тикетов или смене прав.
  useEffect(() => {
    if (me) void refreshBadge(me.permissions);
  }, [ticketsVersion, me, refreshBadge]);

  // Счётчик непрочитанных сообщений. Прав для него не нужно: переписка
  // доступна всем ролям, каждому — только своя.
  useEffect(() => {
    if (!me) {
      setMessagesBadge(0);
      return;
    }
    // Пока не пройден онбординг, у человека нет ника и переписки быть не может.
    if (me.user.mustChangePassword) return;
    void api<{ unread: number }>('/api/messages/unread')
      .then((r) => setMessagesBadge(r.unread))
      .catch(() => undefined);
  }, [messagesVersion, me]);

  const value = useMemo<AuthState>(
    () => ({
      me,
      modules,
      loading,
      ticketsBadge,
      ticketsVersion,
      messagesBadge,
      messagesVersion,
      hasPermission: (key) => !!me?.permissions.includes(key),
      canSeeServer: (serverId) =>
        !!me && (me.allowedServerIds === null || me.allowedServerIds.includes(serverId)),
      loginDone: (token, meRes) => {
        setAccessToken(token);
        setMe(meRes);
      },
      logout: async () => {
        try {
          await api('/api/auth/logout', { method: 'POST' });
        } finally {
          setAccessToken(null);
          setMe(null);
        }
      },
      refreshMe,
    }),
    [me, modules, loading, ticketsBadge, ticketsVersion, messagesBadge, messagesVersion, refreshMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth вне AuthProvider');
  return ctx;
}
