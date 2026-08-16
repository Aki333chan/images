import {
  IsBoolean,
  IsIn,
  IsInt,
  IsISO8601,
  IsObject,
  IsOptional,
  IsString,
  IsUrl,
  Max,
  Matches,
  MaxLength,
  Min,
  MinLength,
} from 'class-validator';

export class KickDto {
  @IsString()
  @MaxLength(200)
  reason!: string;
}

export class BanDto {
  @IsString()
  @MaxLength(200)
  reason!: string;

  /** null/отсутствует — бан навсегда. */
  @IsOptional()
  @IsISO8601()
  expiresAt?: string;
}

export class WhitelistAddDto {
  @IsString()
  @MinLength(3)
  @MaxLength(16)
  name!: string;
}

export class RawCommandDto {
  @IsString()
  @MinLength(1)
  @MaxLength(500)
  command!: string;
}

export class QuickCommandRunDto {
  @IsOptional()
  @IsObject()
  args?: Record<string, string>;
}

export class RconConfigDto {
  /** Приватный адрес через туннель, напр. 10.0.0.2. */
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  host!: string;

  @IsInt()
  @Min(1)
  @Max(65535)
  port!: number;

  @IsString()
  @MinLength(1)
  @MaxLength(200)
  password!: string;
}

export class CompanionConfigDto {
  /** null — отключить companion-плагин. */
  @IsOptional()
  @IsUrl({ require_tld: false, protocols: ['http', 'https'] })
  baseUrl?: string | null;

  @IsOptional()
  @IsString()
  @MaxLength(300)
  token?: string | null;
}

/**
 * Одно изменение прав игрока.
 *
 * Ноды LuckPerms — ASCII без пробелов. Проверяем это здесь, а не только в
 * плагине: панель не должна отправлять на игровой сервер заведомо негодное
 * значение, а сообщение об ошибке человеку понятнее прямо в форме.
 */
export class PermissionChangeDto {
  @IsIn(['group', 'permission'])
  kind!: 'group' | 'permission';

  @IsString()
  @MinLength(1)
  @MaxLength(120)
  @Matches(/^[A-Za-z0-9_.*:-]+$/, {
    message: 'Допустимы только латиница, цифры и символы . _ - * :',
  })
  key!: string;

  /** Знак ноды: true — выдать, false — явный запрет. По умолчанию выдать. */
  @IsOptional()
  @IsBoolean()
  value?: boolean;

  /** true — снять ноду вместо добавления. */
  @IsOptional()
  @IsBoolean()
  remove?: boolean;
}
