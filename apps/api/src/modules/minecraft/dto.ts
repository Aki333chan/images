import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayNotEmpty,
  IsArray,
  IsBoolean,
  IsIn,
  IsInt,
  IsNumber,
  IsOptional,
  IsString,
  IsUrl,
  Max,
  Matches,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
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

/**
 * Одна строка списка выдачи.
 *
 * Идентификатор здесь только проверяется на форму, а не на существование:
 * какие материалы бывают, знает игровой сервер — перечень зависит от версии
 * и установленных модов, и зашитый в панель список устарел бы к следующему
 * обновлению. Поэтому неизвестный предмет вернётся строкой результата с
 * причиной, а не отказом всего запроса.
 */
export class GiveItemDto {
  @IsString()
  @MinLength(1, { message: 'Укажите предмет' })
  @MaxLength(120)
  @Matches(/^[a-z0-9_.:/-]+$/i, {
    message: 'Идентификатор предмета — латиница, цифры и символы _ . : - /',
  })
  id!: string;

  @IsInt({ message: 'Количество — целое число' })
  @Min(1, { message: 'Количество должно быть больше нуля' })
  // 36 слотов по 64 — ровно полный инвентарь одним материалом. Всё сверх
  // всё равно вернулось бы как не поместившееся, а опечатка вроде «64000000»
  // заставила бы игровой сервер собирать миллион стаков в основном потоке.
  @Max(36 * 64, { message: 'За раз можно выдать не больше 2304 штук одного предмета' })
  count!: number;
}

export class GiveItemsDto {
  @IsArray()
  @ArrayNotEmpty({ message: 'Список пуст — нечего выдавать' })
  @ArrayMaxSize(45, { message: 'Не больше 45 строк за раз' })
  @ValidateNested({ each: true })
  @Type(() => GiveItemDto)
  items!: GiveItemDto[];
}

/**
 * Что очистить в инвентаре.
 *
 * `all` — отдельным полем, а не «пустой выбор значит всё». Разница между
 * «стереть выбранное» и «стереть весь инвентарь» необратима, и умолчание
 * здесь рано или поздно сотрёт лишнее из-за потерянного по дороге поля.
 * Пустой выбор без `all` отвергает уже плагин.
 */
export class InventoryClearDto {
  @IsOptional()
  @IsBoolean()
  all?: boolean;

  /** Слоты основного инвентаря: 0-8 хотбар, 9-35 остальное. */
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(36)
  @IsInt({ each: true })
  @Min(0, { each: true })
  @Max(35, { each: true })
  slots?: number[];

  /** Индексы брони 0-3 в порядке Bukkit: ботинки, поножи, нагрудник, шлем. */
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(4)
  @IsInt({ each: true })
  @Min(0, { each: true })
  @Max(3, { each: true })
  armor?: number[];

  @IsOptional()
  @IsBoolean()
  offhand?: boolean;
}
