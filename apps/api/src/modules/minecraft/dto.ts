import {
  IsInt,
  IsISO8601,
  IsObject,
  IsOptional,
  IsString,
  IsUrl,
  Max,
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
