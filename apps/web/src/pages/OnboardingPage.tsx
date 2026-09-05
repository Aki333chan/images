import { useEffect, useState } from 'react';
import type { MeResponse } from '@aurum/shared';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Button,  ErrorText, Input, Label } from '../components/ui';
import { AuthCard } from '../components/AuthCard';
import { useT } from '../i18n';

/**
 * Вход по одноразовому паролю.
 *
 * Экран показывается вместо всей панели: пока не задан постоянный пароль,
 * остальные разделы недоступны. Навигации здесь нет намеренно — уйти отсюда
 * можно только задав пароль либо выйдя из аккаунта.
 *
 * Ник спрашивается ровно один раз в жизни аккаунта — при самом первом входе.
 * Сюда же попадают после сброса пароля ГМ, но у такого сотрудника ник уже
 * есть: коллеги знают его по нему, и требовать придумать новый бессмысленно.
 */
export function OnboardingPage() {
  const t = useT();
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

  /** Ник уже выбран — значит, это сброс пароля, а не первый вход. */
  const needsNickname = !me?.user.nickname;

  const passwordsMatch = newPassword.length > 0 && newPassword === repeat;
  const passwordLongEnough = newPassword.length >= 10;
  const canSubmit =
    currentPassword.length > 0 &&
    passwordLongEnough &&
    passwordsMatch &&
    (!needsNickname || nickState === 'free') &&
    !busy;

  async function submit() {
    setBusy(true);
    setError('');
    try {
      const next = await api<MeResponse>('/api/auth/onboarding', {
        method: 'POST',
        body: JSON.stringify({
          currentPassword,
          newPassword,
          ...(needsNickname ? { nickname: nickname.trim() } : {}),
        }),
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
    <AuthCard className="max-w-md" title={t(needsNickname ? 'onboarding.title.first' : 'onboarding.title.password')}>
      <div className="space-y-4">
        <div>
          <p className="-mt-1 mb-1 text-xs text-muted">
            {needsNickname ? (
              <>
                {t('onboarding.intro.first')}
              </>
            ) : (
              <>
                {t('onboarding.intro.reset', { nickname: me?.user.nickname ?? '' })}
              </>
            )}
          </p>
        </div>

        <div>
          <Label>{t('onboarding.temporaryPassword')}</Label>
          <Input
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            autoComplete="current-password"
          />
        </div>

        <div>
          <Label>{t('onboarding.newPassword')}</Label>
          <Input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            autoComplete="new-password"
            placeholder={t('onboarding.newPassword.placeholder')}
          />
          {newPassword.length > 0 && !passwordLongEnough && (
            <ErrorText>{t('onboarding.newPassword.tooShort')}</ErrorText>
          )}
        </div>

        <div>
          <Label>{t('onboarding.repeatPassword')}</Label>
          <Input
            type="password"
            value={repeat}
            onChange={(e) => setRepeat(e.target.value)}
            autoComplete="new-password"
          />
          {repeat.length > 0 && !passwordsMatch && <ErrorText>{t('onboarding.repeatPassword.mismatch')}</ErrorText>}
        </div>

        {needsNickname && (
        <div>
          <Label>{t('onboarding.nickname')}</Label>
          <Input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder={t('onboarding.nickname.placeholder')}
          />
          <div className="mt-1 text-xs">
            {nickState === 'checking' && <span className="text-muted">{t('onboarding.nickname.checking')}</span>}
            {nickState === 'free' && <span className="text-emerald-400">{t('onboarding.nickname.free')}</span>}
            {nickState === 'taken' && <ErrorText>{t('onboarding.nickname.taken')}</ErrorText>}
            {nickState === 'idle' && (
              <span className="text-muted">
                {t('onboarding.nickname.hint')}
              </span>
            )}
          </div>
        </div>
        )}

        {error && <ErrorText>{error}</ErrorText>}

        <div className="flex items-center justify-between gap-2">
          <Button onClick={() => void submit()} disabled={!canSubmit}>
            {t(busy ? 'onboarding.saving' : 'onboarding.done')}
          </Button>
          {/* Ссылка высотой в строку текста пальцем не берётся —
              область нажатия расширена отступами, вид прежний. */}
          <button
            className="-mr-2 flex min-h-11 items-center px-2 text-xs text-muted underline"
            onClick={() => void logout()}
          >
            {t('nav.logout')}
          </button>
        </div>
      </div>
    </AuthCard>
  );
}
