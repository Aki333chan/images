import { Module } from '@nestjs/common';
import { MinecraftSharedModule } from '../minecraft-shared/minecraft-shared.module';
import { MinecraftForgeController, MinecraftNeoForgeController } from './loader.controller';

/**
 * Один NestJS-модуль на оба загрузчика.
 *
 * ЭТО НЕ ПРОТИВОРЕЧИТ ТОМУ, ЧТО МОДУЛЯ ПАНЕЛИ ДВА. Игровых модуля в реестре
 * действительно два — с разными id, названиями, префиксами роутов и, главное,
 * раздельными правами: доступ к Forge-серверу не выдаётся правом от
 * NeoForge-сервера. Разделено ровно то, что должно быть разделено.
 *
 * А вот заводить два одинаковых контейнера зависимостей ради двух
 * контроллеров, которые дергают один и тот же сервис, смысла нет: это дало бы
 * два экземпляра пула RCON-соединений и ничего больше. Nest всё равно
 * дедуплицирует импорт по ссылке на класс, поэтому оба игровых модуля
 * указывают на этот один.
 */
@Module({
  imports: [MinecraftSharedModule],
  controllers: [MinecraftForgeController, MinecraftNeoForgeController],
})
export class MinecraftLoaderModule {}
