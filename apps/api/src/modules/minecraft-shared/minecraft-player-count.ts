import { Injectable, OnModuleInit } from '@nestjs/common';
import {
  PlayerCountRegistry,
  type PlayerCountProvider,
} from '../../servers/metrics/player-count.registry';
import { VanillaRconService } from './vanilla-rcon.service';

/**
 * Счётчик игроков для всего семейства Minecraft: Paper, Forge и NeoForge.
 *
 * Один на три модуля, потому что считает его команда `list` самого сервера
 * игры — она одинакова на любом ядре и любом загрузчике. Регистрируется
 * трижды, по разу на id модуля: реестр ядра ищет провайдер именно по нему.
 *
 * Companion-плагин здесь намеренно НЕ используется, хотя на Paper он есть и
 * знает больше: списку серверов нужно одно число, а лишний поход к плагину
 * на каждом тике крона по каждому серверу — это трафик ради данных, которые
 * всё равно не показываются.
 */
@Injectable()
export class MinecraftPlayerCount implements PlayerCountProvider, OnModuleInit {
  constructor(
    private readonly registry: PlayerCountRegistry,
    private readonly vanilla: VanillaRconService,
  ) {}

  onModuleInit(): void {
    for (const moduleId of ['minecraft', 'minecraft-forge', 'minecraft-neoforge']) {
      this.registry.register(moduleId, this);
    }
  }

  async count(serverId: string): Promise<{ online: number; max: number | null }> {
    const players = await this.vanilla.getPlayers(serverId);
    return { online: players.online, max: players.max };
  }
}
