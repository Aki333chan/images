import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import cookieParser from 'cookie-parser';
import { AppModule } from './app.module';
import { env } from './config/env';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);
  app.setGlobalPrefix('api');
  app.use(cookieParser());
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
