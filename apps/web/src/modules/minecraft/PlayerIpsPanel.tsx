import { useEffect, useState } from 'react';
import type { MinecraftPlayerIpsResponse } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, ErrorText, Label, Spinner } from '../../components/ui';
import { useApiText, useI18n } from '../../i18n';

const base = (moduleId: string, serverId: string) => `/api/modules/${moduleId}/servers/${serverId}`;

/**
 * Известные адреса игрока.
 *
 * Показывается не всем: право `minecraft.players.ips` по умолчанию есть у
 * администратора и ГМ, но не у модератора. Адрес — личные данные: по нему
 * видно провайдера, город и то, что два ника принадлежат одному человеку, а
 * для кика, бана и разбора жалоб это не нужно.
 *
 * Данные ведёт плагин авторизации. Без него список пуст, и это ожидаемо:
 * ванильный сервер историю адресов не хранит вовсе.
 *
 * Открывается по кнопке, а не сразу: это не та справка, которую стоит
 * показывать заодно при каждом открытии карточки.
 */
export function PlayerIpsPanel({
  serverId,
  moduleId,
  uuid,
}: {
  serverId: string;
  moduleId: string;
  uuid: string;
}) {
  const { t, formatDate, formatDateTime } = useI18n();
  const apiText = useApiText();
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<MinecraftPlayerIpsResponse | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setError('');
    setData(null);
    api<MinecraftPlayerIpsResponse>(`${base(moduleId, serverId)}/players/${uuid}/ips`)
      .then(setData)
      .catch((e: Error) => setError(e.message));
  }, [open, serverId, moduleId, uuid]);

  return (
    <div className="space-y-2 border-t border-border pt-4">
      <div className="flex items-center justify-between gap-2">
        <Label>{t('mc.ips.title')}</Label>
        {!open && (
          <Button size="sm" variant="outline" onClick={() => setOpen(true)}>
            {t('mc.ips.show')}
          </Button>
        )}
      </div>

      {!open && (
        <p className="text-xs text-muted">
          Личные данные игрока. Открываются по запросу, обращение попадает в журнал действий.
        </p>
      )}

      {open && error && <ErrorText>{error}</ErrorText>}
      {open && !error && !data && <Spinner />}

      {open && data && !data.available && (
        <p className="text-sm text-muted">{apiText(data.reason) || t('mc.ips.unavailable')}</p>
      )}

      {open && data?.available && data.addresses.length === 0 && (
        <p className="text-sm text-muted">
          {t('mc.ips.empty')}
        </p>
      )}

      {open && data?.available && data.addresses.length > 0 && (
        <ul className="divide-y divide-border text-sm">
          {data.addresses.map((record) => (
            <li key={record.ip} className="flex flex-wrap items-baseline gap-x-3 py-1.5">
              <span className="font-mono">{record.ip}</span>
              <span className="text-xs text-muted">
                {t('mc.ips.first', { date: formatDate(record.firstSeen) })}
              </span>
              <span className="ml-auto text-xs text-muted">
                {/* Без секунд: точность до минуты здесь и так избыточна, а
                    «14:54:09» превращает строку в мусор из цифр. */}
                {t('mc.ips.last', { date: formatDateTime(record.lastSeen) })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
