import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { request } from 'undici';
import {
  addonsForModule,
  allAddons,
  type AddonInstallResultDto,
  type AurumAddon,
  type ServerAddonDto,
  type ServerAddonsDto,
} from '@aurum/shared';
import { AuditService } from '../audit/audit.service';
import { PrismaService } from '../prisma/prisma.service';
import { SettingsService } from '../settings/settings.service';
import { PluginFilesService } from '../modules/minecraft/plugins/plugin-files.service';
import { isInstalled, latestRelease, type AddonRelease } from './addons-releases';

/**
 * Репозиторий с релизами наших плагинов.
 *
 * ПУБЛИЧНЫЙ И БЕЗ ТОКЕНА — намеренно. Токен пришлось бы держать в настройках
 * панели, продлевать и объяснять третьим лицам, зачем он; а прячет он ровно
 * те jar-ы, которые мы и так раздаём всем, кто заводит у нас сервер.
 */
const ADDONS_REPO = 'Aki333chan/addons';
const RELEASES_URL = `https://api.github.com/repos/${ADDONS_REPO}/releases?per_page=100`;

/** Плагины кладём туда же, куда и маркет. */
const PLUGINS_DIR = '/plugins';

/**
 * Сколько держать список релизов в памяти.
 *
 * Заход на страницу сервера спрашивает состояние аддонов, а страниц у
 * панели десятки: без кэша каждый переход между серверами стучался бы в
 * GitHub, у которого лимит на неаутентифицированные запросы — шестьдесят в
 * час на адрес. Пять минут — это свежесть, которой хватает: релизы наших
 * плагинов выходят не чаще раза в день.
 */
const RELEASES_TTL_MS = 5 * 60 * 1000;

/**
 * Как часто пытаться поставить обязательный аддон заново.
 *
 * Автоустановка срабатывает при заходе на страницу сервера, а страницу
 * открывают и обновляют часто. Если установка не удалась (GitHub недоступен,
 * файловый API молчит), без этой паузы панель ходила бы в сеть на каждое
 * обновление страницы. Успех сюда не попадает: следующий заход увидит файл в
 * plugins/ и не станет ставить ничего.
 */
const RETRY_AFTER_MS = 10 * 60 * 1000;

@Injectable()
export class AddonsService {
  private readonly logger = new Logger(AddonsService.name);

  /** Кэш списка релизов: один на всю панель, аддоны у всех одни и те же. */
  private releases: { at: number; data: unknown } | null = null;
  /** Когда последний раз НЕУДАЧНО пытались поставить обязательный аддон. */
  private readonly lastFailedTry = new Map<string, number>();

  constructor(
    private readonly prisma: PrismaService,
    private readonly settings: SettingsService,
    private readonly plugins: PluginFilesService,
    private readonly audit: AuditService,
  ) {}

  // ------------------------------------------------------------ состояние

  /**
   * Что панель знает об аддонах этого сервера.
   *
   * `canInstall` приходит снаружи, из прав запроса: сервис не знает, кто его
   * позвал, а спрашивать RBAC второй раз внутри значило бы разъехаться с тем,
   * что уже проверил контроллер.
   */
  async state(serverId: string, canInstall: boolean): Promise<ServerAddonsDto> {
    const server = await this.prisma.server.findUnique({
      where: { id: serverId },
      select: { moduleId: true, addonsDismissed: true },
    });
    if (!server) throw new BadRequestException('mc.err.serverNotFound');

    const featureEnabled = await this.settings.addonsOfferEnabled();
    const { required, optional } = addonsForModule(server.moduleId);

    // Файлы читаем один раз на все аддоны сразу: их до трёх, а листинг
    // папки — сетевой запрос в Pterodactyl.
    const files = allAddons(server.moduleId).length > 0
      ? await this.plugins.pluginFileNames(serverId)
      : [];
    const filesAvailable = files !== null;
    const known = files ?? [];

    const describe = (addon: AurumAddon): ServerAddonDto => ({
      id: addon.id,
      displayName: addon.displayName,
      aboutKey: addon.aboutKey,
      installed: isInstalled(addon.pluginName, known),
    });

    const optionalState = optional.map(describe);

    return {
      featureEnabled,
      moduleId: server.moduleId,
      dismissed: server.addonsDismissed,
      canInstall,
      filesAvailable,
      required: required ? describe(required) : null,
      optional: optionalState,
      // Все шесть условий сразу и в одном месте. Разложи их по фронтенду — и
      // однажды поп-ап выскочит после «не предлагать».
      canOffer:
        featureEnabled &&
        canInstall &&
        filesAvailable &&
        !server.addonsDismissed &&
        optionalState.some((a) => !a.installed),
    };
  }

