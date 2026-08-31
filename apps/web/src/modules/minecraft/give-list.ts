import type { MinecraftGiveItemDto } from '@aurum/shared';

/**
 * Верхняя граница на строку — ровно полный инвентарь одним материалом
 * (36 слотов по 64). Больше выдать всё равно некуда, а опечатка вроде
 * «64000000» заставила бы игровой сервер собирать миллион стаков.
 * То же число проверяет и бэкенд: здесь оно нужно, чтобы человек узнал об
 * ошибке до отправки.
 */
export const MAX_GIVE_COUNT = 36 * 64;

/** Не больше строк за раз — столько же принимает и плагин. */
export const MAX_GIVE_ENTRIES = 45;

export interface ParsedGiveList {
  items: MinecraftGiveItemDto[];
  /** Строки, которые разобрать не удалось, — с объяснением для человека. */
  errors: string[];
}

/**
 * Разбор списка выдачи, набранного руками.
 *
 * Формат нарочно свободный: люди пишут `minecraft:stone 64`, `stone x64` и
 * `stone*64`, и заставлять их помнить единственно верный вид — лишний повод
 * ошибиться там, где догадаться легко. Разделителем считается и перевод
 * строки, и запятая: список часто копируют из чата одной строкой.
 *
 * Существование предмета здесь не проверяется. Какие материалы бывают, знает
 * игровой сервер: перечень зависит от версии и установленных модов, и
 * зашитый в панель список устарел бы к следующему обновлению. Поэтому
 * неизвестный предмет вернётся строкой результата от сервера, а не отказом
 * ещё до отправки.
 */
export function parseGiveList(text: string): ParsedGiveList {
  const items: MinecraftGiveItemDto[] = [];
  const errors: string[] = [];

  for (const raw of text.split(/[\n,;]/)) {
    const line = raw.trim();
    if (!line) continue;

    const match = /^([A-Za-z0-9_.:/-]+)(?:\s*[x*×]?\s*(\d+))?$/.exec(line);
    if (!match || !match[1]) {
      errors.push(`«${line}» — не похоже на предмет. Ожидается «minecraft:stone 64»`);
      continue;
    }

    const count = match[2] === undefined ? 1 : Number(match[2]);
    if (count < 1) {
      errors.push(`«${line}» — количество должно быть больше нуля`);
      continue;
    }
    if (count > MAX_GIVE_COUNT) {
      errors.push(`«${line}» — за раз можно выдать не больше ${MAX_GIVE_COUNT} штук`);
      continue;
    }

    items.push({ id: match[1], count });
  }

  if (items.length > MAX_GIVE_ENTRIES) {
    errors.push(`Строк больше ${MAX_GIVE_ENTRIES} — отправьте список по частям`);
    return { items: [], errors };
  }

  return { items, errors };
}
