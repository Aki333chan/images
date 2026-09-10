import { BadRequestException, Injectable, ServiceUnavailableException } from '@nestjs/common';
import type {
  MinecraftJailRequestDto,
  MinecraftJailedPlayerDto,
  MinecraftJailsDto,
} from '@aurum/shared';
import { PrismaService } from '../../prisma/prisma.service';
import { VanillaRconService } from '../minecraft-shared/vanilla-rcon.service';
import type { CompanionJails } from './companion.service';
import { CompanionService } from './companion.service';

/**
 * Срок в формате EssentialsX: «30m», «2h», «7d», «1mo», «1d12h».
 *
 * Разбирает его игровой сервер (DateUtil.parseDateDiff), и повторять этот
 * разбор здесь мы не собираемся: панель бы завела второе мнение о том, что
 * такое «1mo». Проверяем ровно форму — что это цифры с единицами и ничего
 * больше. Пробелов не допускаем нарочно: RCON-команда это одна строка, и
 * лишний пробел превратил бы срок в несколько аргументов.
 */
const DURATION_RE = /^(?:\d{1,4}(?:y|mo|w|d|h|m|s)){1,6}$/i;

/**
 * Сколько офлайн-записей сверяем с сервером за один показ списка.
 *
 * Каждая сверка — чтение файла игрока на игровом сервере. Записей столько,
 * скольких панель посадила и кто сейчас не в сети; десятка хватает с запасом,
 * а на случай, когда кто-то посадил полсотни человек разом, граница не даёт
 * списку сидящих превратиться в полсотни обращений к диску.
 */
const MAX_OFFLINE_CHECKS = 10;

/**
 * Тюрьмы EssentialsX: посадка, выпуск и «кто сейчас сидит».
 *
 * <h2>Почему это не обычная быстрая команда</h2>
 *
 * Остальные кнопки быстрых действий — шаблон команды с подстановками. Здесь
 * так нельзя: `togglejail` ведёт себя по-разному в зависимости от того, сидит
 * ли игрок и в какой именно тюрьме, и одну и ту же кнопку «в тюрьму» нужно
 * превращать то в одну команду, то в две. Не зная состояния, правильную
 * последовательность не собрать.
 *
 * <h2>Что именно делает togglejail (проверено по исходникам 2.x)</h2>
 *
 * <ul>
 *   <li>не сидит: `togglejail игрок тюрьма [срок]` — сажает;</li>
 *   <li>сидит в ТОЙ ЖЕ тюрьме и указан срок: срок ЗАМЕНЯЕТСЯ на новый
 *       (setJailTimeout), хотя сообщение называется «продлён»;</li>
 *   <li>сидит в ТОЙ ЖЕ тюрьме без срока: команда падает на разборе пустой
 *       строки — этот вызов недопустим, см. ниже;</li>
 *   <li>сидит в ДРУГОЙ тюрьме: EssentialsX ОТКАЗЫВАЕТСЯ переводить и отвечает
 *       jailAlreadyIncarcerated. Перевод — это выпуск и посадка заново;</li>
 *   <li>ровно один аргумент: сидящего выпускает, а НЕ сидящего — сажает, если
 *       на сервере настроена ровно одна тюрьма. Отсюда требование сначала
 *       убедиться, что игрок действительно сидит.</li>
 * </ul>
 *
 * <h2>Почему командой, а не через companion</h2>
 *
 * Посадка у EssentialsX — это ещё и телепорт, событие JailStatusChangeEvent
 * для других плагинов, сообщение игроку и оповещение персонала. Дёрнув
 * внутренний метод, мы получили бы игрока в тюрьме, о котором никто не узнал.
 * Companion поэтому только читает.
 */
