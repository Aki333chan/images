import { BadRequestException, Injectable } from '@nestjs/common';
import type { PteroDirectoryDto, PteroFileContentDto, PteroFileDto } from '@aurum/shared';
import { MAX_EDITABLE_BYTES } from '@aurum/shared';
import { ClientApiService, type PteroFile } from '../../pterodactyl/client-api.service';
import { ServersService } from '../servers.service';
import { baseName, breadcrumbsFor, joinPath, normalizeName, normalizePath, parentOf } from './file-paths';

/**
 * Файлы сервера.
 *
 * Всё идёт через Client API одного служебного пользователя — своих учёток
 * Pterodactyl персоналу не заводим.
 *
 * ПРО РАЗМЕРЫ. Файлы ходят ЧЕРЕЗ панель, а не по подписанной ссылке прямо
 * на Wings. Подписанная ссылка живёт пятнадцать минут и работает у любого,
 * кто её получил, — то есть обходит наши права и не попадает в аудит. Плата
 * за это — файл проходит через память бэкенда, поэтому размеры ограничены.
 * Для больших данных есть бэкапы: там ссылка как раз уместна.
 */
@Injectable()
export class PteroFilesService {
  /** Больше Pterodactyl всё равно не отдаст на чтение. */
  static readonly MAX_READ_BYTES = MAX_EDITABLE_BYTES;

  /**
   * Потолок скачивания через панель — 64 МиБ.
   *
   * Не «сколько влезет»: файл целиком оказывается в памяти процесса, и
   * несколько одновременных скачиваний мирового архива положили бы панель.
   * Кому нужно больше — это уже бэкап.
   */
  static readonly MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;

  /** Потолок загрузки. Тот же довод, что и у скачивания. */
  static readonly MAX_UPLOAD_BYTES = 64 * 1024 * 1024;

  constructor(
    private readonly client: ClientApiService,
    private readonly servers: ServersService,
  ) {}

  private async identifier(serverId: string): Promise<string> {
    const server = await this.servers.getById(serverId);
    return server.pteroIdentifier;
  }

  async listDirectory(serverId: string, rawPath: string): Promise<PteroDirectoryDto> {
    const path = normalizePath(rawPath);
    const entries = await this.client.listFiles(await this.identifier(serverId), path);
    return {
      path,
      breadcrumbs: breadcrumbsFor(path),
      // Папки сверху, дальше по алфавиту: так каталог читается глазами, а не
      // перебирается. Сортируем у себя — Wings отдаёт как попало.
      entries: entries.map(toDto).sort(compareEntries),
    };
  }

  async readFile(serverId: string, rawPath: string): Promise<PteroFileContentDto> {
    const path = normalizePath(rawPath);
    if (path === '/') throw new BadRequestException('Это каталог, а не файл');

    const { content, truncated } = await this.client.readFile(
      await this.identifier(serverId),
      path,
      PteroFilesService.MAX_READ_BYTES,
    );
    return { path, content: content.toString('utf8'), truncated };
  }

  async writeFile(serverId: string, rawPath: string, content: string): Promise<{ path: string }> {
    const path = normalizePath(rawPath);
    if (path === '/') throw new BadRequestException('Это каталог, а не файл');

    const buffer = Buffer.from(content, 'utf8');
    if (buffer.length > PteroFilesService.MAX_READ_BYTES) {
      throw new BadRequestException('Файл больше разрешённого размера для правки');
    }
    await this.client.writeFile(await this.identifier(serverId), path, buffer);
    return { path };
  }

  async upload(
    serverId: string,
    rawDirectory: string,
    fileName: string,
    content: Buffer,
  ): Promise<{ path: string }> {
    if (content.length > PteroFilesService.MAX_UPLOAD_BYTES) {
      throw new BadRequestException(
        `Файл больше ${Math.round(PteroFilesService.MAX_UPLOAD_BYTES / 1024 / 1024)} МиБ — загрузите его иначе`,
      );
    }
    const path = joinPath(rawDirectory, normalizeName(fileName));
    await this.client.writeFile(await this.identifier(serverId), path, content);
    return { path };
  }

