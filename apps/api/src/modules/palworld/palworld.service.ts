import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import type {
  PalworldBanDto,
  PalworldCommandResultDto,
  PalworldPlayersResponse,
  PalworldQuickActionDto,
  PalworldServerStateDto,
} from '@aurum/shared';
import { PrismaService } from '../../prisma/prisma.service';
import { PalworldApiService } from './palworld-api.service';
import {
  MAX_SHUTDOWN_WAIT_SECONDS,
  PALWORLD_ACTIONS,
  type PalworldActionDefinition,
} from './palworld-actions.config';
import {
  isValidUserId,
  parseInfo,
  parseMetrics,
  parsePlayers,
  sanitizeMessage,
} from './palworld-parsers';

@Injectable()
export class PalworldService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly api: PalworldApiService,
  ) {}

  // ---------- Игроки ----------

  /**
   * Список игроков. Максимум берём из метрик: в ответе /players его нет,
   * а «12 / 32» полезнее, чем просто «12».
   */
  async getPlayers(serverId: string): Promise<PalworldPlayersResponse> {
    const players = parsePlayers(await this.api.get(serverId, '/players'));
    // Метрики — украшение: если их не дали, список игроков всё равно нужен.
    const max = await this.api
      .get(serverId, '/metrics')
      .then((body) => parseMetrics(body).maxPlayers)
      .catch(() => null);

    return { players, online: players.length, max };
  }

  /** Состояние сервера: имя и версия из /info, показатели из /metrics. */
  async getServerState(serverId: string): Promise<PalworldServerStateDto> {
    if (!(await this.api.isConfigured(serverId))) {
      return { available: false, reason: 'REST API Palworld не настроен для этого сервера' };
    }
    try {
      const [info, metrics] = await Promise.all([
        this.api.get(serverId, '/info').then(parseInfo),
        this.api.get(serverId, '/metrics').then(parseMetrics),
      ]);
      return {
        available: true,
        serverName: info.serverName ?? undefined,
        version: info.version ?? undefined,
        description: info.description ?? undefined,
        fps: metrics.fps,
        frameTimeMs: metrics.frameTimeMs,
        onlineCount: metrics.onlineCount,
        maxPlayers: metrics.maxPlayers,
        uptimeSeconds: metrics.uptimeSeconds,
      };
    } catch (e) {
      // Сервер может быть просто выключен — это не ошибка панели.
      return { available: false, reason: (e as Error).message };
    }
  }

  // ---------- Кик и бан ----------

  /**
   * ПРО ИМЯ ПОЛЯ В ЗАПРОСЕ. Сервер ждёт `userid` строчными буквами —
   * так во всех примерах официальной документации и у хостеров. Один из
   * community-вариантов OpenAPI-спецификации указывает `userId`, и это
   * расхождение опасно: при неверном имени поля сервер отвечает успехом,
   * но НИЧЕГО не делает — бан молча не применяется. Если однажды баны
   * перестанут срабатывать, проверять надо в первую очередь это.
   */
  private assertUserId(userId: string): string {
    if (!isValidUserId(userId)) {
      throw new BadRequestException(
        'Некорректный идентификатор игрока. Нужен userId с игрового сервера ' +
          '(вида steam_0110000...), а не имя персонажа.',
      );
    }
    return userId;
  }

  async kick(serverId: string, userId: string, reason: string): Promise<PalworldCommandResultDto> {
    await this.api.post(serverId, '/kick', {
      userid: this.assertUserId(userId),
      message: sanitizeMessage(reason || 'Кик администрацией'),
    });
    return { ok: true, message: 'Игрок кикнут' };
  }

  /**
   * Бан: сперва команда серверу, потом запись в свою таблицу.
   *
   * Порядок важен. Если сервер команду не принял, записи не появится, и
   * панель не будет показывать бан, которого на сервере нет.
   */
  async ban(
    serverId: string,
    userId: string,
    playerName: string,
    reason: string,
    actorId: string,
  ): Promise<PalworldBanDto> {
    const cleanReason = sanitizeMessage(reason || 'Без указания причины');
    await this.api.post(serverId, '/ban', {
      userid: this.assertUserId(userId),
      message: cleanReason,
    });

    const ban = await this.prisma.palworldBan.create({
      data: {
        serverId,
        userId,
        playerName: playerName.slice(0, 64) || userId,
        reason: cleanReason,
        createdById: actorId,
      },
    });
    return this.toBanDto(ban, null, null);
  }

  async listBans(serverId: string, search?: string): Promise<PalworldBanDto[]> {
    const bans = await this.prisma.palworldBan.findMany({
      where: {
        serverId,
        ...(search
          ? {
              OR: [
                { playerName: { contains: search, mode: 'insensitive' as const } },
                { userId: { contains: search, mode: 'insensitive' as const } },
              ],
            }
          : {}),
      },
      orderBy: { createdAt: 'desc' },
      take: 200,
    });

    const names = await this.actorNames(bans.flatMap((b) => [b.createdById, b.pardonedById]));
    return bans.map((ban) =>
      this.toBanDto(
        ban,
        ban.createdById ? (names.get(ban.createdById) ?? null) : null,
        ban.pardonedById ? (names.get(ban.pardonedById) ?? null) : null,
      ),
    );
  }

  /** Снятие бана: сначала сервер, потом отметка в таблице — как и при бане. */
  async pardon(serverId: string, banId: string, actorId: string): Promise<PalworldBanDto> {
    const ban = await this.prisma.palworldBan.findFirst({ where: { id: banId, serverId } });
    if (!ban) throw new NotFoundException('Бан не найден');
    if (ban.pardonedAt) throw new BadRequestException('Этот бан уже снят');

    await this.api.post(serverId, '/unban', { userid: ban.userId });

    const updated = await this.prisma.palworldBan.update({
      where: { id: ban.id },
      data: { pardonedAt: new Date(), pardonedById: actorId },
    });
    const names = await this.actorNames([updated.createdById, updated.pardonedById]);
    return this.toBanDto(
      updated,
      updated.createdById ? (names.get(updated.createdById) ?? null) : null,
      updated.pardonedById ? (names.get(updated.pardonedById) ?? null) : null,
    );
  }

  // ---------- Быстрые действия ----------

  /** Каталог для интерфейса: без путей REST — они панели не нужны. */
  listActions(): PalworldQuickActionDto[] {
    return PALWORLD_ACTIONS.map(({ id, label, description, permission, args, destructive }) => ({
      id,
      label,
      description,
      permission,
      args,
      destructive,
    }));
  }

  findAction(id: string): PalworldActionDefinition {
    const action = PALWORLD_ACTIONS.find((a) => a.id === id);
    if (!action) throw new NotFoundException('Действие не найдено');
    return action;
  }

  async runAction(
    serverId: string,
    actionId: string,
    args: Record<string, string>,
  ): Promise<PalworldCommandResultDto> {
    const action = this.findAction(actionId);
    await this.api.post(serverId, action.path, buildActionBody(action, args));
    return { ok: true, message: action.successMessage };
  }

  // ---------- Вспомогательное ----------

  private async actorNames(ids: (string | null)[]): Promise<Map<string, string>> {
    const unique = [...new Set(ids.filter((id): id is string => !!id))];
    if (unique.length === 0) return new Map();
    const users = await this.prisma.user.findMany({
      where: { id: { in: unique } },
      select: { id: true, displayName: true, nickname: true },
    });
    return new Map(users.map((u) => [u.id, u.nickname ?? u.displayName]));
  }

  private toBanDto(
    ban: {
      id: string;
      serverId: string;
      playerName: string;
      userId: string;
      reason: string;
      createdAt: Date;
      pardonedAt: Date | null;
    },
    createdByName: string | null,
    pardonedByName: string | null,
  ): PalworldBanDto {
    return {
      id: ban.id,
      serverId: ban.serverId,
      playerName: ban.playerName,
      userId: ban.userId,
      reason: ban.reason,
      createdAt: ban.createdAt.toISOString(),
      createdByName,
      pardonedAt: ban.pardonedAt?.toISOString() ?? null,
      pardonedByName,
      active: ban.pardonedAt === null,
    };
  }
}

