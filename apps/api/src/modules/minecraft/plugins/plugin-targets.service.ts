import { ForbiddenException, Injectable } from '@nestjs/common';
import type { ServerTargetDto } from '@aurum/shared';
import { PrismaService } from '../../../prisma/prisma.service';
import type { EffectivePermissions } from '../../../rbac/permissions.service';
import { ClientApiService } from '../../../pterodactyl/client-api.service';
import { MinecraftService } from '../minecraft.service';

/**
 * Серверы, на которые можно поставить плагин, и то, что о них известно для
 * бейджа совместимости: версия игры, ядро и текущее состояние.
 *
 * Отдельный сервис, потому что задача здесь ровно одна и она стыковочная:
 * собрать в одном месте данные из базы панели, из Pterodactyl и с самого
 * игрового сервера. Раскидывать это по маркету и установщику значило бы
 * повторить одно и то же дважды.
 */
@Injectable()
export class PluginTargetsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly client: ClientApiService,
    private readonly minecraft: MinecraftService,
  ) {}

  /**
   * Только серверы с модулем Minecraft: плагины Bukkit больше никуда не
   * ставятся, а предлагать Palworld в списке — вводить человека в заблуждение.
   */
  async listForUser(eff: EffectivePermissions): Promise<ServerTargetDto[]> {
    const servers = await this.prisma.server.findMany({
      where: {
        moduleId: 'minecraft',
        ...(eff.allowedServerIds === null ? {} : { id: { in: [...eff.allowedServerIds] } }),
      },
      orderBy: { name: 'asc' },
    });

    // Параллельно: каждый сервер опрашивается независимо, и молчание одного
    // не должно задерживать список целиком.
    return Promise.all(servers.map((s) => this.describe(s.id, s.name, s.pteroIdentifier)));
  }

  /**
   * Один сервер — с проверкой доступа.
   *
   * Проверка здесь обязательна: маршрут версий не помечен @ServerScoped
   * (он про маркет, а не про сервер), и без неё по чужому serverId можно было
   * бы узнать версию и состояние недоступного сервера.
   */
  async forUser(eff: EffectivePermissions, serverId: string): Promise<ServerTargetDto> {
    if (eff.allowedServerIds !== null && !eff.allowedServerIds.has(serverId)) {
      throw new ForbiddenException('Нет доступа к этому серверу');
    }

    const server = await this.prisma.server.findUnique({ where: { id: serverId } });
    if (!server) throw new ForbiddenException('Сервер не найден');
    return this.describe(server.id, server.name, server.pteroIdentifier);
  }

  private async describe(
    serverId: string,
    name: string,
    identifier: string,
  ): Promise<ServerTargetDto> {
    const [status, versionInfo] = await Promise.all([
      this.client
        .getResources(identifier)
        .then((r) => r.current_state)
        .catch(() => null),
      this.detectVersion(serverId, identifier),
    ]);

    return { serverId, name, status, ...versionInfo };
  }

  /**
   * Версия игры и ядро — сначала у самого сервера, потом у Pterodactyl.
   *
   * СНАЧАЛА RCON: в эгге может быть указано что угодно, а «version» отвечает
   * то, что реально запущено.
   *
   * ПОТОМ ЗАПУСК — и это не подстраховка «на всякий случай», а единственный
   * способ узнать про Forge и Fabric. Команды `version` в ванильном
   * Minecraft нет: её добавляет Bukkit. На Fabric-сервере RCON на неё ответит
   * «Unknown command», и распознать загрузчик по ответу невозможно в принципе.
   * А знать его надо: от этого зависит, какая вкладка маркета откроется и в
   * какую папку ляжет файл.
   *
   * Не ответило ничего — возвращаем null, и бейдж честно скажет «не с чем
   * сравнивать» вместо того, чтобы угадывать.
   */
  private async detectVersion(
    serverId: string,
    identifier: string,
  ): Promise<{ gameVersion: string | null; loader: string | null }> {
    const output = await this.minecraft.runCommand(serverId, 'version').catch(() => null);
    const fromRcon = output ? parseVersionOutput(output) : { gameVersion: null, loader: null };
    if (fromRcon.loader) return fromRcon;

    const startup = await this.client.getStartup(identifier).catch(() => null);
    if (!startup) return fromRcon;

    // Всё, где вообще может встретиться имя загрузчика: строка запуска,
    // образ и значения переменных эгга (там живёт SERVER_JARFILE).
    const haystack = [
      startup.meta?.startup_command ?? '',
      startup.meta?.raw_startup_command ?? '',
      ...Object.keys(startup.meta?.docker_images ?? {}),
      ...Object.values(startup.meta?.docker_images ?? {}),
      ...startup.variables.map((v) => `${v.env_variable} ${v.server_value ?? ''}`),
    ].join(' ');

    return { gameVersion: fromRcon.gameVersion, loader: loaderFromStartup(haystack) };
  }
}

/**
 * Загрузчик по строке запуска сервера.
 *
 * Порядок проверок важен: NeoForge содержит в себе слово «forge», и наивный
 * поиск объявил бы NeoForge-сервер обычным Forge. Quilt так же тянет за собой
 * Fabric — он собран на его API и обычно упоминает оба имени.
 *
 * Экспортируется ради тестов: это разбор чужой строки, он ломается тихо.
 */
export function loaderFromStartup(raw: string): string | null {
  const value = raw.toLowerCase();
  for (const loader of ['neoforge', 'quilt', 'fabric', 'forge', 'purpur', 'folia', 'paper', 'spigot']) {
    if (value.includes(loader)) return loader;
  }
  return null;
}

/**
 * Разбор ответа команды `version`.
 *
 * Формат отличается у ядер, поэтому ищем не позицию, а признаки:
 *   Paper:   «This server is running Paper version 1.21.4-40-main (MC: 1.21.4)»
 *   Spigot:  «This server is running CraftBukkit version 1.20.1-R0.1-SNAPSHOT (MC: 1.20.1)»
 *   Purpur:  «This server is running Purpur version 1.21.4-2245 (MC: 1.21.4)»
 *
 * Экспортируется ради тестов: разбор чужого текста ломается тихо, и проверять
 * его надо без запущенного сервера.
 */
export function parseVersionOutput(output: string): {
  gameVersion: string | null;
  loader: string | null;
} {
  // «(MC: 1.21.4)» — самая надёжная часть: её печатают все ядра на Bukkit.
  const mc = /\(MC:\s*([0-9][0-9._-]*)\)/i.exec(output);
  // Запасной вариант: «version 1.21.4-40» в начале строки.
  const fallback = /running\s+\S+\s+version\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)/i.exec(output);
  const gameVersion = mc?.[1] ?? fallback?.[1] ?? null;

  const known = ['purpur', 'folia', 'paper', 'spigot', 'craftbukkit', 'bukkit'];
  const lower = output.toLowerCase();
  const found = known.find((k) => lower.includes(k)) ?? null;

  return {
    gameVersion,
    // CraftBukkit в ответе означает Spigot: голый CraftBukkit давно не собирают.
    loader: found === 'craftbukkit' ? 'spigot' : found,
  };
}
