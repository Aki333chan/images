import { useEffect, useState } from 'react';
import type { MeResponse } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button, Card, ErrorText, Input, Label } from '../components/ui';

/**
 * Первый вход по одноразовому паролю.
 *
 * Экран показывается вместо всей панели: пока не задан постоянный пароль и
 * не выбран ник, остальные разделы недоступны. Навигации здесь нет намеренно
 * — уйти отсюда можно только пройдя онбординг либо выйдя из аккаунта.
 */
export function OnboardingPage() {
  const { me, refreshMe, logout } = useAuth();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [nickname, setNickname] = useState('');
  const [nickState, setNickState] = useState<'idle' | 'checking' | 'free' | 'taken'>('idle');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // Проверка ника по мере ввода, но не на каждое нажатие: без задержки
  // запрос уходил бы после каждой буквы.
  useEffect(() => {
    const value = nickname.trim();
    if (value.length < 2) {
      setNickState('idle');
      return;
    }
    setNickState('checking');
    const timer = setTimeout(() => {
      api<{ available: boolean }>(
        `/api/auth/onboarding/nickname-available?nickname=${encodeURIComponent(value)}`,
      )
        .then((r) => setNickState(r.available ? 'free' : 'taken'))
        .catch(() => setNickState('idle'));
    }, 400);
    return () => clearTimeout(timer);
  }, [nickname]);

  const passwordsMatch = newPassword.length > 0 && newPassword === repeat;
  const passwordLongEnough = newPassword.length >= 10;
  const canSubmit =
    currentPassword.length > 0 &&
    passwordLongEnough &&
    passwordsMatch &&
    nickState === 'free' &&
    !busy;

  async function submit() {
    setBusy(true);
    setError('');
    try {
      const next = await api<MeResponse>('/api/auth/onboarding', {
        method: 'POST',
        body: JSON.stringify({ currentPassword, newPassword, nickname: nickname.trim() }),
      });
      // Перечитываем себя: mustChangePassword станет false, и Shell пустит
      // человека в панель без перезагрузки страницы.
      if (next) await refreshMe();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-md space-y-4">
        <div>
          <h1 className="text-lg font-bold">Добро пожаловать в Aurum Panel</h1>
          <p className="mt-1 text-xs text-muted">
            {me?.user.displayName}, вы вошли по временному паролю. Задайте постоянный пароль и
            выберите ник — он будет виден коллегам во внутренней переписке.
          </p>
        </div>

        <div>
          <Label>Временный пароль из письма</Label>
          <Input
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            autoComplete="current-password"
          />
        </div>

        <div>
          <Label>Новый пароль</Label>
          <Input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            autoComplete="new-password"
            placeholder="минимум 10 символов"
          />
          {newPassword.length > 0 && !passwordLongEnough && (
            <ErrorText>Минимум 10 символов</ErrorText>
          )}
        </div>

        <div>
          <Label>Повторите новый пароль</Label>
          <Input
            type="password"
            value={repeat}
            onChange={(e) => setRepeat(e.target.value)}
            autoComplete="new-password"
          />
          {repeat.length > 0 && !passwordsMatch && <ErrorText>Пароли не совпадают</ErrorText>}
        </div>

        <div>
          <Label>Ник в панели</Label>
          <Input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder="Как вас будут звать коллеги"
          />
          <div className="mt-1 text-xs">
            {nickState === 'checking' && <span className="text-muted">проверяем…</span>}
            {nickState === 'free' && <span className="text-emerald-400">ник свободен</span>}
            {nickState === 'taken' && <ErrorText>Этот ник уже занят — выберите другой</ErrorText>}
            {nickState === 'idle' && (
              <span className="text-muted">
                Буквы и цифры, можно пробел, дефис и подчёркивание. Это ник сотрудника панели, к
                нику в игре он отношения не имеет.
              </span>
            )}
          </div>
        </div>

        {error && <ErrorText>{error}</ErrorText>}

        <div className="flex items-center justify-between gap-2">
          <Button onClick={() => void submit()} disabled={!canSubmit}>
            {busy ? 'Сохраняем…' : 'Готово'}
          </Button>
          {/* Ссылка высотой в строку текста пальцем не берётся —
              область нажатия расширена отступами, вид прежний. */}
          <button
            className="-mr-2 flex min-h-11 items-center px-2 text-xs text-muted underline"
            onClick={() => void logout()}
          >
            Выйти
          </button>
        </div>
      </Card>
    </div>
  );
}
