import { useCallback, useEffect, useState } from 'react';
import type { MinecraftJailedPlayerDto, MinecraftJailsDto } from '@aurum/shared';
import { api } from '../../lib/api';
import { Button, ErrorText, Input, Label, Spinner } from '../../components/ui';
import { Modal } from '../../components/Modal';
import { PlayerPicker, useOnlinePlayers } from './PlayerPicker';
import { useI18n } from '../../i18n';

type Translate = ReturnType<typeof useI18n>['t'];

/**
 * Готовые сроки. Свой всё равно можно вписать руками — это лишь то, что
 * выбирают чаще всего, чтобы не печатать «30m» каждый раз.
 *
 * Первым стоит «до отмены»: это самый частый случай для тяжёлых нарушений,
 * а срок обычно назначают уже осознанно.
 */
const PRESETS = ['30m', '1h', '3h', '12h', '1d', '7d'];

/**
 * Тюрьмы EssentialsX: посадить и выпустить.
 *
 * Почему это не обычная быстрая команда с подстановками. Список тюрем задаёт
 * админ прямо в игре, и панель его не знает, пока не спросит сервер. А сама
 * команда togglejail ведёт себя по-разному в зависимости от того, сидит ли
 * игрок уже и в какой именно тюрьме — правильную последовательность собирает
 * бэкенд, см. JailsService.
 *
 * Список тюрем и сидящих спрашивается ТОЛЬКО при открытии окна: это поход на
 * игровой сервер, и делать его на каждый показ дашборда незачем.
 */
export function JailActions({
  serverId,
  moduleId,
  disabled,
  onResult,
}: {
  serverId: string;
  moduleId: string;
  disabled?: boolean;
  /** Ответ сервера показывает общий блок вывода быстрых действий. */
  onResult: (output: string) => void;
}) {
  const { t } = useI18n();
  const [mode, setMode] = useState<'jail' | 'release' | null>(null);

  return (
    <>
      <Button
        size="sm"
        variant="outline"
        title={t('mc.jail.jailHint')}
        disabled={disabled}
        onClick={() => setMode('jail')}
      >
        {t('mc.jail.jail')}
      </Button>
      <Button
        size="sm"
        variant="outline"
        title={t('mc.jail.releaseHint')}
        disabled={disabled}
        onClick={() => setMode('release')}
      >
        {t('mc.jail.release')}
      </Button>

      {mode && (
        <JailModal
          serverId={serverId}
          moduleId={moduleId}
          mode={mode}
          onClose={() => setMode(null)}
          onResult={(output) => {
            onResult(output);
            setMode(null);
          }}
        />
      )}
    </>
  );
}