  async download(
    serverId: string,
    rawPath: string,
  ): Promise<{ name: string; content: Buffer; truncated: boolean; contentType: string }> {
    const path = normalizePath(rawPath);
    if (path === '/') throw new BadRequestException('Это каталог, а не файл');

    const res = await this.client.downloadFile(
      await this.identifier(serverId),
      path,
      PteroFilesService.MAX_DOWNLOAD_BYTES,
    );
    if (res.truncated) {
      // Отдать обрезанный файл под видом целого — худшее, что можно сделать:
      // человек не заметит, а конфиг окажется битым.
      throw new BadRequestException(
        `Файл больше ${Math.round(PteroFilesService.MAX_DOWNLOAD_BYTES / 1024 / 1024)} МиБ — скачайте его бэкапом`,
      );
    }
    return { name: baseName(path), content: res.content, truncated: false, contentType: res.contentType };
  }

  async createFolder(serverId: string, rawDirectory: string, rawName: string): Promise<{ path: string }> {
    const directory = normalizePath(rawDirectory);
    const name = normalizeName(rawName);
    await this.client.createFolder(await this.identifier(serverId), directory, name);
    return { path: joinPath(directory, name) };
  }

  /**
   * Переименование и перенос — одна операция.
   *
   * У Pterodactyl это тоже один маршрут: `to` может содержать путь, и тогда
   * файл переезжает. Разделять их в панели значило бы городить два экрана
   * над одним действием.
   */
  async move(serverId: string, rawFrom: string, rawTo: string): Promise<{ path: string }> {
    const from = normalizePath(rawFrom);
    const to = normalizePath(rawTo);
    if (from === '/' || to === '/') throw new BadRequestException('Нельзя трогать корень');
    if (from === to) throw new BadRequestException('Пути совпадают');

    // Общий корень для обоих путей — панель ждёт root плюс относительные
    // имена. Проще всего взять корень «/» и передать полные пути: так
    // перенос между любыми каталогами работает одинаково.
    await this.client.renameFile(await this.identifier(serverId), '/', from.slice(1), to.slice(1));
    return { path: to };
  }

  async remove(serverId: string, rawDirectory: string, names: string[]): Promise<{ removed: number }> {
    if (names.length === 0) throw new BadRequestException('Не выбрано ни одного файла');
    const directory = normalizePath(rawDirectory);
    const safe = names.map((n) => normalizeName(n));
    await this.client.deleteFiles(await this.identifier(serverId), directory, safe);
    return { removed: safe.length };
  }

  async compress(serverId: string, rawDirectory: string, names: string[]): Promise<PteroFileDto> {
    if (names.length === 0) throw new BadRequestException('Не выбрано ни одного файла');
    const directory = normalizePath(rawDirectory);
    const safe = names.map((n) => normalizeName(n));
    const archive = await this.client.compressFiles(await this.identifier(serverId), directory, safe);
    return toDto(archive);
  }

  async decompress(serverId: string, rawPath: string): Promise<{ path: string }> {
    const path = normalizePath(rawPath);
    if (path === '/') throw new BadRequestException('Это каталог, а не архив');
    await this.client.decompressFile(await this.identifier(serverId), parentOf(path), baseName(path));
    return { path: parentOf(path) };
  }
}

function toDto(file: PteroFile): PteroFileDto {
  return {
    name: file.name,
    mode: file.mode,
    size: file.size,
    isFile: file.is_file,
    isSymlink: file.is_symlink,
    mimetype: file.mimetype,
    modifiedAt: file.modified_at,
  };
}

/** Папки сверху, внутри группы — по имени без учёта регистра. */
function compareEntries(a: PteroFileDto, b: PteroFileDto): number {
  if (a.isFile !== b.isFile) return a.isFile ? 1 : -1;
  return a.name.localeCompare(b.name, 'ru', { sensitivity: 'base' });
}
