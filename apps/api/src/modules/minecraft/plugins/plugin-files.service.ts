import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { createHash } from 'crypto';
import { request } from 'undici';
import {
  DISABLED_PLUGINS_DIR,
  type InstalledPluginDto,
  type InstalledPluginsResponseDto,
  type MarketSourceId,
  type PluginInstallResultDto,
} from '@aurum/shared';
import { AuditService } from '../../../audit/audit.service';
import { PrismaService } from '../../../prisma/prisma.service';
import { ClientApiService } from '../../../pterodactyl/client-api.service';
import { CompanionService } from '../companion.service';
import { MarketService } from './market.service';
import { fileProtectionReasonKey, protectionReasonKey } from './plugin-protection';

/**
 * Установка, включение/выключение и удаление плагинов на игровом сервере.
 *
 * Всё, что здесь есть, — это фактически возможность выполнить произвольный код
 * на игровом сервере: плагин Bukkit — обычная Java-программа без песочницы.
 * Отсюда два следствия, которые нельзя ослаблять:
 *   - права по умолчанию только у ГМ и Админа (см. манифест модуля);
 *   - КАЖДОЕ действие пишется в audit_log с версией, источником и сервером.
 */

const PLUGINS_DIR = '/plugins';
const DISABLED_DIR = `${PLUGINS_DIR}/${DISABLED_PLUGINS_DIR}`;

/**
 * Моды кладутся в mods/, плагины — в plugins/, и перепутать их нельзя.
 *
 * Это не косметика: Forge и Fabric читают только mods/, серверные ядра
 * семейства Bukkit — только plugins/. Мод, положенный в plugins/, просто
 * не загрузится, а плагин в mods/ у Forge ещё и роняет запуск.
 *
 * Управление установленным (список, включение, удаление) осталось только для
 * плагинов: живое состояние приходит от companion-плагина Bukkit, а его на
 * Forge/Fabric нет. Установка модов работает, разбор их состояния — нет, и
 * притворяться обратным панель не станет.
 */
const MODS_DIR = '/mods';

/** Больше этого плагины практически не весят, а память панели не резиновая. */
const MAX_JAR_BYTES = 150 * 1024 * 1024;

@Injectable()
export class PluginFilesService {
  private readonly logger = new Logger(PluginFilesService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly client: ClientApiService,
    private readonly companion: CompanionService,
    private readonly market: MarketService,
    private readonly audit: AuditService,
  ) {}

  private async identifier(serverId: string): Promise<string> {
    const server = await this.prisma.server.findUnique({
      where: { id: serverId },
      select: { pteroIdentifier: true },
    });
    if (!server) throw new BadRequestException('mc.err.serverNotFound');
    return server.pteroIdentifier;
  }

  // ------------------------------------------------------------ Установка

