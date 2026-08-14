import { IsEmail, IsString, Length, MinLength } from 'class-validator';

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
