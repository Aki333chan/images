import { DynamicModule, Module } from '@nestjs/common';
import { ModulesController } from './modules.controller';
import { getEnabledModules } from './module-registry';

/**
 * Динамическое монтирование игровых модулей: NestJS-модули всех включённых
 * в modules.config.ts модулей импортируются при старте. Выключенный модуль
 * не монтируется вовсе — его роуты отвечают 404, данные в БД не трогаются.
 */
@Module({})
export class GameModulesModule {
  static forRoot(): DynamicModule {
    const enabled = getEnabledModules();
    return {
      module: GameModulesModule,
      imports: enabled.flatMap((m) => (m.nestModule ? [m.nestModule] : [])),
      controllers: [ModulesController],
    };
  }
}
