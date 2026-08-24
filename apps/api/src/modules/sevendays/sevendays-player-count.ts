import { Injectable, OnModuleInit } from '@nestjs/common';
import {
  PlayerCountRegistry,
  type PlayerCountProvider,
} from '../../servers/metrics/player-count.registry';
import { SevenDaysService } from './sevendays.service';

/**
 * Счётчик игроков 7 Days to Die.
 *
 * max остаётся null: команда `lp` отдаёт только число тех, кто в игре сейчас,
 * а потолок сервер наружу не сообщает вовсе. Подставлять сюда что-нибудь
 * правдоподобное нельзя — на карточке это выглядело бы как настоящий лимит.
 */
@Injectable()
export class SevenDaysPlayerCount implements PlayerCountProvider, OnModuleInit {
  constructor(
    private readonly registry: PlayerCountRegistry,
    private readonly sevendays: SevenDaysService,
  ) {}

  onModuleInit(): void {
    this.registry.register('sevendays', this);
  }

  async count(serverId: string): Promise<{ online: number; max: number | null }> {
    const players = await this.sevendays.getPlayers(serverId);
    return { online: players.online, max: null };
  }
}
