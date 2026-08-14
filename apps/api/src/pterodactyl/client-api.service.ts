import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { PteroSecretsService, SECRET_KEYS } from './ptero-secrets.service';
import { pteroRequest } from './ptero-http';

export interface PteroResources {
  current_state: string;
  resources: {
    memory_bytes: number;
    cpu_absolute: number;
    disk_bytes: number;
    network_rx_bytes: number;
    network_tx_bytes: number;
    uptime: number;
  };
}

/**
 * Client API служебного пользователя Pterodactyl — консоль (WebSocket-токен),
 * питание, команды, статистика. Служебный пользователь должен быть добавлен
 * subuser'ом (или владельцем) на нужные сервера в Pterodactyl.
 */
@Injectable()
export class ClientApiService {
  constructor(private readonly secrets: PteroSecretsService) {}

  private async key(): Promise<string> {
    const key = await this.secrets.get(SECRET_KEYS.CLIENT_KEY);
    if (!key) {
      throw new ServiceUnavailableException(
        'Client API key Pterodactyl не настроен (PTERO_CLIENT_API_KEY)',
      );
    }
    return key;
  }

  /** GET /api/client/servers/{identifier}/resources */
  async getResources(identifier: string): Promise<PteroResources> {
    const res = await pteroRequest<{ attributes: PteroResources }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/resources`,
    );
    return res.attributes;
  }

  /** POST /api/client/servers/{identifier}/power, signal: start|stop|restart|kill */
  async sendPowerSignal(identifier: string, signal: 'start' | 'stop' | 'restart' | 'kill') {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/power`, {
      signal,
    });
  }

  /** POST /api/client/servers/{identifier}/command */
  async sendCommand(identifier: string, command: string) {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/command`, {
      command,
    });
  }

  /** GET /api/client/servers/{identifier}/websocket — токен+URL для консоли Wings. */
  async getConsoleWebsocket(identifier: string): Promise<{ token: string; socket: string }> {
    const res = await pteroRequest<{ data: { token: string; socket: string } }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/websocket`,
    );
    return res.data;
  }
}
