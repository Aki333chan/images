import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { PteroSecretsService, SECRET_KEYS } from './ptero-secrets.service';
import { pteroRequest } from './ptero-http';

export interface PteroApplicationServer {
  id: number;
  uuid: string;
  identifier: string;
  name: string;
  description: string;
  node: number;
  suspended: boolean;
  /** memory и disk — в МиБ, cpu — в процентах (100 = одно ядро). 0 = без лимита. */
  limits?: { memory: number; disk: number; cpu: number };
}

interface ListResponse {
  data: { attributes: PteroApplicationServer }[];
  meta: { pagination: { current_page: number; total_pages: number } };
}

/**
 * Application API (полный доступ) — используется ТОЛЬКО на бэкенде
 * для зеркалирования списка серверов. Ключ хранится зашифрованным в БД.
 */
@Injectable()
export class ApplicationApiService {
  constructor(private readonly secrets: PteroSecretsService) {}

  private async key(): Promise<string> {
    const key = await this.secrets.get(SECRET_KEYS.APP_KEY);
    if (!key) {
      throw new ServiceUnavailableException(
        'Application API key Pterodactyl не настроен (PTERO_APP_API_KEY)',
      );
    }
    return key;
  }

  /** GET /api/application/servers с пагинацией. */
  async listAllServers(): Promise<PteroApplicationServer[]> {
    const apiKey = await this.key();
    const all: PteroApplicationServer[] = [];
    let page = 1;
    for (;;) {
      const res = await pteroRequest<ListResponse>(
        apiKey,
        'GET',
        `/api/application/servers?per_page=100&page=${page}`,
      );
      all.push(...res.data.map((d) => d.attributes));
      if (page >= res.meta.pagination.total_pages) break;
      page += 1;
    }
    return all;
  }
}
