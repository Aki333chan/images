import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import {
  daysToBloodMoon,
  type SevenDaysActionDto,
  type SevenDaysBanDto,
  type SevenDaysBanUnit,
  type SevenDaysPlayersResponse,
  type SevenDaysStateDto,
  type SevenDaysWhitelistEntryDto,
} from '@aurum/shared';
import { SevenDaysCompanionService } from './sevendays-companion.service';
import { SevenDaysConfigService } from './sevendays-config.service';
import { arg, optionalArg, SevenDaysConsoleService } from './sevendays-console.service';
import {
  SEVENDAYS_ACTIONS,
  type SevenDaysActionDefinition,
} from './sevendays-actions.config';
import {
  parseBans,
  parseGameTime,
  parsePlayers,
  parseVersion,
  parseWhitelist,
} from './sevendays-parsers';

/**
 * Модуль 7 Days to Die.
 *
 * Своей таблицы банов у модуля нет, и миграций он не добавляет — в отличие
 * от Palworld, где список банов пришлось вести панели, потому что API его
 * не отдаёт. Здесь `ban list` отдаёт сам игровой сервер, и дублировать это
 * состояние в своей БД значит рано или поздно с ним разойтись: бан, снятый
 * из игровой консоли, панель бы не заметила.
 *
 * Плата за это — в панели не видно, КТО из персонала выдал бан: игра такого
 * не хранит. Кто нажал кнопку, видно в журнале действий панели, и это
 * честнее, чем показывать автора рядом с баном, выданным мимо панели.
 */
@Injectable()
export class SevenDaysService {
  constructor(
    private readonly console: SevenDaysConsoleService,
    private readonly config: SevenDaysConfigService,
    private readonly companion: SevenDaysCompanionService,
  ) {}

  // ---------- Игроки ----------

  /** Список игроков онлайн: `lp` (она же `listplayers`). */
  async getPlayers(serverId: string): Promise<SevenDaysPlayersResponse> {
    return parsePlayers(await this.console.run(serverId, 'lp'));
  }

  /**
   * Состояние сервера: игровой день, время суток, версия, онлайн.
   *
   * Аналога TPS у 7 Days to Die нет — сервер такого показателя наружу не
   * отдаёт, и выдумывать его панель не будет. Зато отдаёт игровое время, а
   * оно здесь важнее обычного: каждый седьмой день приходит орда.
   *
   * Ответы читаются через tryRun: сервер может быть выключен или ещё
   * грузиться, и это не ошибка панели, а обычное состояние.
   */
  async getState(serverId: string): Promise<SevenDaysStateDto> {
    // Если мод стоит — спрашиваем его: он читает мир напрямую, а не
    // выводит состояние из текста. Разница не косметическая, см. ниже.
    const fromMod = await this.stateFromCompanion(serverId);
    if (fromMod) return fromMod;

    if (!(await this.config.isConfigured(serverId))) {
      return { available: false, reason: 'Консоль 7 Days to Die не настроена для этого сервера' };
    }

    // Первой идёт самая дешёвая команда: если она не прошла, остальные
    // просто потратят по таймауту на каждую.
    const timeRaw = await this.console.tryRun(serverId, 'gettime');
    if (timeRaw === null) {
      return { available: false, reason: 'Консоль сервера не отвечает' };
    }

    const [versionRaw, playersRaw] = await Promise.all([
      this.console.tryRun(serverId, 'version'),
      this.console.tryRun(serverId, 'lp'),
    ]);

    const { day, time } = parseGameTime(timeRaw);
    return {
      available: true,
      source: 'telnet',
      day,
      time,
      // ЭТО ДОГАДКА, а не факт: частота орды настраивается
      // (BloodMoonFrequency), и через консоль она не читается. Панель
      // помечает источник, чтобы дежурный знал, чему верит.
      daysToBloodMoon: day === null ? null : daysToBloodMoon(day),
      version: versionRaw === null ? null : parseVersion(versionRaw),
      onlineCount: playersRaw === null ? null : parsePlayers(playersRaw).online,
    };
  }

  /**
   * Состояние от companion-мода — то же, но от самой игры.
   *
   * Возвращает null, если мода нет или он не ответил: это обычное состояние,
   * а не ошибка, и вызывающий просто идёт в консоль.
   */
  private async stateFromCompanion(serverId: string): Promise<SevenDaysStateDto | null> {
    if (!(await this.config.hasCompanion(serverId))) return null;

    const state = await this.companion.state(serverId);
    if (!state) return null;

    // Частоту орды мод берёт из настроек сервера. Если она известна —
    // считаем по ней, а не по семи из головы.
    const every = state.bloodMoonFrequency && state.bloodMoonFrequency > 0
      ? state.bloodMoonFrequency
      : undefined;

    return {
      available: true,
      source: 'companion',
      day: state.day,
      time: `${String(state.hour).padStart(2, '0')}:${String(state.minute).padStart(2, '0')}`,
      daysToBloodMoon: state.bloodMoonActive ? 0 : daysToBloodMoon(state.day, every),
      bloodMoonActive: state.bloodMoonActive,
      bloodMoonFrequency: state.bloodMoonFrequency,
      version: state.version,
      onlineCount: state.onlinePlayers,
      fps: state.fps,
      zombies: state.zombies,
      maxZombies: state.maxZombies,
      maxPlayers: state.maxPlayers,
    };
  }

