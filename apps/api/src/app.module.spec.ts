process.env.NODE_ENV = 'test';

import { Test } from '@nestjs/testing';
import { AppModule } from './app.module';

/**
 * Дымовой тест сборки графа зависимостей.
 *
 * Ловит класс ошибок, невидимый ни для сборки, ни для остальных тестов:
 * кольцевые импорты между файлами. В CommonJS такое кольцо не падает, а
 * возвращает undefined на полпути — и Nest уже при старте сообщает
 * «can't resolve dependencies … at index [N]» со знаком вопроса вместо
 * имени класса. Так однажды приехал 502 на боевой машине: модуль Minecraft
 * потянул ServersModule, тот — ServersController, а он — PermissionsService,
 * который сам импортирует реестр игровых модулей.
 *
 * compile() строит весь граф и создаёт провайдеры, но не вызывает
 * onModuleInit, поэтому ни база, ни Redis для теста не нужны.
 */
describe('AppModule', () => {
  it('граф зависимостей собирается целиком', async () => {
    const moduleRef = await Test.createTestingModule({ imports: [AppModule] }).compile();
    expect(moduleRef).toBeDefined();
    await moduleRef.close();
  }, 30_000);
});
