import { SEVENDAYS_PERMISSIONS } from '@aurum/shared';

/**
 * Быстрые действия 7 Days to Die.
 *
 * Набор намеренно короткий. Произвольную команду в этой игре выполнить
 * можно — консоль есть у ядра, — поэтому сюда попадает только то, что
 * делают часто и хочется в одно нажатие, и только команды, существующие
 * в самой игре без модов. Соблазн добавить «сделать день», «поменять
 * погоду» и прочее был, но это уже вмешательство в игру, а не
 * администрирование, и в один клик такому не место.
 *
 * `id` — то, что уходит в роут; `template` — команда с подстановкой
 * аргументов по имени. Подстановка идёт через arg(), то есть в кавычках и
 * с проверкой, а не простой склейкой.
 */
export interface SevenDaysActionDefinition {
  id: string;
  label: string;
  description: string;
  /** Команда консоли; {name} заменяется на объявленный аргумент. */
  template: string;
  permission: string;
  args: { name: string; label: string; placeholder?: string; required: boolean }[];
  destructive: boolean;
  successMessage: string;
}

export const SEVENDAYS_ACTIONS: SevenDaysActionDefinition[] = [
  {
    id: 'announce',
    label: 'Объявление в чат',
    description: 'Отправляет сообщение всем игрокам на сервере',
    template: 'say {message}',
    permission: SEVENDAYS_PERMISSIONS.quickActions,
    args: [
      {
        name: 'message',
        label: 'Текст объявления',
        required: true,
        placeholder: 'Рестарт через 5 минут',
      },
    ],
    // Видят все игроки сразу — стоит переспросить.
    destructive: true,
    successMessage: 'Объявление отправлено',
  },
  {
    id: 'save',
    label: 'Сохранить мир',
    description: 'Принудительно записывает мир на диск',
    template: 'saveworld',
    permission: SEVENDAYS_PERMISSIONS.quickActions,
    args: [],
    destructive: false,
    successMessage: 'Мир сохранён',
  },
  {
    id: 'shutdown',
    label: 'Остановить сервер',
    description:
      'Останавливает сервер штатно: мир сохраняется, игроков выкидывает. ' +
      'Отложенной остановки в 7 Days to Die нет — предупредить игроков ' +
      'нужно объявлением заранее.',
    template: 'shutdown',
    permission: SEVENDAYS_PERMISSIONS.shutdown,
    args: [],
    destructive: true,
    successMessage: 'Сервер останавливается',
  },
];
