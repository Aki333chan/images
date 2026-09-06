import { IsEmail, IsIn, IsOptional, IsString, Length, MaxLength, MinLength } from 'class-validator';
import { LOCALES, type Locale } from '@aurum/shared';

export class LoginDto {
  @IsEmail()
  email!: string;

  @IsString()
  @MinLength(1)
  password!: string;
}

export class TwoFactorDto {
  @IsString()
  twoFactorToken!: string;

  @IsString()
  @Length(6, 6)
  code!: string;
}

export class TotpEnableDto {
  @IsString()
  @Length(6, 6)
  code!: string;
}

export class TotpDisableDto {
  @IsString()
  @MinLength(1)
  password!: string;
}

/**
 * Онбординг: постоянный пароль и ник сотрудника за одно действие.
 *
 * Ник здесь — ник СОТРУДНИКА панели (для внутренних сообщений и аудита),
 * а не ник игрока в Minecraft: правила у них разные, и путать нельзя.
 */
export class OnboardingDto {
  @IsString()
  @MinLength(1)
  currentPassword!: string;

  @IsString()
  @MinLength(10)
  @MaxLength(200)
  newPassword!: string;

  /**
   * Ник нужен только при самом первом входе. После сброса пароля ГМ человек
   * попадает на тот же экран, но ник у него уже есть — и поле не приходит.
   */
  @IsOptional()
  @IsString()
  @MinLength(2)
  @MaxLength(31)
  nickname?: string;
}

/** Смена своего пароля из настроек. */
export class ChangePasswordDto {
  @IsString()
  @MinLength(1)
  currentPassword!: string;

  @IsString()
  @MinLength(10)
  @MaxLength(200)
  newPassword!: string;
}

/** Смена своего ника — с разрешения ГМ. */
export class ChangeNicknameDto {
  @IsString()
  @MinLength(2)
  @MaxLength(31)
  nickname!: string;
}

/**
 * Смена языка панели.
 *
 * null — «как в браузере»: сохранённый выбор стирается, и панель снова
 * следует за системным языком. Это не то же самое, что выбрать русский, и
 * поэтому поле не обязательное, а именно допускающее null.
 */
export class ChangeLocaleDto {
  @IsOptional()
  @IsIn([...LOCALES])
  locale?: Locale | null;
}
