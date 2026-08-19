import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { PteroSecretsService, SECRET_KEYS } from './ptero-secrets.service';
import { pteroRawRequest, pteroRequest } from './ptero-http';

/** Запись из листинга каталога. */
export interface PteroFile {
  name: string;
  mode: string;
  size: number;
  is_file: boolean;
  is_symlink: boolean;
  mimetype: string;
  created_at: string;
  modified_at: string;
}

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

  // ---------------------------------------------------- Файлы сервера
  //
  // Маршруты сверены с routes/api-client.php панели Pterodactyl. Обратите
  // внимание на методы: rename — это PUT, а не POST, как остальные операции;
  // перепутанный метод даёт 405, а не понятную ошибку.

  /** GET /api/client/servers/{identifier}/files/list?directory=... */
  async listFiles(identifier: string, directory: string): Promise<PteroFile[]> {
    const res = await pteroRequest<{ data: { attributes: PteroFile }[] }>(
      await this.key(),
      'GET',
      `/api/client/servers/${identifier}/files/list?directory=${encodeURIComponent(directory)}`,
    );
    return res.data.map((d) => d.attributes);
  }

  /**
   * POST /api/client/servers/{identifier}/files/write?file=...
   *
   * Тело — сырое содержимое файла, а не JSON. Для .jar это единственный
   * способ положить бинарник этим маршрутом.
   */
  async writeFile(identifier: string, path: string, content: Buffer): Promise<void> {
    await pteroRawRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/files/write?file=${encodeURIComponent(path)}`,
      content,
    );
  }

  /** PUT /api/client/servers/{identifier}/files/rename — им же и переносим. */
  async renameFile(identifier: string, root: string, from: string, to: string): Promise<void> {
    await pteroRequest(await this.key(), 'PUT', `/api/client/servers/${identifier}/files/rename`, {
      root,
      files: [{ from, to }],
    });
  }

  /** POST /api/client/servers/{identifier}/files/delete */
  async deleteFiles(identifier: string, root: string, files: string[]): Promise<void> {
    await pteroRequest(await this.key(), 'POST', `/api/client/servers/${identifier}/files/delete`, {
      root,
      files,
    });
  }

  /** POST /api/client/servers/{identifier}/files/create-folder */
  async createFolder(identifier: string, root: string, name: string): Promise<void> {
    await pteroRequest(
      await this.key(),
      'POST',
      `/api/client/servers/${identifier}/files/create-folder`,
      { root, name },
    );
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