  // ------------------------------------------ обязательный аддон, молча

  /**
   * Поставить обязательный аддон, если его ещё нет, и вернуть состояние.
   *
   * БЕЗ ДИАЛОГА И БЕЗ ПРАВА НА УСТАНОВКУ. Companion — не предложение, а
   * условие работы панели с этим сервером: без него нет ни инвентарей, ни
   * экономики, ни списка плагинов. Спрашивать разрешения на то, без чего
   * инструмент не работает, — вопрос ради вопроса; а привязать установку к
   * праву значило бы, что сервер, открытый сначала модератором, останется
   * наполовину слепым до прихода Админа.
   *
   * В аудите исполнитель — панель, но рядом записан тот, при чьём заходе это
   * случилось: «сделала система» без единого имени рядом — плохая запись.
   */
  async bootstrap(serverId: string, viewerId: string, canInstall: boolean): Promise<ServerAddonsDto> {
    const state = await this.state(serverId, canInstall);
    if (!state.featureEnabled) return state;
    if (!state.required || state.required.installed || !state.filesAvailable) return state;

    const lastFail = this.lastFailedTry.get(serverId) ?? 0;
    if (Date.now() - lastFail < RETRY_AFTER_MS) return state;

    const addon = addonsForModule(state.moduleId).required!;
    try {
      const result = await this.installOne(serverId, addon, viewerId, true);
      this.lastFailedTry.delete(serverId);
      return {
        ...state,
        required: { ...state.required, installed: true },
        requiredInstall: result.restartRequired ? 'restart-required' : 'installed',
      };
    } catch (e) {
      this.lastFailedTry.set(serverId, Date.now());
      this.logger.warn(
        `Не удалось поставить ${addon.displayName} на сервер ${serverId}: ${(e as Error).message}`,
      );
      return { ...state, requiredInstall: 'failed', requiredError: errorKey(e) };
    }
  }

  // ------------------------------------------------ выбранные вручную

  /**
   * Поставить выбранные в поп-апе аддоны.
   *
   * Неудача одного не отменяет остальных: список выдают целиком, и «ничего не
   * поставилось, потому что у одного не нашёлся релиз» — худший из возможных
   * ответов. По строке на каждый, как в выдаче предметов.
   */
  async install(
    serverId: string,
    ids: string[],
    actorId: string,
  ): Promise<AddonInstallResultDto[]> {
    const server = await this.prisma.server.findUnique({
      where: { id: serverId },
      select: { moduleId: true },
    });
    if (!server) throw new BadRequestException('mc.err.serverNotFound');
    if (!(await this.settings.addonsOfferEnabled())) {
      throw new BadRequestException('addons.err.disabled');
    }

    // Ставим только то, что этому модулю вообще положено: id приходит из
    // браузера, и принимать по нему что угодно из репозитория нельзя.
    const allowed = allAddons(server.moduleId);
    const chosen = ids
      .map((id) => allowed.find((a) => a.id === id))
      .filter((a): a is AurumAddon => a !== undefined);

    const results: AddonInstallResultDto[] = [];
    for (const addon of chosen) {
      try {
        const done = await this.installOne(serverId, addon, actorId, false);
        results.push({
          id: addon.id,
          displayName: addon.displayName,
          ok: true,
          restartRequired: done.restartRequired,
          message: done.restartRequired
            ? 'mc.err.installedPluginRunning'
            : 'mc.err.installedPluginStopped',
          messageValues: { dir: 'plugins' },
        });
      } catch (e) {
        results.push({
          id: addon.id,
          displayName: addon.displayName,
          ok: false,
          restartRequired: false,
          message: errorKey(e),
          messageValues: errorValues(e),
        });
      }
    }
    return results;
  }

