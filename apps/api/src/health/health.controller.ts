import { Controller, Get, ServiceUnavailableException } from '@nestjs/common';
import { Public } from '../auth/decorators';
import { HealthService } from './health.service';

/**
 * Health-check для мониторинга и деплоя.
 *
 * Роуты публичные (без JWT) и намеренно скупые на детали: наружу они торчат
 * через nginx, поэтому версий, адресов и текстов ошибок здесь быть не должно —
 * подробности пишутся в журнал systemd.
 */
@Controller('health')
export class HealthController {
  constructor(private readonly health: HealthService) {}

  /**
   * Liveness: процесс жив и отвечает. Всегда 200, если сервис поднят.
   * Именно этот роут стоит опрашивать внешним мониторингом — он не ходит
   * в БД и не «покраснеет» из-за кратковременной недоступности Postgres.
   */
  @Public()
  @Get()
  live() {
    return this.health.liveness();
  }

  /**
   * Readiness: готов ли сервис обслуживать запросы — проверяет БД и Redis.
   * 503, если что-то из зависимостей недоступно: по нему деплой понимает,
   * что переключать трафик рано.
   */
  @Public()
  @Get('ready')
  async ready() {
    const result = await this.health.readiness();
    if (!result.ready) throw new ServiceUnavailableException(result);
    return result;
  }
}
