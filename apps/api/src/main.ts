import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import cookieParser from 'cookie-parser';
import express from 'express';
import { AppModule } from './app.module';
import { MAX_TRANSFER_BYTES } from '@aurum/shared';
import { env } from './config/env';
import { I18nExceptionFilter } from './i18n/i18n.filter';
import { I18nService } from './i18n/i18n.service';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);
  app.setGlobalPrefix('api');
  app.use(cookieParser());

  // Сырое тело для файловых операций.
  //
  // Содержимое файла не заворачивается в JSON намеренно: JSON раздул бы
  // бинарник экранированием, а разбор мегабайтной строки — это лишняя
  // работа на ровном месте. Правило по content-type, а не по пути: так
  // никакой новый файловый роут не окажется случайно без разбора тела.
  //
  // Лимит берётся из общего MAX_TRANSFER_BYTES, а не пишется числом рядом.
  // Раньше он был задан здесь строкой '64mb', в сервисе — своей константой, а
  // в nginx — третьим числом; они разошлись, и загрузка падала голым 413 от
  // nginx, до которого панель не доходила и объяснить ничего не могла.
  app.use(express.raw({ type: 'application/octet-stream', limit: MAX_TRANSFER_BYTES }));
  app.enableCors({
    origin: env.WEB_ORIGIN,
    credentials: true,
  });
  app.useGlobalPipes(
    new ValidationPipe({ whitelist: true, transform: true, forbidNonWhitelisted: true }),
  );
  // Переводит текст ошибки на язык того, кто её увидит. Ставится ПОСЛЕ
  // ValidationPipe, потому что ловит в том числе её сообщения о полях.
  app.useGlobalFilters(new I18nExceptionFilter(app.get(I18nService)));
  // Доверяем X-Forwarded-For только от перечисленных прокси. Без этого за
  // nginx все сессии выглядели бы пришедшими с 127.0.0.1, а доверять
  // заголовку от кого попало нельзя — его легко подделать.
  if (env.TRUST_PROXY) {
    app.set('trust proxy', env.TRUST_PROXY.split(',').map((ip) => ip.trim()));
  }

  app.enableShutdownHooks();
  await app.listen(env.API_PORT, env.API_BIND);
  Logger.log(`API слушает ${env.API_BIND}:${env.API_PORT} (префикс /api)`, 'Bootstrap');
}

void bootstrap();