  /** «Больше не предлагать» — для этого сервера. */
  async dismiss(serverId: string, actorId: string): Promise<void> {
    await this.prisma.server.update({
      where: { id: serverId },
      data: { addonsDismissed: true },
    });
    await this.audit.log({
      actorId,
      action: 'addons.dismiss',
      targetType: 'server',
      targetId: serverId,
    });
  }

  // ------------------------------------------------------- внутреннее

  /**
   * @param automatic true — панель ставит обязательный аддон сама, без спроса.
   *        Отдельным ДЕЙСТВИЕМ в аудите, а не пометкой в метаданных: «кто
   *        установил» читают по колонке действия, и «Вася поставил companion»
   *        там, где Вася всего лишь открыл страницу, — неверная запись.
   *        Имя рядом всё равно нужно: «сделала система» без единого человека
   *        рядом не даёт ни начала, ни конца при разборе.
   */
  private async installOne(
    serverId: string,
    addon: AurumAddon,
    actorId: string,
    automatic: boolean,
  ): Promise<{ restartRequired: boolean }> {
    const release = await this.findRelease(addon);
    const identifier = await this.plugins.pteroIdentifier(serverId);

    const log = (ok: boolean, extra: Record<string, unknown>) =>
      this.audit.log({
        // Автоустановку выполняет панель, но рядом записан тот, при чьём
        // заходе это произошло.
        actorId,
        action: automatic ? 'addons.autoInstall' : 'addons.install',
        targetType: 'server',
        targetId: serverId,
        metadata: { addon: addon.id, version: release.version, tag: release.tag, ok, ...extra },
      });

    const placed = await this.plugins.fetchAndPlace(
      identifier,
      { url: release.downloadUrl, fileName: release.fileName },
      PLUGINS_DIR,
      log,
    );
    await log(true, { sizeBytes: placed.jar.length, restartRequired: placed.running });
    return { restartRequired: placed.running };
  }

  private async findRelease(addon: AurumAddon): Promise<AddonRelease> {
    const release = latestRelease(await this.fetchReleases(), addon.tagPrefix);
    if (!release) {
      throw new BadRequestException({
        message: 'addons.err.noRelease',
        i18nValues: { name: addon.displayName },
      });
    }
    return release;
  }

  private async fetchReleases(): Promise<unknown> {
    const cached = this.releases;
    if (cached && Date.now() - cached.at < RELEASES_TTL_MS) return cached.data;

    const res = await request(RELEASES_URL, {
      headers: {
        accept: 'application/vnd.github+json',
        'user-agent': 'Aki333chan/aurum-panel (game server admin panel)',
      },
      maxRedirections: 3,
      headersTimeout: 10_000,
      bodyTimeout: 15_000,
    });
    if (res.statusCode >= 400) {
      // Устаревший кэш лучше отказа: релизы меняются раз в неделю, а лимит
      // GitHub на неаутентифицированные запросы кончается за минуту.
      if (cached) return cached.data;
      throw new BadRequestException({
        message: 'addons.err.releasesUnavailable',
        i18nValues: { status: String(res.statusCode) },
      });
    }
    const data: unknown = await res.body.json();
    this.releases = { at: Date.now(), data };
    return data;
  }
}

/** Ключ словаря из брошенного исключения; чужая ошибка — общей формулировкой. */
function errorKey(e: unknown): string {
  const body = (e as { response?: unknown })?.response;
  if (typeof body === 'string') return body;
  if (body && typeof body === 'object') {
    const message = (body as Record<string, unknown>)['message'];
    if (typeof message === 'string') return message;
  }
  return 'addons.err.installFailed';
}

function errorValues(e: unknown): Record<string, string> | undefined {
  const body = (e as { response?: unknown })?.response;
  if (body && typeof body === 'object') {
    const values = (body as Record<string, unknown>)['i18nValues'];
    if (values && typeof values === 'object') return values as Record<string, string>;
  }
  return undefined;
}
