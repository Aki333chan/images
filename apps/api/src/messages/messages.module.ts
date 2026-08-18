import { Module } from '@nestjs/common';
import { MessagesController } from './messages.controller';
import { MessagesService } from './messages.service';

/**
 * Модуль листовой: тянет только Prisma и WS-шлюз (оба глобальные).
 * Импортов в сторону users нет намеренно — переиспользуется одна чистая
 * функция normalizeNickname, а не сервис, чтобы не плодить связей.
 */
@Module({
  controllers: [MessagesController],
  providers: [MessagesService],
  // MessagesService нужен AI-ассистенту: он отправляет ASCII-арт от имени
  // собеседника тем же кодом, что и кнопка «Отправить» в чате. Приватность
  // от этого не страдает — сообщение уходит от человека, а не от ИИ.
  exports: [MessagesService],
})
export class MessagesModule {}
