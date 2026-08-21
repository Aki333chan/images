import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { PteroSecretsService, SECRET_KEYS } from './ptero-secrets.service';
import { pteroRequest } from './ptero-http';

/**
 * Аллокация — «ip:port», по которому игроки заходят на сервер.
 * Поля сверены с AllocationTransformer панели Pterodactyl: alias там зовётся
 * именно `alias` (в client API — `ip_alias`), и путать их нельзя.
 */
export interface PteroAllocation {
  id: number;
  ip: string;
  alias: string | null;
  port: number;
  notes: string | null;
  assigned: boolean;
}

export interface PteroApplicationServer {
  id: number;
  uuid: string;
  identifier: string;
  name: string;
  description: string;
  node: number;
  suspended: boolean;
  /** id основной аллокации — её и показываем как адрес сервера. */
  allocation?: number;
  /** memory и disk — в МиБ, cpu — в процентах (100 = одно ядро). 0 = без лимита. */
  limits?: { memory: number; disk: number; cpu: number };
  relationships?: {
    allocations?: { data?: { attributes: PteroAllocation }[] };
  };
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

  /**
   * GET /api/application/servers с пагинацией.
   *
   * include=allocations добавляет адреса одним запросом вместо похода за
   * каждым сервером отдельно. Если ключу не хватит прав на аллокации,
   * Pterodactyl вернёт связь пустой — тогда адрес просто останется неизвестным,
   * а список серверов приедет как обычно.
   */
  async listAllServers(): Promise<PteroApplicationServer[]> {
    const apiKey = await this.key();
    const all: PteroApplicationServer[] = [];
    let page = 1;
    for (;;) {
      const res = await pteroRequest<ListResponse>(
        apiKey,
        'GET',
        `/api/application/servers?include=allocations&per_page=100&page=${page}`,
      );
      all.push(...res.data.map((d) => d.attributes));
      if (page >= res.meta.pagination.total_pages) break;
      page += 1;
    }
    return all;
  }
}

/**
 * Основная аллокация сервера — та, чей id совпадает с полем `allocation`.
 *
 * Аллокаций у сервера может быть несколько (дополнительные порты для
 * динамических карт, голосовых плагинов и прочего), и показывать человеку
 * первую попавшуюся значило бы назвать неверный адрес. Если совпадения нет,
 * берём первую: это всё равно адрес этого сервера, просто не основной.
 */
export function defaultAllocation(server: PteroApplicationServer): PteroAllocation | null {
  const list = server.relationships?.allocations?.data?.map((d) => d.attributes) ?? [];
  if (list.length === 0) return null;
  return list.find((a) => a.id === server.allocation) ?? list[0] ?? null;
}
