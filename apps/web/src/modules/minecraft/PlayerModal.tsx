import { useEffect, useState, type ReactNode } from 'react';
import { Button, ErrorText, Input, Label, Select, Textarea } from '../../components/ui';

/**
 * Модалка: карточка игрока, причина кика и бана, добавление в whitelist.
 *
 * На мобильном раскрывается на весь экран, а не висит окошком по центру:
 * внутри бывает карточка игрока с вкладками и инвентарём, и в окне на
 * 300 px её пришлось бы читать через двойную прокрутку — страницы и окна.
 * Шапка закреплена, чтобы кнопка закрытия оставалась на виду при длинном
 * содержимом. С sm и шире — прежнее окно по центру.
 */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  // Фон под модалкой не прокручиваем — иначе на телефоне при прокрутке
  // внутри окна «уезжает» страница за ним.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex bg-black/60 sm:items-center sm:justify-center sm:p-4"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div
        className="flex w-full flex-col sm:max-h-[85vh] sm:max-w-md"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden border-border bg-card shadow-sm sm:rounded-lg sm:border">
          <div
            className="flex shrink-0 items-center justify-between gap-2 border-b border-border p-3 sm:p-4"
            style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}
          >
            <h3 className="min-w-0 truncate font-semibold">{title}</h3>
            <button
              type="button"
              onClick={onClose}
              aria-label="Закрыть"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted hover:bg-white/5"
            >
              ✕
            </button>
          </div>
          <div
            className="min-h-0 flex-1 overflow-y-auto p-3 sm:p-4"
            style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}
          >
            {children}
          </div>
        </div>
      </div>
    </div>
  );
}

const BAN_DURATIONS = [
  { value: '', label: 'Навсегда' },
  { value: '3600', label: '1 час' },
  { value: '86400', label: '1 день' },
  { value: '604800', label: '7 дней' },
  { value: '2592000', label: '30 дней' },
];

export function PunishModal({
  player,
  kind,
  onClose,
  onSubmit,
}: {
  player: string;
  kind: 'kick' | 'ban';
  onClose: () => void;
  onSubmit: (reason: string, expiresAt: string | null) => Promise<void>;
}) {
  const [reason, setReason] = useState('');
  const [duration, setDuration] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    try {
      const expiresAt = duration
        ? new Date(Date.now() + Number(duration) * 1000).toISOString()
        : null;
      await onSubmit(reason.trim(), expiresAt);
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`${kind === 'kick' ? 'Кик' : 'Бан'} игрока ${player}`} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>Причина</Label>
          <Textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={kind === 'kick' ? 'Нарушение правил чата' : 'Гриферство на спавне'}
            autoFocus
          />
        </div>
        {kind === 'ban' && (
          <div>
            <Label>Срок</Label>
            <Select value={duration} onChange={setDuration} options={BAN_DURATIONS} className="w-full" />
          </div>
        )}
        <ErrorText>{error}</ErrorText>
        {/* На узком экране кнопки в столбик и во всю ширину: так в них
            попадают пальцем, и подтверждающая оказывается сверху. */}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Отмена
          </Button>
          <Button variant="destructive" onClick={() => void submit()} disabled={busy}>
            {kind === 'kick' ? 'Кикнуть' : 'Забанить'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

export function PromptModal({
  title,
  label,
  placeholder,
  onClose,
  onSubmit,
}: {
  title: string;
  label: string;
  placeholder?: string;
  onClose: () => void;
  onSubmit: (value: string) => Promise<void>;
}) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit() {
    if (!value.trim()) return;
    setBusy(true);
    setError('');
    try {
      await onSubmit(value.trim());
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={title} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>{label}</Label>
          <Input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void submit()}
            placeholder={placeholder}
            autoFocus
          />
        </div>
        <ErrorText>{error}</ErrorText>
        {/* На узком экране кнопки в столбик и во всю ширину: так в них
            попадают пальцем, и подтверждающая оказывается сверху. */}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Отмена
          </Button>
          <Button onClick={() => void submit()} disabled={busy || !value.trim()}>
            Добавить
          </Button>
        </div>
      </div>
    </Modal>
  );
}
