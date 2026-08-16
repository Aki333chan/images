import { IsEmail, IsString, Length, MaxLength, MinLength } from 'class-validator';

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

  @IsString()
  @MinLength(2)
  @MaxLength(31)
  nickname!: string;
}
