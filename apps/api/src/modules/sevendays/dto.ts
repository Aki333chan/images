import {
  IsIn,
  IsInt,
  IsObject,
  IsOptional,
  IsString,
  Max,
  MaxLength,
  Min,
  MinLength,
} from 'class-validator';
import { SEVENDAYS_BAN_UNITS, type SevenDaysBanUnit } from '@aurum/shared';

/**
 * Имена классов без префикса SevenDays — как в остальных модулях: они и так
 * лежат в папке модуля, а `SevenDaysBanDto` путался бы с одноимённым типом
 * ответа из @aurum/shared.
 */

export class TelnetConfigDto {
  /**
   * Адрес консоли внутри приватного туннеля. Пустое значение стирает
   * настройку — так модуль отключают от сервера.
   */
  @IsOptional()
  @IsString()
  @MaxLength(255)
  host?: string | null;

  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(65535)
  port?: number | null;

  @IsOptional()
  @IsString()
  @MaxLength(255)
  password?: string | null;
}

/**
 * Цель команды — ник игрока, id сущности или идентификатор платформы.
 *
 * Все три принимает сама игра, и панель не выбирает за администратора:
 * ник удобнее, когда игрок в сети, а платформенный id — единственное, что
 * работает, когда он уже вышел.
 */
class TargetDto {
  @IsString()
  @MinLength(1)
  @MaxLength(128)
  target!: string;
}

export class KickDto extends TargetDto {
  @IsOptional()
  @IsString()
  @MaxLength(200)
  reason?: string;
}

export class BanDto extends TargetDto {
  /**
   * Срок. «Навсегда» в игре нет — бессрочный бан выражается большим сроком,
   * и панель не притворяется, что умеет иначе.
   */
  @IsInt()
  @Min(1)
  @Max(9999)
  duration!: number;

  @IsIn(SEVENDAYS_BAN_UNITS as unknown as string[])
  unit!: SevenDaysBanUnit;

  @IsOptional()
  @IsString()
  @MaxLength(200)
  reason?: string;
}

export class WhitelistEntryDto extends TargetDto {}

export class ActionRunDto {
  @IsOptional()
  @IsObject()
  args?: Record<string, string>;
}