  /**
   * Скачивает jar выбранной версии и кладёт в plugins/ сервера.
   *
   * Скачивает именно панель, а не Wings: только так можно сверить хэш,
   * который отдал источник. Мы ставим исполняемый код на живой сервер —
   * проверить, что приехало ровно то, что обещано, стоит одного лишнего
   * прыжка через панель.
   */
  async install(
    serverId: string,
    source: MarketSourceId,
    pluginId: string,
    versionId: string,
    actorId: string,
  ): Promise<PluginInstallResultDto> {
    const file = await this.market.getVersionFile(source, pluginId, versionId);
    const identifier = await this.identifier(serverId);

    const log = (ok: boolean, extra: Record<string, unknown>) =>
      this.audit.log({
        actorId,
        action: 'minecraft.plugin.install',
        targetType: 'server',
        targetId: serverId,
        metadata: { source, pluginId, versionId, fileName: file.fileName, ok, ...extra },
      });

    let jar: Buffer;
    try {
      jar = await this.download(file.url);
    } catch (e) {
      await log(false, { error: (e as Error).message });
      // Своя причина — своим ключом; чужая (обрыв связи, отказ TLS) приходит
      // строкой от undici и остаётся как есть: переводить её панель не может
      // и врать про причину не должна.
      throw e instanceof DownloadFailure
        ? new BadRequestException({ message: e.key, i18nValues: e.values })
        : new BadRequestException({
            message: 'mc.err.downloadFailed',
            i18nValues: { error: (e as Error).message },
          });
    }

    if (file.hash && !verifyHash(jar, file.hash)) {
      await log(false, { error: 'хэш не совпал' });
      throw new BadRequestException('mc.err.hashMismatch');
    }

    // Часть источников (SpigotMC) хэшей не отдаёт вовсе, и тогда единственная
    // защита от «приехал не тот файл» — посмотреть, что приехало. jar это zip,
    // у него есть сигнатура. Без этой проверки HTML-страница с ошибкой легла
    // бы в plugins/ под именем плагина, и сервер молча не загрузил бы его.
    if (!looksLikeJar(jar)) {
      await log(false, { error: 'приехал не jar' });
      throw new BadRequestException('mc.err.notAJar');
    }

    const safeName = sanitizeJarName(file.fileName);
    const targetDir = file.projectType === 'mod' ? MODS_DIR : PLUGINS_DIR;
    await this.client.writeFile(identifier, `${targetDir}/${safeName}`, jar);

    // Состояние сервера решает только текст предупреждения: запущенный сервер
    // подхватит новый jar лишь после перезапуска, выключенный — при старте.
    const running = await this.isRunning(identifier);
    await log(true, {
      sizeBytes: jar.length,
      restartRequired: running,
      projectType: file.projectType,
      dir: targetDir,
    });

    return {
      ok: true,
      fileName: safeName,
      sizeBytes: jar.length,
      restartRequired: running,
      // Четыре ключа вместо подстановки «Мод»/«Плагин»: слово это часть
      // фразы и склоняется вместе с ней, а подставленное готовым оно бы
      // застряло в именительном падеже посреди чужого языка.
      message:
        file.projectType === 'mod'
          ? running
            ? 'mc.err.installedModRunning'
            : 'mc.err.installedModStopped'
          : running
            ? 'mc.err.installedPluginRunning'
            : 'mc.err.installedPluginStopped',
      messageValues: { dir: targetDir.slice(1) },
    };
  }

  private async download(url: string): Promise<Buffer> {
    const res = await request(url, {
      method: 'GET',
      headers: { 'user-agent': 'Aki333chan/aurum-panel (game server admin panel)' },
      maxRedirections: 5,
      headersTimeout: 30_000,
      bodyTimeout: 120_000,
    });
    if (res.statusCode >= 400) {
      throw new DownloadFailure('mc.err.dl.status', { status: res.statusCode });
    }

    const chunks: Buffer[] = [];
    let total = 0;
    for await (const chunk of res.body) {
      const buf = Buffer.from(chunk);
      total += buf.length;
      if (total > MAX_JAR_BYTES) throw new DownloadFailure('mc.err.dl.tooBig');
      chunks.push(buf);
    }
    if (total === 0) throw new DownloadFailure('mc.err.dl.empty');
    return Buffer.concat(chunks);
  }

  private async isRunning(identifier: string): Promise<boolean> {
    try {
      const res = await this.client.getResources(identifier);
      return res.current_state === 'running' || res.current_state === 'starting';
    } catch {
      // Pterodactyl не ответил — предупреждаем на всякий случай, как будто
      // сервер запущен: лишнее предупреждение безобиднее пропущенного.
      return true;
    }
  }

  // --------------------------------------------- Установленные плагины

  /**
   * Что стоит на сервере: живое состояние от companion-плагина плюс файлы из
   * plugins/ и plugins/.disabled/.
   *
   * Два источника нужны потому, что каждый знает своё: companion видит
   * загруженные плагины и их имена в Bukkit, а файловый API — что физически
   * лежит на диске, включая отключённое переносом.
   */
  async listInstalled(serverId: string): Promise<InstalledPluginsResponseDto> {
    const identifier = await this.identifier(serverId);
    const live = await this.companion.getInstalledPlugins(serverId);

    let files: string[] = [];
    let disabled: string[] = [];
    let filesAvailable = true;
    try {
      files = (await this.client.listFiles(identifier, PLUGINS_DIR))
        .filter((f) => f.is_file && f.name.toLowerCase().endsWith('.jar'))
        .map((f) => f.name);
      disabled = await this.listDisabled(identifier);
    } catch (e) {
      filesAvailable = false;
      this.logger.warn(`Файлы сервера ${serverId} недоступны: ${(e as Error).message}`);
    }

    const plugins: InstalledPluginDto[] = (live ?? []).map((p) => {
      const reasonKey = protectionReasonKey(p.name);
      return {
        name: p.name,
        version: p.version,
        state: p.enabled ? 'enabled' : 'disabled-runtime',
        fileName: guessFile(files, p.name),
        protected: reasonKey !== null,
        ...(reasonKey ? { protectedReasonKey: reasonKey } : {}),
      };
    });

    // Отключённые переносом сервер не видит вовсе — они есть только на диске.
    for (const fileName of disabled) {
      // Тут имени из Bukkit нет, есть только файл: сверяемся по нему.
      const reasonKey = fileProtectionReasonKey(fileName);
      plugins.push({
        name: nameFromJar(fileName),
        version: null,
        state: 'disabled-file',
        fileName,
        protected: reasonKey !== null,
        ...(reasonKey ? { protectedReasonKey: reasonKey } : {}),
      });
    }

    return {
      companionAvailable: live !== null,
      filesAvailable,
      ...(live === null
        ? { reason: 'mc.err.filesOnly' }
        : {}),
      plugins: plugins.sort(byProtectedThenName),
    };
  }

