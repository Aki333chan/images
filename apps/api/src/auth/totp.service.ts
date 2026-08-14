import { Injectable } from '@nestjs/common';
import { authenticator } from 'otplib';
import { CryptoService } from '../common/crypto.service';

@Injectable()
export class TotpService {
  constructor(private readonly crypto: CryptoService) {}

  generateSecret(): string {
    return authenticator.generateSecret();
  }

  buildOtpAuthUrl(email: string, secret: string): string {
    return authenticator.keyuri(email, 'Aurum Panel', secret);
  }

  verify(code: string, secret: string): boolean {
    return authenticator.verify({ token: code, secret });
  }

  encryptSecret(secret: string): string {
    return this.crypto.encrypt(secret);
  }

  decryptSecret(enc: string): string {
    return this.crypto.decrypt(enc);
  }
}
