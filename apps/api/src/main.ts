import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import cookieParser from 'cookie-parser';
import express from 'express';
import { AppModule } from './app.module';
import { env } from './config/env';

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
  // Лимит держим тем же, что и в PteroFilesService.MAX_UPLOAD_BYTES: файл
  // целиком оказывается в памяти процесса.
  app.use(express.raw({ type: 'application/octet-stream', limit: '64mb' }));
  app.enableCors({
    origin: env.WEB_ORIGIN,
    credentials: true,
  });
  app.useGlobalPipes(
    new ValidationPipe({ whitelist: true, transform: true, forbidNonWhitelisted: true }),
  );
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
