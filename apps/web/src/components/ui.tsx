/**
 * Базовые элементы интерфейса.
 *
 * Внешний вид — из дизайн-системы Nocturne: радиусы 4/8/14, волосяная рамка
 * вместо тени, акцент-блёрпл, Inter. Цвета берутся ТОЛЬКО через ролевые
 * классы Tailwind (bg-card, text-muted, border-border): перекраска системы —
 * это правка tailwind.config.ts, а не поиск шестнадцатеричных литералов по
 * всему проекту.
 *
 * ПРО РАЗМЕР ШРИФТА В ПОЛЯХ ВВОДА. У Input, Textarea и Select на мобильном
 * стоит text-base (16 px), и уменьшать его до text-sm можно только начиная
 * с sm:. Причина не в читаемости: Safari и Chrome на iOS автоматически
 * увеличивают масштаб всей страницы, когда фокус попадает в поле со шрифтом
 * мельче 16 px. Внешне это выглядит как «поехала вёрстка и запрыгали
 * размеры шрифта» — причём после расфокуса масштаб не возвращается.
 * Ровно поэтому здесь 16 px, и менять это, не проверив на iPhone, не стоит.
 *
 * ПРО ВЫСОТУ КНОПОК И ПОЛЕЙ. 44 px на мобильном — не «с запасом», а нижняя
 * граница, при которой в цель попадают пальцем с первого раза. На десктопе,
 * где целятся мышью, вёрстка плотнее — отсюда пары вида h-11 sm:h-9.
 */
import {
  forwardRef,
  useLayoutEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '../lib/cn';

type ButtonVariant = 'default' | 'outline' | 'ghost' | 'destructive';

/**
 * Общая рамка фокуса. Кольцо, а не подмена рамки: рамка у поля уже занята
 * состоянием (обычное / с ошибкой), и если фокус будет менять её же, эти два
 * состояния станут неразличимы.
 */
const FOCUS_RING =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/45 focus-visible:ring-offset-2 focus-visible:ring-offset-background';

export const Button = forwardRef<
  HTMLButtonElement,
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: 'sm' | 'md' }
>(function Button({ className, variant = 'default', size = 'md', ...props }, ref) {
  return (
    <button
      ref={ref}
      className={cn(
        'inline-flex shrink-0 items-center justify-center gap-2 rounded-md font-medium',
        'transition-[background-color,border-color,color,filter,transform] duration-200 ease-panel',
        // Нажатие отзывается смещением на пиксель. Этого достаточно, чтобы
        // касание на телефоне ощущалось нажатием, — там нет ни курсора, ни
        // состояния hover, и без отклика непонятно, попал ли ты по кнопке.
        'active:translate-y-px',
        'disabled:pointer-events-none disabled:opacity-45',
        FOCUS_RING,
        size === 'sm' ? 'h-10 px-3 text-sm sm:h-8 sm:text-xs' : 'h-11 px-4 text-sm sm:h-9',
        variant === 'default' &&
          'bg-primary text-primary-foreground shadow-sm hover:brightness-110',
        variant === 'outline' &&
          'border border-neutral-800 bg-transparent text-neutral-300 hover:border-primary/60 hover:bg-primary/10 hover:text-neutral-100',
        variant === 'ghost' && 'text-muted hover:bg-white/5 hover:text-neutral-100',
        variant === 'destructive' && 'bg-destructive text-white hover:brightness-110',
        className,
      )}
      {...props}
    />
  );
});

/** Поля ввода: подложка темнее карточки, чтобы поле читалось как углубление. */
const FIELD =
  'w-full rounded-md border border-neutral-800 bg-background/70 text-neutral-100 placeholder:text-muted ' +
  'transition-[border-color,box-shadow] duration-200 ' +
  'focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/20 ' +
  'disabled:opacity-50';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return (
      <input
        ref={ref}
        // text-base на мобильном — против автозума iOS, см. шапку файла.
        className={cn(FIELD, 'flex h-11 px-3 py-1 text-base sm:h-9 sm:text-sm', className)}
        {...props}
      />
    );
  },
);

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className, ...props }, ref) {
    return (
      <textarea
        ref={ref}
        className={cn(FIELD, 'flex min-h-[70px] px-3 py-2 text-base sm:text-sm', className)}
        {...props}
      />
    );
  },
);

/**
 * Карточка — основная поверхность интерфейса.
 *
 * Ссылку наружу отдаёт намеренно: прокручиваемым карточкам (консоль) нужно
 * уметь дотянуться до собственного scrollTop, а прокручивать их через
 * scrollIntoView вложенного элемента нельзя — он тянет за собой и страницу.
 */
export const Card = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement>>(
  function Card({ className, children, ...props }, ref) {
    return (
      // На узком экране поля по 16 px с каждой стороны съедали бы почти
      // десятую часть ширины — на мобильном отступ меньше.
      <div
        ref={ref}
        className={cn('rounded-lg border border-border bg-card p-3 sm:p-4', className)}
        {...props}
      >
        {children}
      </div>
    );
  },
);

export function Badge({
  children,
  variant = 'default',
  className,
}: {
  children: ReactNode;
  variant?: 'default' | 'outline' | 'destructive' | 'success' | 'warn';
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium',
        variant === 'default' && 'bg-primary/15 text-primary-300',
        variant === 'outline' && 'border border-border text-muted',
        variant === 'destructive' && 'bg-destructive/15 text-destructive',
        variant === 'success' && 'bg-ok/15 text-ok',
        variant === 'warn' && 'bg-warn/15 text-warn',
        className,
      )}
    >
      {children}
    </span>
  );
}

