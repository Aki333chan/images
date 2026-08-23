import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { CryptoService } from '../../common/crypto.service';
import { PrismaService } from '../../prisma/prisma.service';
import { SEVENDAYS_DEFAULT_PORT } from './telnet/telnet-client';

/**
 * Секреты модуля лежат в servers.credentials_enc (поле ядра) как
 * зашифрованный AES-256-GCM JSON — там же, где креды других модулей. Ключ
 * `sevendays` свой, поэтому смена модуля у сервера чужие настройки не портит.
 *
 * Наружу не отдаётся ничего: ни адрес, ни порт, ни пароль. Контроллер
 * возвращает только флаг «настроено / не настроено» и время последнего
 * успешного ответа. Порт telnet — такой же чувствительный порт, как RCON:
 * знание «где именно слушает консоль» само по себе половина взлома.
 */
export interface SevenDaysCredentials {
  sevendays?: {
    /** Адрес внутри приватного туннеля: 10.0.0.2, а не публичный домен. */
    host: string;
    port: number;
    password: string;
    /** Последняя успешно выполненная команда, ISO-строка. */
    lastSeenAt?: string;
  };
}

/**
 * Адрес консоли: имя хоста или IP, без схемы, пути и пробелов.
 *
 * Проверка нужна не от инъекции (адрес уходит в connect(), а не в команду),
 * а от опечатки вида «http://10.0.0.2:8081» — с ней соединение просто
 * молча не установится, и разбираться пришлось бы по таймауту.
 */
const HOST_RE = /^[A-Za-z0-9]([A-Za-z0-9.-]{0,253}[A-Za-z0-9])?$/;

@Injectable()
export class SevenDaysConfigService {
  private readonly logger = new Logger(SevenDaysConfigService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: CryptoService,
  ) {}

  async read(serverId: string): Promise<SevenDaysCredentials> {
    const server = await this.prisma.server.findUnique({
      where: { id: serverId },
      select: { credentialsEnc: true },
    });
    if (!server?.credentialsEnc) return {};
    try {
      return JSON.parse(this.crypto.decrypt(server.credentialsEnc)) as SevenDaysCredentials;
    } catch {
      // Содержимое не логируем — оно секретное.
      this.logger.error(`Не удалось расшифровать креды сервера ${serverId}`);
      return {};
    }
  }

  private async write(serverId: string, creds: SevenDaysCredentials): Promise<void> {
    await this.prisma.server.update({
      where: { id: serverId },
      data: { credentialsEnc: this.crypto.encrypt(JSON.stringify(creds)) },
    });
  }

  async setTelnet(
    serverId: string,
    host: string | null,
    port: number | null,
    password: string | null,
  ): Promise<void> {
    const current = await this.read(serverId);

    if (!host) {
      delete current.sevendays;
      await this.write(serverId, current);
      return;
    }

    if (!HOST_RE.test(host)) {
      throw new BadRequestException(
        'Адрес консоли — это имя хоста или IP без схемы и порта, например 10.0.0.2',
      );
    }
    if (!password) {
      // Пустой пароль в 7 Days to Die означает «слушать только localhost»:
      // с другой машины такое подключение не состоится в принципе, и лучше
      // сказать об этом сразу, чем показывать таймаут.
      throw new BadRequestException(
        'Нужен пароль telnet-консоли (TelnetPassword в serverconfig.xml). ' +
          'С пустым паролем сервер принимает подключения только с самого себя.',
      );
    }

    await this.write(serverId, {
      ...current,
      sevendays: {
        host,
        port: port ?? SEVENDAYS_DEFAULT_PORT,
        password,
        lastSeenAt: current.sevendays?.lastSeenAt,
      },
    });
  }

  /** Требует настроенной консоли — иначе понятная 400 вместо таймаута. */
  async require(serverId: string): Promise<NonNullable<SevenDaysCredentials['sevendays']>> {
    const creds = await this.read(serverId);
    if (!creds.sevendays) {
      throw new BadRequestException(
        'Консоль 7 Days to Die для этого сервера не настроена — задайте адрес и пароль ' +
          'в настройках модуля. На игровом сервере нужны TelnetEnabled=true и непустой ' +
          'TelnetPassword в serverconfig.xml.',
      );
    }
    return creds.sevendays;
  }

  async isConfigured(serverId: string): Promise<boolean> {
    return !!(await this.read(serverId)).sevendays;
  }

  async markSeen(serverId: string): Promise<void> {
    const current = await this.read(serverId);
    if (!current.sevendays) return;
    await this.write(serverId, {
      ...current,
      sevendays: { ...current.sevendays, lastSeenAt: new Date().toISOString() },
    });
  }
}
