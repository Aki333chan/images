import { Controller, Get } from '@nestjs/common';
import type { ModulesResponse } from '@aurum/shared';
import { getEnabledManifests } from './module-registry';

@Controller('modules')
export class ModulesController {
  /** Манифесты включённых модулей — фронт строит по ним вкладки/меню. */
  @Get()
  list(): ModulesResponse {
    return { enabled: getEnabledManifests() };
  }
}
