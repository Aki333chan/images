/** Имена WebSocket-событий (socket.io), сервер -> клиент. */
export const WS_EVENTS = {
  /** Роль или список доступных серверов пользователя изменились —
   * клиент обязан перезапросить GET /auth/me и перерисовать навигацию. */
  PERMISSIONS_UPDATED: 'permissions.updated',
  /** Открытые тикеты изменились — клиент перезапрашивает счётчик/список. */
  TICKETS_UPDATED: 'tickets.updated',
  /**
   * Пришло личное сообщение либо диалог прочитан.
   * Адресное событие: уходит только в комнату конкретного пользователя,
   * потому что переписка приватна и сам факт её наличия — тоже.
   */
  MESSAGES_UPDATED: 'messages.updated',
} as const;

export interface PermissionsUpdatedPayload {
  reason: 'role' | 'servers' | 'deactivated';
}

export interface TicketsUpdatedPayload {
  serverId: string;
  ticketId: string;
  action: 'created' | 'message' | 'closed';
}

export interface MessagesUpdatedPayload {
  /** С кем диалог — id собеседника. */
  peerId: string;
  action: 'received' | 'read';
}
