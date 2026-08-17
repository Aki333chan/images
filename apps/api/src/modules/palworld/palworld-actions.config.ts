import { PALWORLD_PERMISSIONS, type PalworldQuickActionArg } from '@aurum/shared';

/**
 * Быстрые действия Palworld.
 *
 * В отличие от Minecraft, здесь это НЕ шаблоны команд: у REST API Palworld
 * нет эндпоинта «выполни произвольную команду». Набор действий закрыт и
 * задан самим API, поэтому каждое действие — это конкретный эндпоинт.
 *
 * Список правится здесь: он одинаков для всех серверов Palworld и
 * версионируется вместе с кодом.
 */
export interface PalworldActionDefinition {
  id: string;
  label: string;
  description: string;
  /** Путь REST API относительно /v1/api. */
  path: string;
  permission: string;
  args: PalworldQuickActionArg[];
  destructive: boolean;
  /** Что показать в панели после успеха: тела ответа сервер не возвращает. */
  successMessage: string;
}

export const PALWORLD_ACTIONS: PalworldActionDefinition[] = [
  {
    id: 'announce',
    label: 'Объявление в чат',
    description: 'Отправляет сообщение всем игрокам на сервере',
    path: '/announce',
    permission: PALWORLD_PERMISSIONS.quickActions,
    args: [
      {
        name: 'message',
        label: 'Текст объявления',
        required: true,
        placeholder: 'Рестарт через 5 минут',
      },
    ],
    // Видят все игроки — стоит переспросить.
    destructive: true,
    successMessage: 'Объявление отправлено',
  },
  {
    id: 'save',
    label: 'Сохранить мир',
    description: 'Принудительно записывает мир на диск',
    path: '/save',
    permission: PALWORLD_PERMISSIONS.quickActions,
    args: [],
    destructive: false,
    successMessage: 'Мир сохранён',
  },
  {
    id: 'shutdown',
    label: 'Остановить с предупреждением',
    description:
      'Показывает игрокам сообщение и выключает сервер через заданное число секунд. ' +
      'В отличие от кнопки «Стоп», игроки успевают выйти сами.',
    path: '/shutdown',
    permission: PALWORLD_PERMISSIONS.shutdown,
    args: [
      {
        name: 'waittime',
        label: 'Через сколько секунд',
        required: true,
        placeholder: '60',
        kind: 'number',
      },
      {
        name: 'message',
        label: 'Что написать игрокам',
        required: false,
        placeholder: 'Сервер уходит на обновление',
      },
    ],
    destructive: true,
    successMessage: 'Остановка запланирована — игроки предупреждены',
  },
];

/** Максимум для отложенной остановки: сутки. Больше — почти наверняка опечатка. */
export const MAX_SHUTDOWN_WAIT_SECONDS = 86_400;
