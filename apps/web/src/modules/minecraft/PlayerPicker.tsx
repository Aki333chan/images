import { useEffect, useRef, useState } from 'react';
import type { MinecraftPlayersResponse } from '@aurum/shared';
import { api } from '../../lib/api';
import { Input } from '../../components/ui';

/**
 * Ники тех, кто сейчас в сети, — для подсказок при вводе.
 *
 * Ошибку намеренно проглатываем: подсказки это удобство, а не функция.
 * Если список получить не удалось, поле остаётся обычным вводом, и
 * действие по-прежнему выполнимо.
 */
export function useOnlinePlayers(
  serverId: string,
  enabled: boolean,
  // Модуль сервера: Paper, Forge и NeoForge отвечают на одинаковый по форме
  // роут, но каждый на своём префиксе.
  moduleId: string = 'minecraft',
): string[] {
  const [players, setPlayers] = useState<string[]>([]);

  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    api<MinecraftPlayersResponse>(`/api/modules/${moduleId}/servers/${serverId}/players`)
      .then((r) => {
        if (!stopped) setPlayers(r.players.map((p) => p.name));
      })
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [serverId, enabled, moduleId]);

  return players;
}

/**
 * Поле для ника игрока с подсказками из тех, кто сейчас в сети.
 *
 * Намеренно НЕ закрытый выпадающий список, а поле с автодополнением:
 *
 *  - список онлайна берётся с игрового сервера и может не получиться —
 *    RCON молчит, сервер перезапускается. Закрытый список в этот момент
 *    заблокировал бы действие целиком, а подсказки просто не появятся;
 *  - ник иногда нужно ввести руками: например, чтобы забанить того, кто
 *    только что вышел.
 *
 * Поэтому набранное значение всегда принимается как есть, а список рядом
 * лишь избавляет от необходимости печатать ник целиком и от опечаток.
 */
export function PlayerPicker({
  value,
  onChange,
  players,
  placeholder,
  autoFocus,
  className,
}: {
  value: string;
  onChange: (value: string) => void;
  /** Ники онлайна; пустой список — подсказывать нечем, остаётся обычный ввод. */
  players: string[];
  placeholder?: string;
  autoFocus?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  // Клик мимо закрывает список. Именно mousedown, а не click: иначе выбор
  // мышью успевает потерять фокус раньше, чем сработает выбор пункта.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!boxRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  // Внутри модалки со своей прокруткой список подсказок обрезается нижним
  // краем окна. block:'nearest' прокручивает ближайшего прокручиваемого
  // предка ровно настолько, чтобы список стало видно, и не трогает
  // страницу, когда обрезки нет.
  useEffect(() => {
    if (open) listRef.current?.scrollIntoView({ block: 'nearest' });
  }, [open, value]);

  const query = value.trim().toLowerCase();
  // Совпадение по началу ника — привычный порядок; если ничего не совпало,
  // показываем всех, чтобы список не «схлопывался» на опечатке.
  const byPrefix = players.filter((n) => n.toLowerCase().startsWith(query));
  const matches = (query && byPrefix.length > 0 ? byPrefix : query ? [] : players).slice(0, 20);
  const exact = players.some((n) => n.toLowerCase() === query);

  return (
    <div ref={boxRef} className={`relative ${className ?? ''}`}>
      <Input
        value={value}
        onChange={(e) => {
          onChange(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false);
          // Enter при единственном совпадении дописывает его целиком —
          // так ник вводится тремя буквами.
          if (e.key === 'Enter' && matches.length === 1 && !exact) {
            e.preventDefault();
            onChange(matches[0]!);
            setOpen(false);
          }
        }}
        placeholder={placeholder}
        autoFocus={autoFocus}
        autoComplete="off"
        spellCheck={false}
      />

      {/* Выпадающий список — только когда есть что выбрать. Когда введён
          ровно чей-то ник, подсказывать нечего. */}
      {open && !exact && matches.length > 0 && (
        <div
          ref={listRef}
          className="absolute left-0 right-0 z-20 mt-1 max-h-52 overflow-y-auto rounded-md border border-border bg-card p-1 shadow-lg"
        >
          {matches.map((name) => (
            <button
              key={name}
              type="button"
              className="block w-full rounded px-2 py-2.5 text-left text-sm hover:bg-white/10 sm:py-1.5"
              onClick={() => {
                onChange(name);
                setOpen(false);
              }}
            >
              {name}
            </button>
          ))}
        </div>
      )}

      {/* «Таких в сети нет» — обычной строкой в потоке, а НЕ всплывающей
          панелью. Всплывающая накрывала кнопку под полем, и действие с
          ником вручную становилось невозможно нажать. Это не ошибка:
          ник принимается как есть, строка лишь помогает заметить опечатку. */}
      {!exact && query.length > 0 && players.length > 0 && matches.length === 0 && (
        <p className="mt-1 text-xs text-muted">
          Среди тех, кто в сети, такого ника нет — команда всё равно уйдёт с
          тем, что введено.
        </p>
      )}
    </div>
  );
}
