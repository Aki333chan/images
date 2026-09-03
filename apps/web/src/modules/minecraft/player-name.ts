import type { MinecraftKnownPlayerDto } from '@aurum/shared';

/**
 * Справочник «ник → запись исторического списка».
 *
 * Ключ в нижнем регистре: список онлайна приходит по RCON, где регистр ника
 * повторяет то, что напечатал сервер, и сверять строки как есть — значит
 * иногда терять совпадение.
 */
export function knownByName(
  players: MinecraftKnownPlayerDto[],
): Map<string, MinecraftKnownPlayerDto> {
  const map = new Map<string, MinecraftKnownPlayerDto>();
  for (const player of players) map.set(player.name.toLowerCase(), player);
  return map;
}

/** Когда игрок заходил в последний раз — коротко и по-русски. */
export function lastSeenText(iso: string | null): string {
  if (!iso) return '—';
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return '—';

  // Минуты вниз, а не к ближайшей: полминуты назад — это «только что», а
  // округление вверх превратило бы их в «1 мин назад».
  const minutes = Math.floor((Date.now() - at.getTime()) / 60_000);
  if (minutes < 1) return 'только что';
  if (minutes < 60) return `${minutes} мин назад`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} ч назад`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} дн назад`;
  return at.toLocaleDateString('ru-RU');
}
