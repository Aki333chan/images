import { useState } from 'react';
import { Button, Input, Label } from '../../components/ui';
import { useI18n } from '../../i18n';

export function CompanionSetup({ serverId }: { serverId: string }) {
  const { t } = useI18n();
  const [message, setMessage] = useState('');
  const line = `server-id = ${serverId}`;
  async function copy() {
    try {
      await navigator.clipboard.writeText(line);
      setMessage(t('sdtd.setup.copied'));
    } catch {
      setMessage(t('sdtd.setup.copyFailed'));
    }
  }
  return (
    <div className="space-y-2 rounded-lg border border-border p-3">
      <Label>{t('sdtd.setup.id')}</Label>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          aria-label={t('sdtd.setup.id')}
          value={line}
          readOnly
          onFocus={(event) => event.target.select()}
          className="min-w-0 flex-1 font-mono text-xs"
        />
        <Button variant="outline" onClick={() => void copy()}>
          {t('common.copy')}
        </Button>
      </div>
      <p className="text-xs text-muted">{t('sdtd.setup.idHint')}</p>
      <p className="text-xs text-muted">{t('sdtd.setup.networkHint')}</p>
      <p className="text-xs text-muted">{t('sdtd.setup.statusHint')}</p>
      {message && (
        <p role="status" className="text-xs text-muted">
          {message}
        </p>
      )}
    </div>
  );
}