  private async listDisabled(identifier: string): Promise<string[]> {
    try {
      return (await this.client.listFiles(identifier, DISABLED_DIR))
        .filter((f) => f.is_file && f.name.toLowerCase().endsWith('.jar'))
        .map((f) => f.name);
    } catch {
      // Папки просто ещё нет — это норма, а не ошибка.
      return [];
    }
  }

  // ------------------------------------------------------- Переключение

  /**
   * Включение/выключение «на горячую» через PluginManager.
   *
   * Это best-effort по самой природе Bukkit: плагины регистрируют слушателей,
   * задачи и команды, и далеко не все аккуратно убирают их за собой при
   * выключении. Ровно поэтому /reload считается рискованной командой. Панель
   * говорит об этом человеку прямо и держит рядом кнопку перезапуска.
   */
  async setEnabled(
    serverId: string,
    pluginName: string,
    enabled: boolean,
    actorId: string,
  ): Promise<PluginActionResult> {
    // Запрет односторонний: выключать защищённый плагин нельзя, а включать —
    // можно и нужно. Если LuckPerms оказался выключен, кнопка «Включить» это
    // единственное, чем панель ещё способна помочь.
    if (!enabled) this.assertNotProtected(pluginName);
    const result = await this.companion.setPluginEnabled(serverId, pluginName, enabled);

    await this.audit.log({
      actorId,
      action: enabled ? 'minecraft.plugin.enable' : 'minecraft.plugin.disable',
      targetType: 'server',
      targetId: serverId,
      metadata: { plugin: pluginName, ok: result.ok, error: result.error ?? null },
    });

    if (!result.ok) {
      throw new BadRequestException(result.error ?? 'mc.err.toggleFailed');
    }
    return {
      ok: true,
      message: enabled ? 'mc.err.pluginEnabled' : 'mc.err.pluginDisabled',
      messageValues: { plugin: pluginName },
    };
  }

  /**
   * Отключение переносом файла в plugins/.disabled/.
   *
   * Физический перенос, а не пометка в базе панели: так состояние переживает
   * перезапуск сервера кем угодно — через панель, через Pterodactyl напрямую
   * или руками по SFTP. Отметка в базе врала бы при первом же старте мимо
   * панели.
   */
  async setFileDisabled(
    serverId: string,
    fileName: string,
    disabled: boolean,
    actorId: string,
  ): Promise<PluginActionResult> {
    const safeName = sanitizeJarName(fileName);
    // Как и с горячим выключением: унести файл защищённого плагина нельзя,
    // вернуть его из .disabled/ обратно — можно.
    if (disabled) this.assertFileNotProtected(safeName);
    const identifier = await this.identifier(serverId);

    if (disabled) {
      // Папку создаём заранее: перенос в несуществующий каталог не сработает.
      await this.client.createFolder(identifier, PLUGINS_DIR, DISABLED_PLUGINS_DIR).catch(() => undefined);
      await this.client.renameFile(
        identifier,
        PLUGINS_DIR,
        safeName,
        `${DISABLED_PLUGINS_DIR}/${safeName}`,
      );
    } else {
      await this.client.renameFile(
        identifier,
        PLUGINS_DIR,
        `${DISABLED_PLUGINS_DIR}/${safeName}`,
        safeName,
      );
    }

    await this.audit.log({
      actorId,
      action: disabled ? 'minecraft.plugin.file-disable' : 'minecraft.plugin.file-enable',
      targetType: 'server',
      targetId: serverId,
      metadata: { fileName: safeName, ok: true },
    });

    return {
      ok: true,
      message: disabled ? 'mc.err.fileDisabled' : 'mc.err.fileRestored',
      messageValues: { file: safeName, dir: DISABLED_PLUGINS_DIR },
    };
  }

