import { IsObject, IsOptional, IsString, IsUrl, MaxLength, MinLength } from 'class-validator';

/**
 * Имена классов без префикса Palworld — как в модуле Minecraft: они и так
 * лежат в папке модуля, а `PalworldBanDto` путался бы с одноимённым типом
 * ответа из @aurum/shared.
 */

export class ApiConfigDto {
  /**
   * Адрес REST API. Приватный: HTTPS сервер не умеет, и пароль уходит
   * в заголовке практически открытым текстом — наружу его публиковать нельзя.
   * require_tld отключён: адрес вида http://10.0.0.2:8212 доменом не является.
   */
  @IsOptional()
  @IsUrl({ require_tld: false, protocols: ['http', 'https'] })
  @MaxLength(255)
  baseUrl?: string | null;

  @IsOptional()
  @IsString()
  @MaxLength(255)
  adminPassword?: string | null;
}

export class KickDto {
  @IsString()
  @MinLength(3)
  @MaxLength(128)
  userId!: string;

  @IsOptional()
  @IsString()
  @MaxLength(200)
  reason?: string;
}

export class BanDto {
  @IsString()
  @MinLength(3)
  @MaxLength(128)
  userId!: string;

  /** Имя нужно только для списка банов: сервер оперирует userId. */
  @IsOptional()
  @IsString()
  @MaxLength(64)
  playerName?: string;

  @IsOptional()
  @IsString()
  @MaxLength(200)
  reason?: string;
}

export class ActionRunDto {
  @IsOptional()
  @IsObject()
  args?: Record<string, string>;
}
