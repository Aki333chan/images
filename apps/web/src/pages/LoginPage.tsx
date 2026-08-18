import { useState, type FormEvent } from 'react';
import type { LoginResponse } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button, Card, ErrorText, Input, Label } from '../components/ui';

export function LoginPage() {
  const { loginDone } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [twoFactorToken, setTwoFactorToken] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = twoFactorToken
        ? await api<LoginResponse>('/api/auth/2fa', {
            method: 'POST',
            body: JSON.stringify({ twoFactorToken, code }),
          })
        : await api<LoginResponse>('/api/auth/login', {
            method: 'POST',
            body: JSON.stringify({ email, password }),
          });
      if (res.twoFactorRequired && res.twoFactorToken) {
        setTwoFactorToken(res.twoFactorToken);
      } else if (res.accessToken && res.me) {
        loginDone(res.accessToken, res.me);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    // p-4: без отступа карточка на 375 px прижималась к обоим краям экрана
    // вплотную — max-w-sm (384 px) шире самого экрана.
    <div className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <h1 className="mb-4 text-center text-xl font-bold text-primary">Aurum Panel</h1>
        <form onSubmit={submit} className="space-y-3">
          {!twoFactorToken ? (
            <>
              <div>
                <Label>Email</Label>
                <Input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="username"
                  required
                />
              </div>
              <div>
                <Label>Пароль</Label>
                <Input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                />
              </div>
            </>
          ) : (
            <div>
              <Label>Код из приложения-аутентификатора</Label>
              <Input
                value={code}
                onChange={(e) => setCode(e.target.value)}
                inputMode="numeric"
                maxLength={6}
                autoFocus
                required
              />
            </div>
          )}
          <ErrorText>{error}</ErrorText>
          <Button className="w-full" disabled={busy}>
            {twoFactorToken ? 'Подтвердить' : 'Войти'}
          </Button>
          {twoFactorToken && (
            <Button
              type="button"
              variant="ghost"
              className="w-full"
              onClick={() => {
                setTwoFactorToken(null);
                setCode('');
              }}
            >
              Назад
            </Button>
          )}
        </form>
      </Card>
    </div>
  );
}