  /**
   * Удаление плагина.
   *
   * Папка данных удаляется ТОЛЬКО по явной просьбе: в ней конфиги, базы
   * прав, экономика — то, что восстановить неоткуда. По умолчанию не трогаем.
   */
  async remove(
    serverId: string,
    input: { fileName: string; pluginName?: string; withData: boolean },
    actorId: string,
  ): Promise<PluginActionResult> {
    if (input.pluginName) this.assertNotProtected(input.pluginName);
    const safeName = sanitizeJarName(input.fileName);
    this.assertFileNotProtected(safeName);
    const identifier = await this.identifier(serverId);

    // Файл может лежать и в plugins/, и в .disabled/ — сносим там, где он есть.
    // Промах по одной из папок это норма (файл всегда только в одной), а вот
    // отказ самого Pterodactyl — нет: молча отрапортовать об удалении, когда
    // ничего не удалено, значит соврать человеку про состояние сервера.
    const results = await Promise.all([
      this.tryDelete(identifier, PLUGINS_DIR, safeName),
      this.tryDelete(identifier, DISABLED_DIR, safeName),
    ]);
    const failure = results.find((r) => r.error);
    if (!results.some((r) => r.deleted)) {
      await this.audit.log({
        actorId,
        action: 'minecraft.plugin.remove',
        targetType: 'server',
        targetId: serverId,
        metadata: { fileName: safeName, ok: false, error: failure?.error ?? 'файл не найден' },
      });
      throw new BadRequestException(
        failure?.error
          ? { message: 'mc.err.removeFailed', i18nValues: { error: failure.error } }
          : { message: 'mc.err.fileNotFound', i18nValues: { file: safeName, dir: DISABLED_PLUGINS_DIR } },
      );
    }

    let dataRemoved = false;
    if (input.withData && input.pluginName) {
      const folder = sanitizeFolderName(input.pluginName);
      dataRemoved = (await this.tryDelete(identifier, PLUGINS_DIR, folder)).deleted;
    }

    await this.audit.log({
      actorId,
      action: 'minecraft.plugin.remove',
      targetType: 'server',
      targetId: serverId,
      metadata: {
        fileName: safeName,
        plugin: input.pluginName ?? null,
        withData: input.withData,
        dataRemoved,
        ok: true,
      },
    });

    return {
      ok: true,
      message: dataRemoved
        ? 'mc.err.removedWithData'
        : input.withData
          ? 'mc.err.removedNoDataFolder'
          : 'mc.err.removed',
    };
  }

  /**
   * Удаление одной записи с различением «не было» и «не смогли».
   *
   * Pterodactyl отвечает 404 и когда файла нет, и когда нет папки. Первое —
   * ожидаемо (ищем в двух местах), второе тоже. А вот 500 или недоступный
   * ключ — это отказ, о котором человек должен узнать.
   */
  private async tryDelete(
    identifier: string,
    root: string,
    name: string,
  ): Promise<{ deleted: boolean; error?: string }> {
    try {
      await this.client.deleteFiles(identifier, root, [name]);
      return { deleted: true };
    } catch (e) {
      const message = (e as Error).message;
      if (message.includes('404')) return { deleted: false };
      this.logger.warn(`Не удалось удалить ${root}/${name}: ${message}`);
      return { deleted: false, error: message.slice(0, 200) };
    }
  }

  private assertNotProtected(pluginName: string): void {
    const key = protectionReasonKey(pluginName);
    // Ключ и подстановка к нему, а не готовая фраза: язык того, кто нажал
    // кнопку, известен только на краю — там фильтр ошибок и соберёт текст.
    if (key) throw new BadRequestException({ message: key, i18nValues: { name: pluginName } });
  }

  private assertFileNotProtected(fileName: string): void {
    const key = fileProtectionReasonKey(fileName);
    if (key) {
      throw new BadRequestException({ message: key, i18nValues: { name: nameFromJar(fileName) } });
    }
  }
}

function verifyHash(data: Buffer, hash: { algo: string; value: string }): boolean {
  const digest = createHash(hash.algo).update(data).digest('hex');
  return digest.toLowerCase() === hash.value.toLowerCase();
}

