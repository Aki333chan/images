import {
  IsBoolean,
  IsIn,
  IsNumber,
  IsOptional,
  IsString,
  IsUrl,
  Max,
  Matches,
  MaxLength,
  Min,
  MinLength,
} from 'class-validator';

/**
 * DTO, которые есть только у Paper: за каждым стоит companion-плагин Bukkit.
 * Общие для всего семейства Minecraft лежат в ../minecraft-shared/dto.
 */
export {
  BanDto,
  KickDto,
  QuickCommandRunDto,
  RawCommandDto,
  RconConfigDto,
  WhitelistAddDto,
} from '../minecraft-shared/dto';

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

/**
 * Начисление или списание валюты.
 *
 * Знак задаётся маршрутом (deposit/withdraw), а не значением: отрицательная
 * сумма в «начислить» означала бы списание, и в журнале аудита операция
 * выглядела бы ровно наоборот. Поэтому здесь только положительные числа.
 */
export class BalanceChangeDto {
  @IsNumber({ maxDecimalPlaces: 2 }, { message: 'Сумма — число не более чем с двумя знаками после запятой' })
  @Min(0.01, { message: 'Сумма должна быть больше нуля' })
  // Верхняя граница — не про «столько не бывает», а про опечатку: лишний
  // ноль в поле не должен приводить к необратимой выдаче.
  @Max(1_000_000_000)
  amount!: number;

  /** За что. Не обязательна для API, но именно она делает журнал полезным. */
  @IsOptional()
  @IsString()
  @MaxLength(200)
  reason?: string;
}

/**
 * Кого назначить лидером при принудительной передаче.
 *
 * Ник, а не UUID: администратор в панели видит состав гильдии именно никами, а
 * заставлять его копировать UUID ради одного клика — лишний шаг там, где
 * ошибиться легко.
 */
export class GuildTransferDto {
  @IsString()
  @MinLength(1, { message: 'Укажите ник участника' })
  @MaxLength(16)
  target!: string;
}

/** Кого исключить из его гильдии. Гильдию искать не нужно: она у игрока одна. */
export class GuildRemoveMemberDto {
  @IsString()
  @MinLength(1, { message: 'Укажите ник игрока' })
  @MaxLength(16)
  target!: string;
}