function JailModal({
  serverId,
  moduleId,
  mode,
  onClose,
  onResult,
}: {
  serverId: string;
  moduleId: string;
  mode: 'jail' | 'release';
  onClose: () => void;
  onResult: (output: string) => void;
}) {
  const { t } = useI18n();
  const path = `/api/modules/${moduleId}/servers/${serverId}/jails`;

  const [state, setState] = useState<MinecraftJailsDto | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [player, setPlayer] = useState('');
  const [jail, setJail] = useState('');
  const [duration, setDuration] = useState('');

  const onlinePlayers = useOnlinePlayers(serverId, mode === 'jail', moduleId);

  const load = useCallback(async () => {
    setError('');
    try {
      const data = await api<MinecraftJailsDto>(path);
      setState(data);
      // Одна тюрьма — выбирать не из чего, ставим её сразу.
      if (data.jails.length === 1) setJail(data.jails[0]!);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [path]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit(request: () => Promise<{ output: string }>) {
    setBusy(true);
    setError('');
    try {
      const res = await request();
      onResult(res.output || t('mc.jail.done'));
    } catch (e) {
      setError((e as Error).message);
      // Состояние на сервере могло измениться — перечитываем, чтобы список
      // сидящих и подсветка «уже сидит» не остались от прошлого мира.
      void load();
    } finally {
      setBusy(false);
    }
  }

  const title = t(mode === 'jail' ? 'mc.jail.jail' : 'mc.jail.release');

  if (!state) {
    return (
      <Modal title={title} onClose={onClose}>
        {error ? <ErrorText>{error}</ErrorText> : <Spinner />}
      </Modal>
    );
  }

  if (!state.available) {
    return (
      <Modal title={title} onClose={onClose}>
        <p className="text-sm text-muted">{t('mc.jail.unavailable')}</p>
      </Modal>
    );
  }

  return (
    <Modal title={title} onClose={onClose}>
      {mode === 'jail' ? (
        <JailForm
          state={state}
          player={player}
          jail={jail}
          duration={duration}
          onlinePlayers={onlinePlayers}
          busy={busy}
          error={error}
          onPlayer={setPlayer}
          onJail={setJail}
          onDuration={setDuration}
          onCancel={onClose}
          onSubmit={() =>
            void submit(() =>
              api<{ output: string }>(path, {
                method: 'POST',
                body: JSON.stringify({
                  player: player.trim(),
                  jail,
                  // Пустая строка означает «до отмены» — поле просто не шлём.
                  ...(duration.trim() ? { duration: duration.trim() } : {}),
                }),
              }),
            )
          }
        />
      ) : (
        <ReleaseList
          jailed={state.jailed}
          busy={busy}
          error={error}
          onCancel={onClose}
          onRelease={(name) =>
            void submit(() =>
              api<{ output: string }>(`${path}/${encodeURIComponent(name)}`, { method: 'DELETE' }),
            )
          }
        />
      )}
    </Modal>
  );
}

function JailForm({
  state,
  player,
  jail,
  duration,
  onlinePlayers,
  busy,
  error,
  onPlayer,
  onJail,
  onDuration,
  onCancel,
  onSubmit,
}: {
  state: MinecraftJailsDto;
  player: string;
  jail: string;
  duration: string;
  onlinePlayers: string[];
  busy: boolean;
  error: string;
  onPlayer: (value: string) => void;
  onJail: (value: string) => void;
  onDuration: (value: string) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  const { t } = useI18n();

  // Уже сидит? Тогда предупреждаем заранее, что будет перевод, а не посадка:
  // EssentialsX переводить одной командой отказывается, и панель сделает это
  // выпуском и посадкой заново — игрок увидит скачок.
  const current = state.jailed.find((e) => e.name.toLowerCase() === player.trim().toLowerCase());
  const moving = current && jail && current.jail.toLowerCase() !== jail.toLowerCase();

  return (
    <div className="space-y-3">
      <div>
        <Label>{t('mc.jail.player')}</Label>
        <PlayerPicker
          value={player}
          onChange={onPlayer}
          players={onlinePlayers}
          placeholder={t('mc.jail.playerHint')}
          autoFocus
        />
      </div>

      <div>
        <Label>{t('mc.jail.which')}</Label>
        {state.jails.length === 0 ? (
          <p className="text-sm text-muted">{t('mc.jail.none')}</p>
        ) : (
          <JailPicker jails={state.jails} value={jail} onChange={onJail} />
        )}
      </div>

      <div>
        <Label>{t('mc.jail.duration')}</Label>
        <Input
          value={duration}
          onChange={(e) => onDuration(e.target.value)}
          placeholder={t('mc.jail.durationHint')}
          autoComplete="off"
          spellCheck={false}
        />
        <div className="mt-2 flex flex-wrap gap-1.5">
          {/* Пустое поле — до отмены; отдельной кнопкой это виднее, чем
              подписью под полем, которую не читают. */}
          <Button
            size="sm"
            variant={duration.trim() ? 'ghost' : 'outline'}
            onClick={() => onDuration('')}
          >
            {t('mc.jail.forever')}
          </Button>
          {PRESETS.map((preset) => (
            <Button
              key={preset}
              size="sm"
              variant={duration.trim() === preset ? 'outline' : 'ghost'}
              onClick={() => onDuration(preset)}
            >
              {preset}
            </Button>
          ))}
        </div>
      </div>

      {current && !moving && (
        <p className="text-xs text-amber-300">{t('mc.jail.alreadyHere', { jail: current.jail })}</p>
      )}
      {moving && (
        <p className="text-xs text-amber-300">{t('mc.jail.willMove', { jail: current!.jail })}</p>
      )}
      <ErrorText>{error}</ErrorText>

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:flex-wrap sm:justify-end">
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          {t('common.cancel')}
        </Button>
        <Button onClick={onSubmit} disabled={busy || !player.trim() || !jail}>
          {t('mc.jail.confirm')}
        </Button>
      </div>
    </div>
  );
}

/**
 * Выбор тюрьмы.
 *
 * Не выпадающий список: тюрем на сервере бывает и сотня, и в списке из ста
 * пунктов нужную ищут прокруткой. Поле поиска появляется, только когда их
 * действительно много — ради трёх штук оно было бы лишним шагом.
 */
function JailPicker({
  jails,
  value,
  onChange,
}: {
  jails: string[];
  value: string;
  onChange: (value: string) => void;
}) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const needle = query.trim().toLowerCase();
  const matches = needle ? jails.filter((name) => name.toLowerCase().includes(needle)) : jails;

  return (
    <div className="space-y-2">
      {jails.length > 8 && (
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('mc.jail.search')}
          autoComplete="off"
          spellCheck={false}
        />
      )}
      <div className="max-h-40 overflow-y-auto rounded-md border border-border p-1">
        {matches.length === 0 ? (
          <p className="px-2 py-1.5 text-xs text-muted">{t('mc.jail.noMatch')}</p>
        ) : (
          matches.map((name) => (
            <button
              key={name}
              type="button"
              aria-pressed={name === value}
              className={`block w-full rounded px-2 py-2.5 text-left text-sm sm:py-1.5 ${
                name === value ? 'bg-white/15 font-medium' : 'hover:bg-white/10'
              }`}
              onClick={() => onChange(name)}
            >
              {name}
            </button>
          ))
        )}
      </div>
    </div>
  );
}

/** Кто сидит и кнопка выпуска у каждого. */
function ReleaseList({
  jailed,
  busy,
  error,
  onCancel,
  onRelease,
}: {
  jailed: MinecraftJailedPlayerDto[];
  busy: boolean;
  error: string;
  onCancel: () => void;
  onRelease: (name: string) => void;
}) {
  const { t } = useI18n();

  return (
    <div className="space-y-3">
      {jailed.length === 0 ? (
        <p className="text-sm text-muted">{t('mc.jail.nobody')}</p>
      ) : (
        <div className="max-h-72 space-y-1 overflow-y-auto">
          {jailed.map((entry) => (
            <div
              key={entry.name}
              className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border px-2 py-2"
            >
              {/* flex-1 вместе с min-w-0: без первого блок занимает ширину
                  своего текста и вылезает за край окна, без второго не даёт
                  себя сжать. Нужны оба. */}
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm">
                  {entry.name}
                  {!entry.online && (
                    <span className="ml-2 text-xs text-muted">{t('mc.jail.offline')}</span>
                  )}
                </p>
                <p className="break-words text-xs text-muted">{describe(entry, t)}</p>
              </div>
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => onRelease(entry.name)}
              >
                {t('mc.jail.releaseOne')}
              </Button>
            </div>
          ))}
        </div>
      )}
      <ErrorText>{error}</ErrorText>
      <div className="flex justify-end">
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          {t('common.close')}
        </Button>
      </div>
    </div>
  );
}

/**
 * Строка под ником: где сидит, сколько уже и сколько осталось.
 *
 * «Сидит столько-то» есть только у тех, кого посадила панель: EssentialsX
 * момент посадки не хранит вовсе, и брать его больше неоткуда. У остальных
 * честно остаётся один остаток срока — выдуманное начало было бы хуже, чем
 * его отсутствие.
 */
function describe(entry: MinecraftJailedPlayerDto, t: Translate): string {
  const parts = [t('mc.jail.in', { jail: entry.jail })];

  if (entry.jailedAt !== null) {
    parts.push(t('mc.jail.sitting', { span: span(Date.now() - entry.jailedAt, t) }));
  }

  if (entry.releaseAt === null) parts.push(t('mc.jail.leftUnknown'));
  else if (entry.releaseAt === 0) parts.push(t('mc.jail.untilCancelled'));
  else {
    const left = entry.releaseAt - Date.now();
    parts.push(left > 0 ? t('mc.jail.left', { span: span(left, t) }) : t('mc.jail.leftOver'));
  }

  if (entry.jailedBy) parts.push(t('mc.jail.by', { name: entry.jailedBy }));
  return parts.join(' · ');
}

/**
 * Промежуток одной крупной единицей.
 *
 * Округление ВНИЗ, а не к ближайшему: «сидит 2 ч» при двух с половиной часах
 * — это правда, а «сидит 3 ч» — нет, и на разговоре с игроком такая мелочь
 * стоит дороже, чем выигрыш в точности.
 */
function span(ms: number, t: Translate): string {
  const minutes = Math.floor(ms / 60_000);
  if (minutes >= 2880) return t('mc.jail.spanDays', { count: String(Math.floor(minutes / 1440)) });
  if (minutes >= 60) return t('mc.jail.spanHours', { count: String(Math.floor(minutes / 60)) });
  return t('mc.jail.spanMinutes', { count: String(Math.max(1, minutes)) });
}
