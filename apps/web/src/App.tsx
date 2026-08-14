import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './lib/auth';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { ServersPage } from './pages/ServersPage';
import { ServerDetailPage } from './pages/ServerDetailPage';
import { TicketsPage } from './pages/TicketsPage';
import { AccessControlPage } from './pages/AccessControlPage';
import { AuditPage } from './pages/AuditPage';
import { SecurityPage } from './pages/SecurityPage';
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
        <Route path="/security" element={<SecurityPage />} />
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
