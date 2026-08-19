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

/** Больше этого плагины практически не весят, а память панели не резиновая. */
const MAX_JAR_BYTES = 150 * 1024 * 1024;

/** Имя companion-плагина: его самого выключать и сносить нельзя. */
const COMPANION_PLUGIN = 'AurumCompanion';

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
    if (!server) throw new BadRequestException('Сервер не найден');
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
      throw new BadRequestException(`Не удалось скачать файл плагина: ${(e as Error).message}`);
    }

    if (file.hash && !verifyHash(jar, file.hash)) {
      await log(false, { error: 'хэш не совпал' });
      throw new BadRequestException(
        'Скачанный файл не совпал с контрольной суммой источника — установка отменена',
      );
    }

    const safeName = sanitizeJarName(file.fileName);
    await this.client.writeFile(identifier, `${PLUGINS_DIR}/${safeName}`, jar);

    // Состояние сервера решает только текст предупреждения: запущенный сервер
    // подхватит новый jar лишь после перезапуска, выключенный — при старте.
    const running = await this.isRunning(identifier);
    await log(true, { sizeBytes: jar.length, restartRequired: running });

    return {
      ok: true,
      fileName: safeName,
      sizeBytes: jar.length,
      restartRequired: running,
      message: running
        ? 'Плагин загружен. Сервер сейчас запущен — он заработает после перезапуска.'
        : 'Плагин загружен. Он подхватится при следующем запуске сервера.',
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
    if (res.statusCode >= 400) throw new Error(`источник ответил ${res.statusCode}`);

    const chunks: Buffer[] = [];
    let total = 0;
    for await (const chunk of res.body) {
      const buf = Buffer.from(chunk);
      total += buf.length;
      if (total > MAX_JAR_BYTES) throw new Error('файл больше 150 МБ');
      chunks.push(buf);
    }
    if (total === 0) throw new Error('источник вернул пустой файл');
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

    const plugins: InstalledPluginDto[] = (live ?? []).map((p) => ({
      name: p.name,
      version: p.version,
      state: p.enabled ? 'enabled' : 'disabled-runtime',
      fileName: guessFile(files, p.name),
      protected: p.name === COMPANION_PLUGIN,
    }));

    // Отключённые переносом сервер не видит вовсе — они есть только на диске.
    for (const fileName of disabled) {
      plugins.push({
        name: nameFromJar(fileName),
        version: null,
        state: 'disabled-file',
        fileName,
        protected: false,
      });
    }

    return {
      companionAvailable: live !== null,
      filesAvailable,
      ...(live === null
        ? { reason: 'Companion-плагин не настроен — видно только файлы, без живого состояния' }
        : {}),
      plugins,
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
  ): Promise<{ ok: boolean; message: string }> {
    this.assertNotCompanion(pluginName);
    const result = await this.companion.setPluginEnabled(serverId, pluginName, enabled);

    await this.audit.log({
      actorId,
      action: enabled ? 'minecraft.plugin.enable' : 'minecraft.plugin.disable',
      targetType: 'server',
      targetId: serverId,
      metadata: { plugin: pluginName, ok: result.ok, error: result.error ?? null },
    });

    if (!result.ok) {
      throw new BadRequestException(result.error ?? 'Не удалось переключить плагин');
    }
    return {
      ok: true,
      message: enabled
        ? `Плагин ${pluginName} включён без перезапуска`
        : `Плагин ${pluginName} выключен без перезапуска`,
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
  ): Promise<{ ok: boolean; message: string }> {
    const safeName = sanitizeJarName(fileName);
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
      message: disabled
        ? `Файл ${safeName} перенесён в ${DISABLED_PLUGINS_DIR}/ — после перезапуска плагин не загрузится`
        : `Файл ${safeName} возвращён в plugins/ — плагин загрузится при следующем запуске`,
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
  ): Promise<{ ok: boolean; message: string }> {
    if (input.pluginName) this.assertNotCompanion(input.pluginName);
    const safeName = sanitizeJarName(input.fileName);
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
          ? `Не удалось удалить файл: ${failure.error}`
          : `Файл ${safeName} не найден ни в plugins/, ни в ${DISABLED_PLUGINS_DIR}/`,
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
        ? `Плагин и его папка данных удалены. Изменения вступят в силу после перезапуска.`
        : `Файл плагина удалён${input.withData ? ' (папку данных найти не удалось)' : ''}. Изменения вступят в силу после перезапуска.`,
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

  private assertNotCompanion(pluginName: string): void {
    if (pluginName === COMPANION_PLUGIN) {
      throw new BadRequestException(
        'Это companion-плагин самой панели: выключив его, панель потеряет связь с сервером ' +
          'и включить обратно будет уже нечем. Снимайте его только вручную по SFTP.',
      );
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
function sanitizeJarName(raw: string): string {
  const base = (raw ?? '').split(/[\\/]/).pop() ?? '';
  const cleaned = base.replace(/[^A-Za-z0-9._+-]/g, '_');
  if (!cleaned.toLowerCase().endsWith('.jar')) {
    throw new BadRequestException('Файл плагина должен быть .jar');
  }
  if (cleaned.startsWith('.') || cleaned.length > 200) {
    throw new BadRequestException('Недопустимое имя файла плагина');
  }
  return cleaned;
}

/** Папка данных зовётся так же, как плагин в Bukkit. */
function sanitizeFolderName(raw: string): string {
  const cleaned = (raw ?? '').replace(/[^A-Za-z0-9._+-]/g, '_');
  if (!cleaned || cleaned.startsWith('.') || cleaned.length > 200) {
    throw new BadRequestException('Недопустимое имя папки плагина');
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