@Injectable()
export class JailsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly companion: CompanionService,
    private readonly rcon: VanillaRconService,
  ) {}

  // ------------------------------------------------------------- Чтение

  /**
   * Тюрьмы и сидельцы.
   *
   * Список склеивается из двух источников. Плагин знает, кто сидит СЕЙЧАС, но
   * только про тех, кто в сети. Панель знает, кого посадила она сама и когда —
   * этого не знает никто больше, потому что EssentialsX момент посадки не
   * хранит вовсе, только момент выхода.
   */
  async list(serverId: string): Promise<MinecraftJailsDto> {
    const [info, players] = await Promise.all([
      this.companion.getJails(serverId),
      this.companion.getPlayers(serverId),
    ]);
    if (!info || !info.available) return { available: false, jails: [], jailed: [] };

    const records = await this.prisma.minecraftJailRecord.findMany({ where: { serverId } });
    const live = new Map(info.jailed.map((e) => [e.name.toLowerCase(), e]));
    const online = new Set((players ?? []).map((p) => p.name.toLowerCase()));

    // Записи о тех, кто в сети и НЕ сидит, устарели: выпустили в игре мимо
    // панели. Про офлайновых так сказать нельзя — их плагин просто не видит.
    const stale = records.filter((r) => online.has(r.playerName) && !live.has(r.playerName));
    if (stale.length) {
      await this.prisma.minecraftJailRecord
        .deleteMany({ where: { id: { in: stale.map((r) => r.id) } } })
        .catch(() => undefined);
    }

    const byName = new Map(records.map((r) => [r.playerName, r]));
    const jailed: MinecraftJailedPlayerDto[] = info.jailed.map((entry) => {
      const record = byName.get(entry.name.toLowerCase());
      // Запись годится, только если тюрьма совпала. Иначе игрока перевели в
      // игре, и «сидит с такого-то» относилось бы к прошлой отсидке.
      const ours = record && record.jail.toLowerCase() === entry.jail.toLowerCase() ? record : null;
      return {
        uuid: entry.uuid,
        name: entry.name,
        jail: entry.jail,
        releaseAt: entry.releaseAt,
        jailedAt: ours ? ours.jailedAt.getTime() : null,
        jailedBy: ours?.jailedBy ?? null,
        online: true,
      };
    });

    // Офлайн-сидельцы. В ответе плагина их нет и быть не может, поэтому
    // берутся из записей панели — но каждая запись СВЕРЯЕТСЯ с сервером
    // отдельным запросом. Так решаются сразу три вещи: сидящий, которого
    // выпустили в игре, перестаёт числиться; переведённый в другую тюрьму
    // показывается там, где он на самом деле; и главное — появляется остаток
    // срока, которого у записи панели нет.
    //
    // По запросу на запись — это дорого только на бумаге: записей столько,
    // скольких панель посадила и кто сейчас не в сети, то есть единицы.
    // Верхняя граница всё равно стоит: список сидящих не должен уметь
    // превращаться в сотню обращений к диску игрового сервера.
    const offline = records.filter(
      (r) => !live.has(r.playerName) && !online.has(r.playerName),
    );
    const checked = await Promise.all(
      offline
        .slice(0, MAX_OFFLINE_CHECKS)
        .map((r) => this.companion.getPlayerJail(serverId, r.playerName)),
    );

    const orphans: string[] = [];
    offline.forEach((record, index) => {
      // Сверх лимита — показываем по записи, без остатка срока. Честнее, чем
      // спрятать человека, который сидит.
      const state = index < checked.length ? checked[index] : undefined;
      if (state && state.known && !state.jailed) {
        orphans.push(record.id);
        return;
      }
      jailed.push({
        // UUID у офлайн-записи нет: панель сажает по нику, и в момент посадки
        // игрока может не быть ни в сети, ни в кэше сервера.
        uuid: '',
        name: record.playerName,
        jail: state?.jailed ? state.jail : record.jail,
        // null — спросить не удалось: companion молчит либо записей больше
        // лимита. «До отмены» тут было бы выдумкой.
        releaseAt: state?.jailed ? state.releaseAt : null,
        jailedAt: record.jailedAt.getTime(),
        jailedBy: record.jailedBy,
        online: false,
      });
    });
    if (orphans.length) {
      await this.prisma.minecraftJailRecord
        .deleteMany({ where: { id: { in: orphans } } })
        .catch(() => undefined);
    }

    jailed.sort((a, b) => a.name.localeCompare(b.name));
    return { available: true, jails: info.jails, jailed };
  }

  // ------------------------------------------------------------- Посадка

  /**
   * Посадить или изменить срок.
   *
   * Перевод в другую тюрьму и «сидит бессрочно» делаются в две команды:
   * сначала выпуск, потом посадка заново. Первое — потому что EssentialsX
   * отказывается переводить одной командой, второе — потому что срок «до
   * отмены» это ноль, а через продление ноль не выставить: любой срок он
   * считает от «сейчас», и ноль означал бы «выпустить немедленно».
   *
   * Цена перевода — видимый скачок: на выпуске игрока телепортирует из тюрьмы
   * (куда именно, решает настройка teleport-when-free самого EssentialsX), и
   * следом он уезжает в новую тюрьму. Обойти это, не теряя событий плагина,
   * нельзя.
   */
  async jail(
    serverId: string,
    dto: MinecraftJailRequestDto,
    actorId: string,
  ): Promise<{ output: string }> {
    const player = this.rcon.assertNickname(dto.player);
    const info = await this.requireJails(serverId);

    const jail = info.jails.find((name) => name.toLowerCase() === dto.jail.trim().toLowerCase());
    if (!jail) throw new BadRequestException('mc.err.unknownJail');

    const duration = (dto.duration ?? '').trim();
    if (duration && !DURATION_RE.test(duration)) {
      throw new BadRequestException('mc.err.badJailDuration');
    }

    const current = await this.currentJail(serverId, player, info);
    const sameJail = current && current.toLowerCase() === jail.toLowerCase();

    // Одной командой обходимся ровно в двух случаях: игрок не сидит вовсе
    // либо сидит в этой же тюрьме и ему назначают новый срок. Всё остальное —
    // выпуск и посадка заново.
    const inPlace = sameJail && !!duration;
    const commands: string[] = [];
    if (current !== null && !inPlace) commands.push(`togglejail ${player}`);
    commands.push(`togglejail ${player} ${jail}${duration ? ` ${duration}` : ''}`);

    const output: string[] = [];
    for (const command of commands) {
      output.push(await this.rcon.runCommand(serverId, command));
    }

    const jailedBy = await this.actorNickname(actorId);
    // Момент посадки обновляем и при смене срока: для сотрудника «сидит
    // столько-то» отсчитывается от последнего решения, а не от первого.
    await this.prisma.minecraftJailRecord.upsert({
      where: { serverId_playerName: { serverId, playerName: player.toLowerCase() } },
      create: { serverId, playerName: player.toLowerCase(), jail, jailedBy },
      update: { jail, jailedBy, jailedAt: new Date() },
    });

    return { output: output.filter((line) => line.trim()).join('\n') };
  }

  // ------------------------------------------------------------- Выпуск

  /**
   * Выпустить.
   *
   * Сначала убеждаемся, что игрок действительно сидит, и только потом шлём
   * команду: `togglejail` с одним аргументом по НЕ сидящему игроку на сервере
   * с единственной настроенной тюрьмой посадит его туда. Кнопка «выпустить»,
   * сажающая в тюрьму, — худшее, что тут может случиться.
   */
  async release(serverId: string, player: string): Promise<{ output: string }> {
    const name = this.rcon.assertNickname(player);
    const info = await this.requireJails(serverId);
    if ((await this.currentJail(serverId, name, info)) === null) {
      throw new BadRequestException('mc.err.notJailed');
    }

    const output = await this.rcon.runCommand(serverId, `togglejail ${name}`);
    await this.prisma.minecraftJailRecord
      .deleteMany({ where: { serverId, playerName: name.toLowerCase() } })
      .catch(() => undefined);
    // Кто выпустил, остаётся в audit_log: отдельного поля для этого нет —
    // запись живёт ровно пока человек сидит и тут же удаляется.
    return { output };
  }

  // ------------------------------------------------------------- Служебное

  /**
   * В какой тюрьме игрок сидит СЕЙЧАС; null — не сидит.
   *
   * Сначала смотрим в уже полученный список сидящих: для игрока в сети это
   * бесплатно. Для остальных спрашиваем сервер про одного человека — иначе
   * состояние офлайн-игрока узнать негде, а без него команда собирается
   * наугад: посадка того, кто уже сидит, у EssentialsX либо не делает ничего
   * (другая тюрьма), либо падает на разборе пустого срока (та же).
   *
   * Если companion не ответил, считаем «не сидит»: сервер в этом случае сам
   * рассудит команду и в худшем случае откажет — это лучше, чем не дать
   * посадить нарушителя из-за молчания необязательного плагина.
   */
  private async currentJail(
    serverId: string,
    player: string,
    info: CompanionJails,
  ): Promise<string | null> {
    const online = info.jailed.find((e) => e.name.toLowerCase() === player.toLowerCase());
    if (online) return online.jail;

    const state = await this.companion.getPlayerJail(serverId, player);
    return state?.jailed ? state.jail : null;
  }

  private async requireJails(serverId: string) {
    const info = await this.companion.getJails(serverId);
    // Без плагина панели список тюрем взять неоткуда: RCON их не отдаёт, а
    // сажать в тюрьму, которой нет, значит получить ошибку в консоли вместо
    // внятного ответа.
    if (!info || !info.available) throw new ServiceUnavailableException('mc.err.noJails');
    if (!info.jails.length) throw new BadRequestException('mc.err.noJailsConfigured');
    return info;
  }

  private async actorNickname(actorId: string): Promise<string> {
    const user = await this.prisma.user.findUnique({
      where: { id: actorId },
      select: { nickname: true },
    });
    return user?.nickname ?? '—';
  }
}
