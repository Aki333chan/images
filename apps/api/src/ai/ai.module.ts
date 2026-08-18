import { Module } from '@nestjs/common';
import { AuditModule } from '../audit/audit.module';
import { RbacModule } from '../rbac/rbac.module';
import { ServersModule } from '../servers/servers.module';
import { MessagesModule } from '../messages/messages.module';
import { TicketsModule } from '../tickets/tickets.module';
import { MinecraftModule } from '../modules/minecraft/minecraft.module';
import { AiController } from './ai.controller';
import { AiSettingsService } from './ai-settings.service';
import { AiToolsService } from './ai-tools.service';
import { AiService } from './ai.service';
import { DeepseekClient } from './deepseek.client';

/**
 * AI-ассистент.
 *
 * Импортирует модули, чьи сервисы оборачивает инструментами: своей логики
 * у ассистента нет, он только вызывает уже существующее. Зависимость
 * направлена в одну сторону — игровые модули про ассистента не знают и
 * знать не должны, иначе получилось бы кольцо.
 *
 * TODO на будущее: когда игровых модулей станет много, регистрацию
 * инструментов стоит перенести в манифест модуля (как capabilities), чтобы
 * ассистент не импортировал каждый модуль поимённо.
 */
@Module({
  imports: [RbacModule, AuditModule, ServersModule, TicketsModule, MessagesModule, MinecraftModule],
  controllers: [AiController],
  providers: [AiSettingsService, AiToolsService, AiService, DeepseekClient],
})
export class AiModule {}
