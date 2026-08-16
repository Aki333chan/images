import { Body, Controller, Get, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { IsString, MaxLength, MinLength } from 'class-validator';
import type { ConversationDto, StaffContactDto, StaffMessageDto } from '@aurum/shared';
import { AuthUser, CurrentUser } from '../auth/decorators';
import { MessagesService } from './messages.service';

class SendMessageDto {
  @IsString()
  @MinLength(1)
  @MaxLength(31)
  nickname!: string;

  @IsString()
  @MinLength(1)
  @MaxLength(4000)
  text!: string;
}

/**
 * Внутренняя переписка сотрудников.
 *
 * Ни одного @RequirePermission: это не игровое действие, доступ есть у всех
 * ролей. Ограничение — не в правах, а в выборках: каждый видит только те
 * диалоги, в которых сам участвует.
 */
@Controller('messages')
export class MessagesController {
  constructor(private readonly messages: MessagesService) {}

  @Get('contacts')
  contacts(@CurrentUser() user: AuthUser, @Query('q') q?: string): Promise<StaffContactDto[]> {
    return this.messages.contacts(user.id, q);
  }

  @Get('unread')
  async unread(@CurrentUser() user: AuthUser): Promise<{ unread: number }> {
    return { unread: await this.messages.unreadCount(user.id) };
  }

  @Get('conversations')
  conversations(@CurrentUser() user: AuthUser): Promise<ConversationDto[]> {
    return this.messages.conversations(user.id);
  }

  @Get('thread/:peerId')
  thread(
    @CurrentUser() user: AuthUser,
    @Param('peerId', ParseUUIDPipe) peerId: string,
  ): Promise<StaffMessageDto[]> {
    return this.messages.thread(user.id, peerId);
  }

  @Post('thread/:peerId/read')
  markRead(
    @CurrentUser() user: AuthUser,
    @Param('peerId', ParseUUIDPipe) peerId: string,
  ): Promise<{ updated: number }> {
    return this.messages.markRead(user.id, peerId);
  }

  @Post()
  send(@CurrentUser() user: AuthUser, @Body() dto: SendMessageDto): Promise<StaffMessageDto> {
    return this.messages.send(user.id, dto);
  }
}
