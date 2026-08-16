import type { Role } from './roles';
import type { PermissionKey } from './permissions';
import type { GameModuleManifest } from './module-manifest';

/** Ответ GET /auth/me — единственный источник прав для фронтенда. */
export interface MeResponse {
  user: {
    id: string;
    email: string;
    displayName: string;
    /** Ник сотрудника панели; null — онбординг ещё не пройден. */
    nickname: string | null;
    role: Role;
    totpEnabled: boolean;
    /**
     * true — вход был по одноразовому паролю. Пока не пройден онбординг,
     * остальные разделы панели недоступны.
     */
    mustChangePassword: boolean;
  };
  permissions: PermissionKey[];
  /** null — доступ ко всем серверам (OWNER или незскоупленная роль). */
  allowedServerIds: string[] | null;
}

export interface LoginResponse {
  twoFactorRequired?: boolean;
  /** Короткоживущий токен для второго шага логина. */
  twoFactorToken?: string;
  accessToken?: string;
  me?: MeResponse;
}

export interface SessionDto {
  id: string;
  userAgent: string | null;
  ip: string | null;
  createdAt: string;
  lastUsedAt: string;
  current: boolean;
}

export interface ServerDto {
  id: string;
  pteroIdentifier: string;
  name: string;
  description: string | null;
  node: string | null;
  status: string | null;
  moduleId: string | null;
}

export interface UserAdminDto {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  isActive: boolean;
  totpEnabled: boolean;
  serverIds: string[];
  createdAt: string;
}

export interface TicketMessage {
  text: string;
  /** 'player' | id пользователя панели | 'ai' */
  from: string;
  created_at: string;
}

export interface TicketDto {
  id: string;
  serverId: string;
  serverName?: string;
  playerUuid: string;
  playerNameCached: string;
  status: 'OPEN' | 'CLOSED';
  messages: TicketMessage[];
  createdAt: string;
  updatedAt: string;
}

export interface AuditLogDto {
  id: string;
  actorId: string | null;
  actorEmail?: string | null;
  actorType: 'user' | 'ai';
  action: string;
  targetType: string | null;
  targetId: string | null;
  metadata: unknown;
  createdAt: string;
}

export interface ModulesResponse {
  enabled: GameModuleManifest[];
}

/** Потребление ресурсов сервера — данные Pterodactyl, без игровой специфики. */
export interface ServerResourcesDto {
  /** running / offline / starting / stopping. */
  state: string;
  cpuPercent: number;
  memoryBytes: number;
  /** Лимит из настроек сервера в Pterodactyl; 0 — без ограничения. */
  memoryLimitBytes: number;
  diskBytes: number;
  diskLimitBytes: number;
  networkRxBytes: number;
  networkTxBytes: number;
  /** Аптайм в миллисекундах. */
  uptimeMs: number;
}

/**
 * История онлайна по часам. Сервер отдаёт плоский список замеров во
 * времени UTC, а раскладку по суткам и часам делает клиент — только он
 * знает часовой пояс смотрящего. Раскладывать на сервере нельзя: сдвиг
 * готовой сетки на несколько часов срезал бы данные по краям.
 */
export interface ServerActivityDto {
  /** За сколько суток вернули замеры. */
  days: number;
  /** Максимум онлайна за период — по нему нормируется цвет. */
  peak: number;
  samples: ServerActivitySampleDto[];
}

export interface ServerActivitySampleDto {
  /** Начало часа в UTC, ISO-строка. */
  bucket: string;
  /** Пик онлайна за этот час. */
  online: number;
}

// ---------------------------------------------------------- Учётные записи

export type UserStatusDto = 'active' | 'pending_approval' | 'rejected';

/** Заявка на аккаунт, созданная Админом и ждущая решения ГМ. */
export interface PendingUserDto {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  createdAt: string;
  /** Кто подал заявку. */
  createdBy: { id: string; displayName: string; nickname: string | null } | null;
}

/** Что вернуть после создания аккаунта — по этому фронт понимает, что сказать. */
export interface CreateUserResultDto {
  user: UserAdminDto;
  /** true — аккаунт активен, письмо с паролем отправлено. */
  activated: boolean;
  /** false — SMTP не настроен либо письмо не ушло; пароль нужно передать лично. */
  emailSent: boolean;
  /** Причина, если письмо не ушло. Самого пароля здесь нет никогда. */
  emailError?: string;
}

// ------------------------------------------------------------- Настройки ГМ

export interface AppSettingsDto {
  /**
   * Пока включено, аккаунт, созданный Админом, ждёт подтверждения ГМ.
   * Выключено — активируется сразу, как будто его создал ГМ.
   */
  requireGmApprovalForAdminCreatedAccounts: boolean;
}

/** Настройки SMTP. Пароль наружу не отдаётся никогда — только флаг. */
export interface SmtpSettingsDto {
  configured: boolean;
  host: string;
  port: number;
  secure: boolean;
  user: string;
  from: string;
  /** true — пароль сохранён; само значение не возвращается. */
  hasPassword: boolean;
}

export interface SmtpTestResultDto {
  ok: boolean;
  /** Текст ошибки от SMTP-сервера, если не удалось. */
  error?: string;
}

// ------------------------------------------------- Внутренние сообщения

export interface StaffMessageDto {
  id: string;
  fromUserId: string;
  toUserId: string;
  text: string;
  readAt: string | null;
  createdAt: string;
}

/** Строка списка диалогов. */
export interface ConversationDto {
  peer: { id: string; displayName: string; nickname: string | null };
  lastMessage: { text: string; createdAt: string; outgoing: boolean };
  unread: number;
}

/** Сотрудник для автодополнения при отправке сообщения. */
export interface StaffContactDto {
  id: string;
  nickname: string;
  displayName: string;
}
