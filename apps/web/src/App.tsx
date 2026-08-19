import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './lib/auth';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { ServersPage } from './pages/ServersPage';
import { ServerDetailPage } from './pages/ServerDetailPage';
import { TicketsPage } from './pages/TicketsPage';
import { AccessControlPage } from './pages/AccessControlPage';
import { AuditPage } from './pages/AuditPage';
import { MarketPage } from './pages/MarketPage';
import { SecurityPage } from './pages/SecurityPage';
import { SettingsPage } from './pages/SettingsPage';
import { MessagesPage } from './pages/MessagesPage';
import { OnboardingPage } from './pages/OnboardingPage';
import { Spinner } from './components/ui';

/** Роут, требующий права; при live-потере права редиректит на /servers. */
function Guarded({ permission, children }: { permission: string; children: JSX.Element }) {
  const { hasPermission } = useAuth();
  if (!hasPermission(permission)) return <Navigate to="/servers" replace />;
  return children;
}

function Shell() {
  const { me, loading } = useAuth();
  if (loading) return <Spinner />;
  if (!me) return <LoginPage />;

  // Вход был по одноразовому паролю: до онбординга остальная панель закрыта.
  // Отдельным экраном без Layout — навигации здесь быть не должно, иначе
  // человек начнёт ходить по разделам с временным паролём.
  if (me.user.mustChangePassword) return <OnboardingPage />;

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Navigate to="/servers" replace />} />
        <Route
          path="/servers"
          element={
            <Guarded permission="servers.view">
              <ServersPage />
            </Guarded>
          }
        />
        <Route
          path="/servers/:serverId"
          element={
            <Guarded permission="servers.view">
              <ServerDetailPage />
            </Guarded>
          }
        />
        <Route
          path="/tickets"
          element={
            <Guarded permission="tickets.view">
              <TicketsPage />
            </Guarded>
          }
        />
        <Route
          path="/access"
          element={
            <Guarded permission="users.manage">
              <AccessControlPage />
            </Guarded>
          }
        />
        <Route
          path="/audit"
          element={
            <Guarded permission="audit.view">
              <AuditPage />
            </Guarded>
          }
        />
        <Route
          path="/market"
          element={
            <Guarded permission="minecraft.plugins.install">
              <MarketPage />
            </Guarded>
          }
        />
        <Route path="/messages" element={<MessagesPage />} />
        <Route path="/security" element={<SecurityPage />} />
        {/* Настройки доступны всем: там же лежат личные — свой ник и пароль.
            Блоки для ГМ (правила аккаунтов, почта, ассистент) внутри страницы
            показываются по праву users.manage. */}
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/servers" replace />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Shell />
      </BrowserRouter>
    </AuthProvider>
  );
}
