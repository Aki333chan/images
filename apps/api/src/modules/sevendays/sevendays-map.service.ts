import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  ServiceUnavailableException,
} from '@nestjs/common';
import type { SevenDaysMapSnapshot, SevenDaysMapPois, SevenDaysMapPoi } from '@aurum/shared';
import { parseSevenDaysZones, hasZoneCommands } from '@aurum/shared';
import { SevenDaysCompanionService } from './sevendays-companion.service';

const blank = (reason: SevenDaysMapSnapshot['reason']): SevenDaysMapSnapshot => ({
  available: false,
  reason,
  info: null,
  players: [],
  claims: [],
  truncated: false,
});
const obj = (v: unknown): Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const coordinate = (n: unknown): n is number =>
  typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 1_000_000;
const text = (v: unknown): v is string => typeof v === 'string' && v.length <= 128;

export function parseMapPois(value: unknown): SevenDaysMapPois {
  const body = obj(value);
  if (body.ready !== true)
    return { available: false, reason: 'world_loading', pois: [], truncated: false };
  const input = Array.isArray(body.pois) ? body.pois : [];
  const pois: SevenDaysMapPoi[] = [];
  const ids = new Set<number>();
  for (const row of input.slice(0, 2048)) {
    const p = obj(row);
    if (
      typeof p.id !== 'number' ||
      !Number.isSafeInteger(p.id) ||
      ids.has(p.id) ||
      !text(p.name) ||
      !coordinate(p.x) ||
      !coordinate(p.z) ||
      typeof p.tier !== 'number' ||
      !Number.isInteger(p.tier) ||
      p.tier < 0 ||
      p.tier > 255 ||
      typeof p.trader !== 'boolean'
    )
      continue;
    ids.add(p.id);
    pois.push({ id: p.id, name: p.name, x: p.x, z: p.z, tier: p.tier, trader: p.trader });
  }
  return { available: true, pois, truncated: body.truncated === true || input.length > 2048 };
}

export function parseMapSnapshot(infoValue: unknown, markersValue: unknown): SevenDaysMapSnapshot {
  const info = obj(infoValue),
    markers = obj(markersValue);
  if (markers.ready !== true) return blank('world_loading');
  const size = info.blockSize,
    zoom = info.maxZoom;
  const hasTiles =
    info.available === true &&
    typeof size === 'number' &&
    Number.isInteger(size) &&
    size >= 64 &&
    size <= 512 &&
    (size & (size - 1)) === 0 &&
    typeof zoom === 'number' &&
    Number.isInteger(zoom) &&
    zoom >= 0 &&
    zoom <= 8;
  const players = Array.isArray(markers.players) ? markers.players : [];
  const claims = Array.isArray(markers.claims) ? markers.claims : [];
  return {
    available: true,
    reason: hasTiles ? undefined : 'native_map_missing',
    info: hasTiles ? { blockSize: size, maxZoom: zoom } : null,
    players: players
      .slice(0, 256)
      .map(obj)
      .filter((p) => text(p.id) && text(p.name) && coordinate(p.x) && coordinate(p.z))
      .map((p) => ({
        id: p.id as string,
        name: p.name as string,
        x: p.x as number,
        z: p.z as number,
      })),
    claims: claims
      .slice(0, 2048)
      .map(obj)
      .filter(
        (c) =>
          text(c.ownerId) &&
          text(c.owner) &&
          coordinate(c.x) &&
          coordinate(c.z) &&
          typeof c.size === 'number' &&
          Number.isInteger(c.size) &&
          c.size >= 1 &&
          c.size <= 1024,
      )
      .map((c) => ({
        ownerId: c.ownerId as string,
        owner: c.owner as string,
        x: c.x as number,
        z: c.z as number,
        size: c.size as number,
      })),
    truncated: markers.truncated === true || players.length > 256 || claims.length > 2048,
  };
}

@Injectable()
export class SevenDaysMapService {
  private readonly snapshots = new Map<string, { until: number; value: SevenDaysMapSnapshot }>();
  private readonly pending = new Map<string, Promise<SevenDaysMapSnapshot>>();
  private readonly tiles = new Map<string, { until: number; png: string | null }>();
  private activeTiles = 0;
  private readonly poiRequests = new Map<string, Promise<SevenDaysMapPois>>();
  constructor(private readonly companion: SevenDaysCompanionService) {}

