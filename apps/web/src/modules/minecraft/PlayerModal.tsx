import { useState } from 'react';
import { Button, ErrorText, Input, Label, Select, Textarea } from '../../components/ui';
import { Modal } from '../../components/Modal';
import { useT } from '../../i18n';

// Modal переехал в components/Modal.tsx — им пользуются и другие модули.
// Реэкспорт, чтобы не править импорты во всех местах модуля Minecraft.
export { Modal };

const BAN_DURATIONS = [
  { value: '', labelKey: 'mc.punish.forever' },
  { value: '3600', labelKey: 'mc.punish.1h' },
  { value: '86400', labelKey: 'mc.punish.1d' },
  { value: '604800', labelKey: 'mc.punish.7d' },
  { value: '2592000', labelKey: 'mc.punish.30d' },
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
  const t = useT();
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
    <Modal title={t(kind === 'kick' ? 'mc.punish.kickTitle' : 'mc.punish.banTitle', { name: player })} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>{t('mc.punish.reason')}</Label>
          <Textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={t(kind === 'kick' ? 'mc.punish.kickPlaceholder' : 'mc.punish.banPlaceholder')}
            autoFocus
          />
        </div>
        {kind === 'ban' && (
          <div>
            <Label>{t('mc.th.until')}</Label>
            <Select value={duration} onChange={setDuration} options={BAN_DURATIONS.map((d) => ({ value: d.value, label: t(d.labelKey) }))} className="w-full" />
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
            {t(kind === 'kick' ? 'mc.punish.kick' : 'mc.punish.ban')}
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
  const t = useT();
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
            {t('common.add')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
