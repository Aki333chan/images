import type { Role } from './roles';
import type { PermissionKey } from './permissions';
import type { GameModuleManifest } from './module-manifest';

/** Ответ GET /auth/me — единственный источник прав для фронтенда. */
export interface MeResponse {
  user: {
    id: string;
    email: string;
    displayName: string;
    role: Role;
    totpEnabled: boolean;
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