  async zones(serverId: string, payload?: unknown, commandsAllowed = false) {
    let body: unknown;
    if (payload !== undefined) {
      try {
        body = parseSevenDaysZones(payload);
      } catch {
        throw new BadRequestException('invalid_zones');
      }
      if (Buffer.byteLength(JSON.stringify(body), 'utf8') > 60000)
        throw new BadRequestException('zones_payload_too_large');
      if (!commandsAllowed) {
        const next = parseSevenDaysZones(body);
        const current = parseSevenDaysZones(await this.companion.zoneRequest(serverId));
        // Protect the entire scripted zone, including bounds/enable/delete, not just command text.
        const protectedZones = (zones: typeof next.zones) =>
          zones.filter(hasZoneCommands).sort((a, b) => a.id.localeCompare(b.id));
        if (
          JSON.stringify(protectedZones(next.zones)) !==
          JSON.stringify(protectedZones(current.zones))
        )
          throw new ForbiddenException('zones_commands_permission_required');
      }
    }
    return parseSevenDaysZones(await this.companion.zoneRequest(serverId, body));
  }

  async pois(serverId: string): Promise<SevenDaysMapPois> {
    const pending = this.poiRequests.get(serverId);
    if (pending) return pending;
    if (this.poiRequests.size >= 4) throw new ServiceUnavailableException('map_busy');
    const result = this.loadPois(serverId).finally(() => this.poiRequests.delete(serverId));
    this.poiRequests.set(serverId, result);
    return result;
  }
  private async loadPois(serverId: string): Promise<SevenDaysMapPois> {
    const ping = await this.companion.ping(serverId);
    if (!ping || !ping.compatible || !ping.capabilities?.includes('map-pois'))
      return {
        available: false,
        reason: !ping ? 'mod_unavailable' : 'mod_update',
        pois: [],
        truncated: false,
      };
    return parseMapPois(await this.companion.mapRequest(serverId, '/map/pois'));
  }

  async snapshot(serverId: string): Promise<SevenDaysMapSnapshot> {
    const cached = this.snapshots.get(serverId);
    if (cached && cached.until > Date.now()) return cached.value;
    const pending = this.pending.get(serverId);
    if (pending) return pending;
    if (this.pending.size >= 8) throw new ServiceUnavailableException('map_busy');
    const promise = this.loadSnapshot(serverId)
      .then((value) => {
        if (this.snapshots.size >= 32) this.snapshots.delete(this.snapshots.keys().next().value!);
        this.snapshots.set(serverId, { value, until: Date.now() + 5000 });
        return value;
      })
      .finally(() => this.pending.delete(serverId));
    this.pending.set(serverId, promise);
    return promise;
  }
  private async loadSnapshot(serverId: string): Promise<SevenDaysMapSnapshot> {
    const ping = await this.companion.ping(serverId);
    if (!ping) return blank('mod_unavailable');
    if (!ping.compatible || !ping.capabilities?.includes('map-read')) return blank('mod_update');
    const [info, markers] = await Promise.all([
      this.companion.mapRequest(serverId, '/map/info'),
      this.companion.mapRequest(serverId, '/map/markers'),
    ]);
    return parseMapSnapshot(info, markers);
  }
  async tile(
    serverId: string,
    zoom: number,
    x: number,
    z: number,
  ): Promise<{ png: string | null }> {
    if (
      ![zoom, x, z].every(Number.isInteger) ||
      zoom < 0 ||
      zoom > 8 ||
      Math.abs(x) > 65536 ||
      Math.abs(z) > 65536
    )
      throw new BadRequestException('invalid_tile');
    const key = JSON.stringify([serverId, zoom, x, z]);
    const cached = this.tiles.get(key);
    if (cached && cached.until > Date.now()) return { png: cached.png };
    if (this.activeTiles >= 2) throw new ServiceUnavailableException('map_busy');
    this.activeTiles++;
    try {
      const snapshot = await this.snapshot(serverId);
      if (!snapshot.info || zoom > snapshot.info.maxZoom) return { png: null };
      const body = obj(await this.companion.mapRequest(serverId, `/map/tile/${zoom}/${x}/${z}`));
      let png: string | null = null;
      if (
        typeof body.png === 'string' &&
        body.png.length <= 349528 &&
        /^[A-Za-z0-9+/]*={0,2}$/.test(body.png)
      ) {
        const bytes = Buffer.from(body.png, 'base64');
        if (
          bytes.length >= 33 &&
          bytes.length <= 262144 &&
          bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) &&
          bytes.toString('ascii', 12, 16) === 'IHDR' &&
          bytes.readUInt32BE(16) === snapshot.info.blockSize &&
          bytes.readUInt32BE(20) === snapshot.info.blockSize
        )
          png = body.png;
      }
      if (this.tiles.size >= 32) this.tiles.delete(this.tiles.keys().next().value!);
      this.tiles.set(key, { png, until: Date.now() + 15000 });
      return { png };
    } finally {
      this.activeTiles--;
    }
  }
}
