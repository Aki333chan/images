import { BadRequestException } from '@nestjs/common';
import type { EffectivePermissions } from '../rbac/permissions.service';
import type { ServersService } from '../servers/servers.service';
import type { MinecraftService } from '../modules/minecraft/minecraft.service';
import type { CompanionService } from '../modules/minecraft/companion.service';

/**
 * Поиск сервера и игрока по части названия.
 *
 * ЗАЧЕМ. Человек в переписке пишет «на выживании» и «забань Ste_grief», а не
 * «srv_01H8…» и не «Ste_griefer_2019» посимвольно. Требовать точное имя —
 * значит заставлять его лезть в панель и копировать оттуда ровно то, ради
 * чего он и пришёл к ассистенту.
 *
 * ГЛАВНОЕ ПРАВИЛО: угадывать нельзя. Совпадений несколько — это не повод
 * взять первое, а повод переспросить. Отсюда три исхода вместо двух:
 * нашлось одно (работаем), не нашлось (говорим, что есть), нашлось
 * несколько (перечисляем и ждём уточнения). Ошибка здесь не «не нашли
 * игрока», а «забанили не того».
 *
 * Кандидаты уезжают в тексте ошибки: инструмент бросает исключение, модель
 * видит его текстом и передаёт вопрос человеку. Отдельного механизма «задай
 * уточняющий вопрос» не нужно — модель и так умеет спрашивать.
 */

/** Сколько кандидатов показывать. Список из полусотни ников не помогает. */
const MAX_CANDIDATES = 10;

const norm = (value: string): string => value.trim().toLowerCase();

/**
 * Сервер по id, точному имени или его части.
 *
 * Порядок проверок не случаен: id и точное имя должны выигрывать у частичного
 * совпадения. Сервер «Выживание» и сервер «Выживание 2» — оба подойдут под
 * «выживание», и без приоритета точного имени первый стал бы недоступен.
 */
export async function resolveServerId(
  deps: { servers: ServersService },
  permissions: EffectivePermissions,
  value: string,
): Promise<string> {
  const raw = value.trim();
  if (!raw) throw new BadRequestException('Не указан сервер');

  const servers = await deps.servers.listForUser(permissions);
  const byId = servers.find((s) => s.id === raw);
  if (byId) return byId.id;

  const exact = servers.filter((s) => norm(s.name) === norm(raw));
  if (exact.length === 1) return exact[0]!.id;

  const partial = servers.filter((s) => norm(s.name).includes(norm(raw)));
  if (partial.length === 1) return partial[0]!.id;

  if (partial.length > 1) {
    throw new BadRequestException(
      `Под «${raw}» подходит несколько серверов: ${partial
        .map((s) => s.name)
        .join(', ')}. Спроси у собеседника, какой из них имеется в виду.`,
    );
  }

  throw new BadRequestException(
    servers.length === 0
      ? 'У собеседника нет доступа ни к одному серверу.'
      : `Сервера «${raw}» нет. Доступны: ${servers.map((s) => s.name).join(', ')}.`,
  );
}

/**
 * Ник игрока по точному совпадению или части.
 *
 * Ищем сначала среди тех, кто в сети: если человек говорит про игрока в
 * настоящем времени, почти всегда речь о нём. Потом — среди всех, кто когда-
 * либо заходил: именно там живут офлайн-игроки, ради которых и появился
 * поиск по части ника.
 *
 * Точное совпадение выигрывает у частичного по той же причине, что и у
 * серверов: «Alex» и «Alexander» существуют одновременно, и без приоритета
 * первый оказался бы недостижим.
 */
export async function resolvePlayerName(
  deps: { minecraft: MinecraftService; companion: CompanionService },
  serverId: string,
  value: string,
): Promise<string> {
  const raw = value.trim();
  if (!raw) throw new BadRequestException('Не указан игрок');

  const online = await deps.minecraft
    .getPlayers(serverId)
    .then((d) => d.players.map((p) => p.name))
    .catch(() => [] as string[]);

  const pick = fromCandidates(online, raw);
  if (pick.kind === 'one') return pick.value;

  // Историю спрашиваем только когда среди онлайна ответа нет: это поход на
  // игровой сервер, и делать его ради того, кто и так в сети, незачем.
  const known = await deps.companion.getKnownPlayers(serverId, { query: raw, limit: 50 });
  const historical = known.available ? known.players.map((p) => p.name) : [];

  const all = [...new Set([...online, ...historical])];
  const second = fromCandidates(all, raw);
  if (second.kind === 'one') return second.value;

  if (second.kind === 'many') {
    throw new BadRequestException(
      `Под «${raw}» подходит несколько игроков: ${second.values
        .slice(0, MAX_CANDIDATES)
        .join(', ')}${
        second.values.length > MAX_CANDIDATES ? ` и ещё ${second.values.length - MAX_CANDIDATES}` : ''
      }. Спроси у собеседника, кто из них имеется в виду.`,
    );
  }

  throw new BadRequestException(
    known.available
      ? `Игрок с ником, похожим на «${raw}», на этом сервере не найден — ни в сети, ни в истории заходов.`
      : `Игрока «${raw}» нет в сети, а историю заходов посмотреть нечем: нужен companion-плагин.`,
  );
}

type Pick =
  | { kind: 'one'; value: string }
  | { kind: 'many'; values: string[] }
  | { kind: 'none' };

function fromCandidates(names: string[], raw: string): Pick {
  const needle = norm(raw);
  const exact = names.filter((n) => norm(n) === needle);
  if (exact.length === 1) return { kind: 'one', value: exact[0]! };

  const partial = names.filter((n) => norm(n).includes(needle));
  if (partial.length === 1) return { kind: 'one', value: partial[0]! };
  if (partial.length > 1) return { kind: 'many', values: partial };
  return { kind: 'none' };
}
