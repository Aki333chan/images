import { useEffect, type ReactNode } from 'react';
import { IconClose } from './icons';

/**
 * Общая модалка ядра. Ею пользуются все игровые модули — своей копии
 * заводить не нужно, и импортировать её из чужого модуля тоже: модули
 * не должны зависеть друг от друга.
 *
 * На мобильном раскрывается на весь экран, а не висит окошком по центру:
 * внутри бывает карточка игрока с вкладками и инвентарём, и в окне на
 * 300 px её пришлось бы читать через двойную прокрутку — страницы и окна.
 * Шапка закреплена, чтобы кнопка закрытия оставалась на виду при длинном
 * содержимом. С sm и шире — прежнее окно по центру.
 */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  // Фон под модалкой не прокручиваем — иначе на телефоне при прокрутке
  // внутри окна «уезжает» страница за ним.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex bg-black/60 sm:items-center sm:justify-center sm:p-4"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div
        className="flex w-full flex-col sm:max-h-[85vh] sm:max-w-md"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Верхний уровень высоты: у диалога тень плотнее, чем у карточки, —
            иначе на тёмном фоне он не отделяется от того, что под ним. */}
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden border-border bg-card sm:rounded-lg sm:border sm:shadow-md">
          <div
            className="flex shrink-0 items-center justify-between gap-2 border-b border-border p-3 sm:p-4"
            style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}
          >
            <h3 className="min-w-0 truncate font-semibold">{title}</h3>
            <button
              type="button"
              onClick={onClose}
              aria-label="Закрыть"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted transition-colors hover:bg-white/5 hover:text-neutral-100"
            >
              <IconClose size={16} />
            </button>
          </div>
          <div
            className="min-h-0 flex-1 overflow-y-auto p-3 sm:p-4"
            style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}
          >
            {children}
          </div>
        </div>
      </div>
    </div>
  );
}
