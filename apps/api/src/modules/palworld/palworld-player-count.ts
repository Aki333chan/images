import { Injectable, OnModuleInit } from '@nestjs/common';
import {
  PlayerCountRegistry,
  type PlayerCountProvider,
} from '../../servers/metrics/player-count.registry';
import { PalworldService } from './palworld.service';

/** Счётчик игроков Palworld: онлайн и потолок из метрик REST API игры. */
@Injectable()
export class PalworldPlayerCount implements PlayerCountProvider, OnModuleInit {
  constructor(
    private readonly registry: PlayerCountRegistry,
    private readonly palworld: PalworldService,
  ) {}

  onModuleInit(): void {
    this.registry.register('palworld', this);
  }

  async count(serverId: string): Promise<{ online: number; max: number | null }> {
    const players = await this.palworld.getPlayers(serverId);
    return { online: players.online, max: players.max };
  }
}
