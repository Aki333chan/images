/** Имена WebSocket-событий (socket.io), сервер -> клиент. */
export const WS_EVENTS = {
  /** Роль или список доступных серверов пользователя изменились —
   * клиент обязан перезапросить GET /auth/me и перерисовать навигацию. */
  PERMISSIONS_UPDATED: 'permissions.updated',
  /** Открытые тикеты изменились — клиент перезапрашивает счётчик/список. */
  TICKETS_UPDATED: 'tickets.updated',
} as const;

export interface PermissionsUpdatedPayload {
  reason: 'role' | 'servers' | 'deactivated';
}

export interface TicketsUpdatedPayload {
  serverId: string;
  ticketId: string;
  action: 'created' | 'message' | 'closed';
}