/**
 * Точка состояния для значка: цвет берёт у текста рядом и светится им же.
 * Отдельный элемент, потому что «работает» и «выключен» должны отличаться
 * не только словом — на беглый взгляд читается именно огонёк.
 */
export function Dot({ className }: { className?: string }) {
  return (
    <span
      className={cn('h-1.5 w-1.5 shrink-0 rounded-full bg-current', className)}
      style={{ boxShadow: '0 0 8px currentColor' }}
    />
  );
}

export function Label({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <label className={cn('mb-1 block text-xs font-medium text-muted', className)}>{children}</label>
  );
}

export function Select({
  value,
  onChange,
  options,
  className,
}: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  className?: string;
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={cn(FIELD, 'h-11 max-w-full px-2 text-base sm:h-9 sm:text-sm', className)}
    >
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

/**
 * Ряд вкладок с едущей подложкой.
 *
 * Подложка — один отдельный элемент, который переезжает под активную вкладку,
 * а не подсветка, вспыхивающая на новом месте. Разница смысловая: переезд
 * показывает, ЧТО ИМЕННО сменилось и куда, а вспышка оставляет глаз искать
 * активную вкладку заново — особенно на длинном ряду, где она может оказаться
 * с другого края.
 *
 * Позиция меряется по живому DOM, а не считается из ширин: вкладки подписаны
 * словами разной длины, ряд на узком экране прокручивается, и любая
 * арифметика тут разъезжается. Пересчёт — на смену активной, на изменение
 * размеров (ResizeObserver) и на прокрутку ряда.
 */
export function Tabs({
  tabs,
  active,
  onChange,
  fill = false,
}: {
  tabs: { id: string; label: string; icon?: ReactNode }[];
  active: string;
  onChange: (id: string) => void;
  /**
   * Делить ширину поровну на телефоне. Для коротких рядов из трёх вкладок,
   * которые целиком помещаются на экран: так в каждую удобно попасть пальцем,
   * и прокручивать нечего.
   */
  fill?: boolean;
}) {
  const rowRef = useRef<HTMLDivElement | null>(null);
  const [indicator, setIndicator] = useState<{ left: number; width: number } | null>(null);
  /**
   * Первую установку подложки делаем без перехода: иначе при открытии
   * страницы она приезжает из левого угла — движение, которого никто не
   * совершал.
   */
  const placed = useRef(false);

  useLayoutEffect(() => {
    const row = rowRef.current;
    if (!row) return;

    const measure = () => {
      const el = row.querySelector<HTMLElement>(`[data-tab="${CSS.escape(active)}"]`);
      if (!el) return setIndicator(null);
      // offsetLeft, а не getBoundingClientRect: ряд прокручивается, и
      // координаты относительно окна уезжают вместе с прокруткой, а
      // относительно родителя — нет.
      setIndicator({ left: el.offsetLeft, width: el.offsetWidth });
    };

    measure();
    const raf = requestAnimationFrame(() => {
      placed.current = true;
    });

    // Шрифт догружается после первой отрисовки и меняет ширину подписей —
    // без пересчёта подложка остаётся под старыми размерами.
    const observer = new ResizeObserver(measure);
    observer.observe(row);
    for (const child of Array.from(row.children)) observer.observe(child);

    return () => {
      cancelAnimationFrame(raf);
      observer.disconnect();
    };
  }, [active, tabs]);

  return (
    // Вкладок бывает шесть и больше, а на 375 px в ряд помещается три.
    // Горизонтальная прокрутка вместо переноса: ряд вкладок, разъехавшийся
    // на две строки, перестаёт читаться как одна группа. scrollbar скрыт —
    // на мобильном его и так нет, а на десктопе ряд обычно влезает целиком.
    <div className="-mx-3 overflow-x-auto px-3 sm:mx-0 sm:px-0 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      <div
        ref={rowRef}
        className={cn(
          'relative flex min-w-full gap-1 rounded-lg border border-border bg-card/60 p-1',
          fill ? 'w-full' : 'w-max',
        )}
      >
        {indicator && (
          <span
            aria-hidden
            className={cn(
              'pointer-events-none absolute bottom-1 top-1 rounded-md bg-primary/15',
              'shadow-[inset_0_0_0_1px_rgba(145,132,217,.28)]',
              // motion-safe: у тех, кто попросил систему не анимировать,
              // подложка просто оказывается на новом месте.
              placed.current && 'motion-safe:transition-[transform,width] motion-safe:duration-300 motion-safe:ease-panel',
            )}
            style={{ transform: `translateX(${indicator.left}px)`, width: indicator.width }}
          />
        )}
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            data-tab={t.id}
            aria-current={active === t.id ? 'page' : undefined}
            onClick={() => onChange(t.id)}
            className={cn(
              'relative z-10 flex min-h-11 items-center gap-2 whitespace-nowrap rounded-md px-3 text-sm',
              'transition-colors duration-200 sm:min-h-9',
              fill ? 'flex-1 justify-center sm:flex-none sm:justify-start' : 'shrink-0',
              FOCUS_RING,
              active === t.id ? 'text-primary-200' : 'text-muted hover:text-neutral-100',
            )}
          >
            {t.icon}
            {t.label}
          </button>
        ))}
      </div>
    </div>
  );
}

export function Spinner() {
  return (
    <div className="flex justify-center p-8">
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
    </div>
  );
}

export function ErrorText({ children }: { children: ReactNode }) {
  if (!children) return null;
  return <p className="text-sm text-destructive">{children}</p>;
}
