import { Body, Controller, Get, Param, Post, Put, Req, Res } from '@nestjs/common';
import type { Request, Response } from 'express';
import {
  IsArray,
  ArrayMaxSize,
  ArrayMinSize,
  IsBoolean,
  IsIn,
  IsInt,
  IsOptional,
  IsNumber,
  IsString,
  Max,
  MaxLength,
  Min,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';
import type {
  AiPageContext,
  AiProvider,
  AiSettingsDto,
  AiStreamEvent,
  AiUsageDto,
} from '@aurum/shared';
import { AuthUser, CurrentUser } from '../auth/decorators';
import { AuditRedactBody } from '../audit/audit.decorators';
import { RequirePermission } from '../rbac/rbac.decorators';
import { AiSettingsService } from './ai-settings.service';
import { AiToolsService } from './ai-tools.service';
import { I18nService } from '../i18n/i18n.service';
import { AiService } from './ai.service';

class ChatMessageDto {
  @IsIn(['user', 'assistant'])
  role!: 'user' | 'assistant';

  @IsString()
  @MaxLength(4000)
  content!: string;
}

class PageContextDto implements AiPageContext {
  @IsOptional() @IsString() @MaxLength(64) serverId?: string;
  @IsOptional() @IsString() @MaxLength(32) tab?: string;
}

class ChatDto {
  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(20)
  @ValidateNested({ each: true })
  @Type(() => ChatMessageDto)
  messages!: ChatMessageDto[];
  @IsOptional() @ValidateNested() @Type(() => PageContextDto) context?: PageContextDto;
}

class SettingsPatchDto {
  @IsOptional() @IsIn(['deepseek', 'gemini']) provider?: AiProvider;
  @IsOptional() @IsBoolean() enabled?: boolean;
  @IsOptional() @IsString() @MaxLength(64) model?: string;
  @IsOptional() @IsString() @MaxLength(8000) systemPrompt?: string;
  @IsOptional() @IsInt() @Min(1) @Max(1000) requestsPerHour?: number;
  @IsOptional() @IsInt() @Min(1000) @Max(100_000_000) tokensPerDay?: number;
  @IsOptional() @IsInt() @Min(4096) @Max(131072) maxInputTokens?: number;
  @IsOptional() @IsInt() @Min(256) @Max(16384) maxOutputTokens?: number;
  @IsOptional() @IsNumber() @Min(0.05) @Max(1000) dailyBudgetUsd?: number;
  @IsOptional() @IsNumber() @Min(0.05) @Max(10000) monthlyBudgetUsd?: number;
  /** Пусто — не менять сохранённый ключ. */
  @IsOptional() @IsString() @MaxLength(200) apiKey?: string;
  @IsOptional() @IsBoolean() clearApiKey?: boolean;
}

class ResolveDto {
  @IsBoolean()
  approve!: boolean;
}

/**
 * AI-ассистент.
 *
 * Диалог доступен всем, у кого есть право ai.chat; настройки — только ГМ
 * (users.manage), потому что там задаётся ключ API и системный промпт.
 */
@Controller('ai')
export class AiController {
  constructor(
    private readonly ai: AiService,
    private readonly settings: AiSettingsService,
    private readonly tools: AiToolsService,
    private readonly i18n: I18nService,
  ) {}

  /**
   * Поток ответа ассистента.
   *
   * Именно POST с SSE в ответе, а не EventSource: EventSource не умеет
   * задавать заголовок Authorization, а переносить токен в query-строку
   * значит записать его в логи nginx. Браузер читает поток через fetch.
   */
  @Post('chat')
  @RequirePermission('ai.chat')
  async chat(
    @CurrentUser() user: AuthUser,
    @Body() dto: ChatDto,
    @Req() req: Request,
    @Res() res: Response,
  ): Promise<void> {
    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    // Отключает буферизацию в nginx: без этого ответ приедет целиком в конце,
    // и стриминг превратится в долгое ожидание.
    res.setHeader('X-Accel-Buffering', 'no');
    res.flushHeaders?.();

    let closed = false;
    const abort = new AbortController();
    const disconnect = () => {
      closed = true;
      abort.abort();
    };
    // IncomingMessage.close also fires after the POST body, before SSE has finished.
    res.on('close', disconnect);
    req.on('aborted', disconnect);

    const emit = (event: AiStreamEvent) => {
      if (closed) return;
      res.write(`data: ${JSON.stringify(event)}\n\n`);
    };

    try {
      // Язык панели у собеседника — запасной вариант. Основной ответ на
      // вопрос «на каком языке отвечать» даёт само сообщение, и его читает
      // модель; сюда доезжает только то, чем крыть, если сообщение — это
      // ник или одна команда.
      await this.ai.chat(
        user.id,
        dto.messages,
        emit,
        this.i18n.localeOf(req.headers['accept-language']),
        dto.context,
        abort.signal,
      );
    } catch {
      // Unexpected setup/database errors must not expose queries, infrastructure or secrets.
      emit({
        type: 'error',
        message: this.i18n.t(
          this.i18n.localeOf(req.headers['accept-language']),
          'ai.err.providerUnavailable',
        ),
      });
    } finally {
      res.off('close', disconnect);
      req.off('aborted', disconnect);
      if (!closed) res.end();
    }
  }

  /** Решение по предложенному действию: подтвердить или отклонить. */
  @Get('actions/:actionId')
  @RequirePermission('ai.chat')
  action(@CurrentUser() user: AuthUser, @Param('actionId') actionId: string, @Req() req: Request) {
    return this.ai.action(user.id, actionId, this.i18n.localeOf(req.headers['accept-language']));
  }

  @Post('actions/:actionId')
  @RequirePermission('ai.chat')
  resolve(
    @CurrentUser() user: AuthUser,
    @Param('actionId') actionId: string,
    @Body() dto: ResolveDto,
    @Req() req: Request,
  ) {
    // Карточку читает тот, кто нажал кнопку: подпись действия собирается на
    // его языке, а не на языке того, при ком её когда-то предложили.
    return this.ai.resolve(
      user.id,
      actionId,
      dto.approve,
      this.i18n.localeOf(req.headers['accept-language']),
    );
  }

  @Get('usage')
  @RequirePermission('ai.chat')
  usage(@CurrentUser() user: AuthUser): Promise<AiUsageDto> {
    return this.ai.usage(user.id);
  }

  /** Какие инструменты вообще есть — для экрана настроек. */
  @Get('tools')
  @RequirePermission('ai.chat')
  toolList() {
    return { tools: this.tools.list() };
  }

  // ---------- Настройки (ГМ) ----------

  @Get('settings')
  @RequirePermission('users.manage')
  getSettings(): Promise<AiSettingsDto> {
    return this.settings.get();
  }

  @Put('settings')
  @RequirePermission('users.manage')
  @AuditRedactBody() // в теле ключ API
  updateSettings(@Body() dto: SettingsPatchDto): Promise<AiSettingsDto> {
    return this.settings.update({
      ...(dto.provider !== undefined ? { provider: dto.provider } : {}),
      ...(dto.enabled !== undefined ? { enabled: dto.enabled } : {}),
      ...(dto.model !== undefined ? { model: dto.model } : {}),
      ...(dto.systemPrompt !== undefined ? { systemPrompt: dto.systemPrompt } : {}),
      ...(dto.requestsPerHour !== undefined ? { requestsPerHour: dto.requestsPerHour } : {}),
      ...(dto.tokensPerDay !== undefined ? { tokensPerDay: dto.tokensPerDay } : {}),
      ...(dto.maxInputTokens !== undefined ? { maxInputTokens: dto.maxInputTokens } : {}),
      ...(dto.maxOutputTokens !== undefined ? { maxOutputTokens: dto.maxOutputTokens } : {}),
      ...(dto.dailyBudgetUsd !== undefined ? { dailyBudgetUsd: dto.dailyBudgetUsd } : {}),
      ...(dto.monthlyBudgetUsd !== undefined ? { monthlyBudgetUsd: dto.monthlyBudgetUsd } : {}),
      ...(dto.clearApiKey ? { apiKey: null } : dto.apiKey ? { apiKey: dto.apiKey } : {}),
    });
  }
}
