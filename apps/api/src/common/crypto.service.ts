import { Injectable } from '@nestjs/common';
import { createCipheriv, createDecipheriv, randomBytes } from 'crypto';
import { env } from '../config/env';

/**
 * Шифрование секретов at rest: AES-256-GCM.
 * Формат хранения: base64(iv[12]) . base64(tag[16]) . base64(ciphertext), через точки.
 */
@Injectable()
export class CryptoService {
  private readonly key: Buffer;

  constructor() {
    this.key = Buffer.from(env.APP_ENCRYPTION_KEY, 'base64');
    if (this.key.length !== 32) {
      throw new Error('APP_ENCRYPTION_KEY должен быть 32 байта в base64 (openssl rand -base64 32)');
    }
  }

  encrypt(plaintext: string): string {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    const enc = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    return `${iv.toString('base64')}.${tag.toString('base64')}.${enc.toString('base64')}`;
  }

  decrypt(stored: string): string {
    const [ivB64, tagB64, dataB64] = stored.split('.');
    if (!ivB64 || !tagB64 || !dataB64) throw new Error('Некорректный формат зашифрованного значения');
    const decipher = createDecipheriv('aes-256-gcm', this.key, Buffer.from(ivB64, 'base64'));
    decipher.setAuthTag(Buffer.from(tagB64, 'base64'));
    return Buffer.concat([
      decipher.update(Buffer.from(dataB64, 'base64')),
      decipher.final(),
    ]).toString('utf8');
  }
}
