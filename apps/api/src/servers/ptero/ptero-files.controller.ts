import {
  Body,
  Controller,
  Get,
  Header,
  Param,
  Post,
  Query,
  Req,
  Res,
  StreamableFile,
} from '@nestjs/common';
import type { Response } from 'express';
import { IsArray, IsString, MaxLength, MinLength } from 'class-validator';
import type { PteroDirectoryDto, PteroFileContentDto, PteroFileDto } from '@aurum/shared';
import { RequirePermission, ServerScoped } from '../../rbac/rbac.decorators';
import { PteroFilesService } from './ptero-files.service';

class FolderDto {
  @IsString() @MaxLength(1024) path!: string;
  @IsString() @MinLength(1) @MaxLength(255) name!: string;
}

class MoveDto {
  @IsString() @MaxLength(1024) from!: string;
  @IsString() @MaxLength(1024) to!: string;
}

class NamesDto {
  @IsString() @MaxLength(1024) path!: string;

  @IsArray()
  @IsString({ each: true })
  names!: string[];
}

class DecompressDto {
  @IsString() @MaxLength(1024) path!: string;
}

/**
 * Файловый менеджер сервера.
 *
 * Ничего игрового здесь нет: это возможность самого Pterodactyl, и работает
 * она одинаково при любом подключённом модуле.
 *
 * ПРАВА РАЗДЕЛЕНЫ НА ТРИ, а не на «смотреть/менять»:
 *   files.view   — дерево, чтение и скачивание. Модератору нужно уметь
 *                  открыть лог сервера, и это безопасно;
 *   files.manage — сохранение, папки, перенос, архивы, загрузка;
 *   files.delete — только удаление. Испорченный файл можно переписать
 *                  обратно, удалённый — только из бэкапа.
 *
 * Все мутирующие роуты попадают в audit_log автоматически: этим занимается
 * глобальный AuditInterceptor, отдельных вызовов здесь нет.
 */
@Controller('servers/:serverId/files')
export class PteroFilesController {
  constructor(private readonly files: PteroFilesService) {}

  @Get('list')
  @RequirePermission('files.view')
  @ServerScoped('serverId')
  list(
    @Param('serverId') serverId: string,
    @Query('path') path?: string,
  ): Promise<PteroDirectoryDto> {
    return this.files.listDirectory(serverId, path ?? '/');
  }

  @Get('content')
  @RequirePermission('files.view')
  @ServerScoped('serverId')
  read(
    @Param('serverId') serverId: string,
    @Query('path') path: string,
  ): Promise<PteroFileContentDto> {
    return this.files.readFile(serverId, path);
  }

  /**
   * Сохранение файла. Тело — сырое содержимое, а не JSON.
   *
   * Путь в query, а не в теле, ровно потому, что тело занято содержимым.
   */
  @Post('content')
  @RequirePermission('files.manage')
  @ServerScoped('serverId')
  write(
    @Param('serverId') serverId: string,
    @Query('path') path: string,
    @Req() req: { body?: Buffer },
  ) {
    return this.files.writeFile(serverId, path, bufferOf(req).toString('utf8'));
  }

  /** Загрузка файла в каталог. Тело — сырое содержимое. */
  @Post('upload')
  @RequirePermission('files.manage')
  @ServerScoped('serverId')
  upload(
    @Param('serverId') serverId: string,
    @Query('path') path: string,
    @Query('name') name: string,
    @Req() req: { body?: Buffer },
  ) {
    return this.files.upload(serverId, path ?? '/', name, bufferOf(req));
  }

  /**
   * Скачивание файла.
   *
   * Идёт через панель, а не подписанной ссылкой на Wings: такая ссылка
   * живёт пятнадцать минут и работает у любого, кто её получил, — то есть
   * обходит наши права и не попадает в аудит.
   */
  @Get('download')
  @RequirePermission('files.view')
  @ServerScoped('serverId')
  @Header('Cache-Control', 'no-store')
  async download(
    @Param('serverId') serverId: string,
    @Query('path') path: string,
    @Res({ passthrough: true }) res: Response,
  ): Promise<StreamableFile> {
    const file = await this.files.download(serverId, path);
    // filename* с кодировкой — иначе кириллица в имени превращается в мусор
    // либо обрывает заголовок.
    res.set({
      'Content-Type': 'application/octet-stream',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`,
    });
    return new StreamableFile(file.content);
  }

  @Post('folder')
  @RequirePermission('files.manage')
  @ServerScoped('serverId')
  createFolder(@Param('serverId') serverId: string, @Body() dto: FolderDto) {
    return this.files.createFolder(serverId, dto.path, dto.name);
  }

  /** Переименование и перенос — одно действие: у Pterodactyl это один маршрут. */
  @Post('move')
  @RequirePermission('files.manage')
  @ServerScoped('serverId')
  move(@Param('serverId') serverId: string, @Body() dto: MoveDto) {
    return this.files.move(serverId, dto.from, dto.to);
  }

  @Post('delete')
  @RequirePermission('files.delete')
  @ServerScoped('serverId')
  remove(@Param('serverId') serverId: string, @Body() dto: NamesDto) {
    return this.files.remove(serverId, dto.path, dto.names);
  }

  @Post('compress')
  @RequirePermission('files.manage')
  @ServerScoped('serverId')
  compress(@Param('serverId') serverId: string, @Body() dto: NamesDto): Promise<PteroFileDto> {
    return this.files.compress(serverId, dto.path, dto.names);
  }

  @Post('decompress')
  @RequirePermission('files.manage')
  @ServerScoped('serverId')
  decompress(@Param('serverId') serverId: string, @Body() dto: DecompressDto) {
    return this.files.decompress(serverId, dto.path);
  }
}

/**
 * Тело запроса как Buffer.
 *
 * Пустое тело — это не «пустой файл», а почти всегда забытый заголовок
 * content-type: без него express.raw тело не разберёт, и сюда придёт `{}`.
 * Молча записать пустой файл поверх конфига было бы худшим исходом.
 */
function bufferOf(req: { body?: unknown }): Buffer {
  if (Buffer.isBuffer(req.body)) return req.body;
  throw new Error(
    'Тело запроса пустое или не разобрано — файл должен уходить с заголовком ' +
      'Content-Type: application/octet-stream',
  );
}