/**
 * Имя файла из внешнего источника попадает в путь на диске игрового сервера.
 * Всё, что похоже на выход из каталога, отсекаем — иначе плагин можно было бы
 * положить куда угодно, вплоть до перезаписи server.jar.
 */
/**
 * Похоже ли скачанное на jar.
 *
 * jar — это zip, а у zip первые байты всегда «PK» и дальше 03 04 (обычная
 * запись) либо 05 06 / 07 08 (пустой архив и разбитый на тома). Проверяем
 * ровно это: полный разбор архива тут не нужен, нужна отсечка страницы
 * «404 Not Found» и капчи, приехавших вместо файла.
 *
 * Экспортируется ради тестов: это единственная проверка содержимого для тех
 * источников, которые не отдают хэш (SpigotMC не отдаёт его вовсе).
 */
export function looksLikeJar(buffer: Buffer): boolean {
  if (buffer.length < 4) return false;
  if (buffer[0] !== 0x50 || buffer[1] !== 0x4b) return false;
  const marker = (buffer[2]! << 8) | buffer[3]!;
  return marker === 0x0304 || marker === 0x0506 || marker === 0x0708;
}

function sanitizeJarName(raw: string): string {
  const base = (raw ?? '').split(/[\\/]/).pop() ?? '';
  const cleaned = base.replace(/[^A-Za-z0-9._+-]/g, '_');
  if (!cleaned.toLowerCase().endsWith('.jar')) {
    throw new BadRequestException('mc.err.mustBeJar');
  }
  if (cleaned.startsWith('.') || cleaned.length > 200) {
    throw new BadRequestException('mc.err.badFileName');
  }
  return cleaned;
}

/** Папка данных зовётся так же, как плагин в Bukkit. */
function sanitizeFolderName(raw: string): string {
  const cleaned = (raw ?? '').replace(/[^A-Za-z0-9._+-]/g, '_');
  if (!cleaned || cleaned.startsWith('.') || cleaned.length > 200) {
    throw new BadRequestException('mc.err.badFolderName');
  }
  return cleaned;
}

/** Сопоставление плагина с файлом: имена почти всегда начинаются одинаково. */
function guessFile(files: string[], pluginName: string): string | null {
  const needle = pluginName.toLowerCase().replace(/[^a-z0-9]/g, '');
  const hit = files.find((f) => f.toLowerCase().replace(/[^a-z0-9]/g, '').startsWith(needle));
  return hit ?? null;
}

/** Имя плагина по имени файла — для тех, кого сервер не видит (отключены). */
function nameFromJar(fileName: string): string {
  return fileName.replace(/\.jar$/i, '').replace(/[-_]v?\d[\d.]*.*$/i, '') || fileName;
}

/**
 * Порядок в списке установленных: сначала неотключаемые, потом остальные.
 *
 * Неотключаемые — это те, на которых держится сама панель: companion и
 * плагины из KNOWN_PLUGINS. У них нет кнопок выключения и удаления, то есть
 * делать с ними в списке нечего, — но именно их наличие и версию проверяют
 * первым делом, когда что-то в панели перестало работать. Держать их
 * вперемешку с двумя десятками обычных плагинов значит заставлять искать
 * глазами то, на что смотрят чаще всего.
 *
 * Внутри каждой группы — по имени, без учёта регистра: порядок, в котором
 * плагины отдаёт Bukkit, зависит от порядка загрузки и человеку ни о чём не
 * говорит.
 */
export function byProtectedThenName(a: InstalledPluginDto, b: InstalledPluginDto): number {
  if (a.protected !== b.protected) return a.protected ? -1 : 1;
  return a.name.localeCompare(b.name, 'ru', { sensitivity: 'base' });
}

/** Ответ действия над плагином: ключ сообщения и подстановки к нему. */
export interface PluginActionResult {
  ok: boolean;
  /** Ключ словаря панели — текст собирает браузер, на языке того, кто смотрит. */
  message: string;
  messageValues?: Record<string, string>;
}

/**
 * Отказ скачивания, причину которого назвала сама панель.
 *
 * Отдельный класс нужен, чтобы отличить свою причину («файл больше 150 МБ»)
 * от чужой строки из сетевой библиотеки: первую можно перевести по ключу,
 * вторую — только показать как есть.
 */
class DownloadFailure extends Error {
  constructor(
    readonly key: string,
    readonly values?: Record<string, string | number>,
  ) {
    super(key);
  }
}