/**
 * Тело запроса действия.
 *
 * Собирается по объявленным аргументам, а не из того, что прислал клиент:
 * лишние поля в тело запроса к игровому серверу попасть не должны.
 */
export function buildActionBody(
  action: PalworldActionDefinition,
  args: Record<string, string>,
): Record<string, string | number> | undefined {
  const body: Record<string, string | number> = {};

  for (const arg of action.args) {
    const raw = (args[arg.name] ?? '').trim();
    if (!raw) {
      if (arg.required) throw new BadRequestException(`Не заполнено поле «${arg.label}»`);
      continue;
    }

    if (arg.kind === 'number') {
      const value = Number(raw);
      if (!Number.isInteger(value) || value < 0 || value > MAX_SHUTDOWN_WAIT_SECONDS) {
        throw new BadRequestException(
          `Поле «${arg.label}»: нужно целое число секунд от 0 до ${MAX_SHUTDOWN_WAIT_SECONDS}`,
        );
      }
      body[arg.name] = value;
      continue;
    }

    body[arg.name] = sanitizeMessage(raw);
  }

  // Действия без аргументов (сохранение мира) шлём вовсе без тела:
  // пустой объект некоторые версии сервера воспринимают хуже.
  return action.args.length === 0 ? undefined : body;
}