  // ---------- Кик и бан ----------

  /** `kick <цель> [причина]`. Цель — ник, id сущности или id платформы. */
  async kick(serverId: string, target: string, reason: string): Promise<{ ok: true }> {
    const parts = ['kick', arg(target, 'Игрок')];
    const why = optionalArg(reason, 'Причина');
    if (why) parts.push(why);

    await this.console.run(serverId, parts.join(' '));
    return { ok: true };
  }

  /**
   * `ban add <цель> <срок> <единица> [причина]`.
   *
   * Единицу срока проверяет и DTO, и эта функция: сервер на чужое значение
   * отвечает отказом уже после отправки, а до отправки об этом знаем и мы.
   */
  async ban(
    serverId: string,
    target: string,
    duration: number,
    unit: SevenDaysBanUnit,
    reason: string,
  ): Promise<{ ok: true }> {
    if (!Number.isInteger(duration) || duration < 1) {
      throw new BadRequestException('Срок бана — целое число больше нуля');
    }

    const parts = ['ban add', arg(target, 'Игрок'), String(duration), unit];
    const why = optionalArg(reason, 'Причина');
    if (why) parts.push(why);

    await this.console.run(serverId, parts.join(' '));
    return { ok: true };
  }

  /** Список банов отдаёт сам сервер: `ban list`. */
  async listBans(serverId: string): Promise<SevenDaysBanDto[]> {
    return parseBans(await this.console.run(serverId, 'ban list'));
  }

  /** `ban remove <цель>` — снятие бана по тому же идентификатору. */
  async pardon(serverId: string, target: string): Promise<{ ok: true }> {
    await this.console.run(serverId, `ban remove ${arg(target, 'Игрок')}`);
    return { ok: true };
  }

  // ---------- Белый список ----------

  /**
   * Белый список в 7 Days to Die есть без модов, и включается он самим
   * фактом непустоты: пока в списке никого, он не действует. Об этом
   * предупреждает интерфейс — иначе добавление первого же игрока внезапно
   * закрыло бы сервер для всех остальных.
   */
  async listWhitelist(serverId: string): Promise<SevenDaysWhitelistEntryDto[]> {
    return parseWhitelist(await this.console.run(serverId, 'whitelist list'));
  }

  async addToWhitelist(serverId: string, target: string): Promise<{ ok: true }> {
    await this.console.run(serverId, `whitelist add ${arg(target, 'Игрок')}`);
    return { ok: true };
  }

  async removeFromWhitelist(serverId: string, target: string): Promise<{ ok: true }> {
    await this.console.run(serverId, `whitelist remove ${arg(target, 'Игрок')}`);
    return { ok: true };
  }

  // ---------- Быстрые действия ----------

  /** Каталог для интерфейса: без шаблонов команд — панели они не нужны. */
  listActions(): SevenDaysActionDto[] {
    return SEVENDAYS_ACTIONS.map(({ id, label, description, permission, args, destructive }) => ({
      id,
      label,
      description,
      permission,
      args,
      destructive,
    }));
  }

  findAction(id: string): SevenDaysActionDefinition {
    const action = SEVENDAYS_ACTIONS.find((a) => a.id === id);
    if (!action) throw new NotFoundException('Действие не найдено');
    return action;
  }

  async runAction(
    serverId: string,
    actionId: string,
    args: Record<string, string>,
  ): Promise<{ ok: true; message: string }> {
    const action = this.findAction(actionId);
    await this.console.run(serverId, buildCommand(action, args));
    return { ok: true, message: action.successMessage };
  }
}

/**
 * Команда действия: подстановка объявленных аргументов в шаблон.
 *
 * Берутся только объявленные аргументы, а не всё, что прислал клиент:
 * лишнее поле в теле запроса не должно превращаться в лишнее слово в
 * команде игрового сервера.
 */
export function buildCommand(
  action: SevenDaysActionDefinition,
  args: Record<string, string>,
): string {
  let command = action.template;

  for (const spec of action.args) {
    const raw = (args[spec.name] ?? '').trim();
    if (!raw) {
      if (spec.required) throw new BadRequestException(`Не заполнено поле «${spec.label}»`);
      command = command.replace(`{${spec.name}}`, '').trimEnd();
      continue;
    }
    command = command.replace(`{${spec.name}}`, arg(raw, spec.label));
  }

  // Незакрытая подстановка означала бы, что шаблон и список аргументов
  // разошлись, — лучше упасть здесь, чем отправить серверу «say {message}».
  if (/\{[a-z]+\}/i.test(command)) {
    throw new BadRequestException(`Действие «${action.label}» настроено неверно`);
  }
  return command;
}
