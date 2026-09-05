import { LOCALES, LOCALE_LABELS, isLocale, type Locale } from '@aurum/shared';
import { useI18n } from '../i18n';
import { cn } from '../lib/cn';

/**
 * Выбор языка панели.
 *
 * Два варианта одного и того же: компактный — на экранах до входа, где
 * места мало и человеку нужно просто попасть на понятный ему язык; полный —
 * в настройках, где есть место объяснить, что переключается, а что нет.
 *
 * «Как в системе» — не то же самое, что «русский». Это отдельный пункт, и
 * выбравший его получает язык браузера, меняющийся вместе с ним. Свести их
 * в один список без этого пункта значило бы отнять возможность вернуться к
 * автоматическому выбору, случайно нажав не на тот язык.
 */
export function LanguagePicker({
  compact,
  onChange,
  className,
}: {
  compact?: boolean;
  /** Зовётся после выбора — например, чтобы сохранить его в профиле. */
  onChange?: (locale: Locale | null) => void;
  className?: string;
}) {
  const { locale, setLocale, manual, t } = useI18n();

  function pick(value: string) {
    const next = isLocale(value) ? value : null;
    setLocale(next);
    onChange?.(next);
  }

  if (compact) {
    return (
      <select
        // Родной select, а не наш Select: на экране входа это не часть формы,
        // а служебный переключатель, и он не должен выглядеть полем ввода
        // наравне с email и паролем.
        aria-label={t('language.title')}
        value={manual ? locale : ''}
        onChange={(e) => pick(e.target.value)}
        className={cn(
          'rounded border border-border bg-transparent px-2 py-1 text-xs text-muted',
          'focus:border-primary focus:outline-none',
          className,
        )}
      >
        <option value="">{t('language.system')}</option>
        {LOCALES.map((code) => (
          <option key={code} value={code}>
            {LOCALE_LABELS[code]}
          </option>
        ))}
      </select>
    );
  }

  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex flex-wrap gap-2">
        <LanguageButton active={!manual} onClick={() => pick('')} label={t('language.system')} />
        {LOCALES.map((code) => (
          <LanguageButton
            key={code}
            active={manual && locale === code}
            onClick={() => pick(code)}
            label={LOCALE_LABELS[code]}
          />
        ))}
      </div>
      {!manual && <p className="text-xs text-muted">{t('language.systemHint')}</p>}
    </div>
  );
}

function LanguageButton({
  active,
  label,
  onClick,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'min-h-11 rounded-md border px-3 text-sm transition-colors',
        active
          ? 'border-primary bg-primary/15 text-primary-200'
          : 'border-border text-muted hover:bg-white/5',
      )}
    >
      {label}
    </button>
  );
}
