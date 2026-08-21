/**
 * Минимальный набор UI-компонентов в стиле shadcn/ui (тёмная тема).
 * При желании заменяются на полноценные shadcn-компоненты — API совместим.
 *
 * ПРО РАЗМЕР ШРИФТА В ПОЛЯХ ВВОДА. У Input, Textarea и Select на мобильном
 * стоит text-base (16 px), и уменьшать его до text-sm можно только начиная
 * с sm:. Причина не в читаемости: Safari и Chrome на iOS автоматически
 * увеличивают масштаб всей страницы, когда фокус попадает в поле со шрифтом
 * мельче 16 px. Внешне это выглядит как «поехала вёрстка и запрыгали
 * размеры шрифта» — причём после расфокуса масштаб не возвращается.
 * Ровно поэтому здесь 16 px, и менять это, не проверив на iPhone, не стоит.
 */
import {
  forwardRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '../lib/cn';

type ButtonVariant = 'default' | 'outline' | 'ghost' | 'destructive';

export const Button = forwardRef<
  HTMLButtonElement,
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: 'sm' | 'md' }
>(function Button({ className, variant = 'default', size = 'md', ...props }, ref) {
  return (
    <button
      ref={ref}
      className={cn(
        'inline-flex shrink-0 items-center justify-center gap-2 rounded-md font-medium transition-colors',
        'disabled:pointer-events-none disabled:opacity-50',
        // На мобильном кнопки крупнее: 40 px — нижняя граница, при которой
        // в кнопку попадают пальцем с первого раза. На десктопе, где целятся
        // мышью, оставляем прежнюю плотную вёрстку.
        size === 'sm' ? 'h-10 px-3 text-sm sm:h-8 sm:text-xs' : 'h-11 px-4 text-sm sm:h-9',
        variant === 'default' && 'bg-primary text-primary-foreground hover:bg-primary/90',
        variant === 'outline' && 'border border-border bg-transparent hover:bg-white/5',
        variant === 'ghost' && 'hover:bg-white/5',
        variant === 'destructive' && 'bg-destructive text-white hover:bg-destructive/90',
        className,
      )}
      {...props}
    />
  );
});

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return (
      <input
        ref={ref}
        className={cn(
          // text-base на мобильном — против автозума iOS, см. шапку файла.
          'flex h-11 w-full rounded-md border border-border bg-background px-3 py-1 text-base',
          'sm:h-9 sm:text-sm',
          'placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-primary/50',
          className,
        )}
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
        className={cn(
          'flex min-h-[70px] w-full rounded-md border border-border bg-background px-3 py-2 text-base',
          'sm:text-sm',
          'placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-primary/50',
          className,
        )}
        {...props}
      />
    );
  },
);

/**
 * Карточка. Ссылку наружу отдаёт намеренно: прокручиваемым карточкам (консоль)
 * нужно уметь дотянуться до собственного scrollTop, а прокручивать их через
 * scrollIntoView вложенного элемента нельзя — он тянет за собой и страницу.
 */
export const Card = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement>>(
  function Card({ className, children, ...props }, ref) {
    return (
      // На узком экране поля по 16 px с каждой стороны съедали бы почти
      // десятую часть ширины — на мобильном отступ меньше.
      <div
        ref={ref}
        className={cn('rounded-lg border border-border bg-card p-3 shadow-sm sm:p-4', className)}
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
  variant?: 'default' | 'outline' | 'destructive' | 'success';
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        variant === 'default' && 'bg-primary/15 text-primary',
        variant === 'outline' && 'border border-border text-muted',
        variant === 'destructive' && 'bg-destructive/15 text-red-400',
        variant === 'success' && 'bg-emerald-500/15 text-emerald-400',
        className,
      )}
    >
      {children}
    </span>
  );
}

export function Label({ children, className }: { children: ReactNode; className?: string }) {
  return <label className={cn('mb-1 block text-xs font-medium text-muted', className)}>{children}</label>;
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
      className={cn(
        'h-11 max-w-full rounded-md border border-border bg-background px-2 text-base',
        'sm:h-9 sm:text-sm',
        'focus:outline-none focus:ring-2 focus:ring-primary/50',
        className,
      )}
    >
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

export function Tabs({
  tabs,
  active,
  onChange,
}: {
  tabs: { id: string; label: string }[];
  active: string;
  onChange: (id: string) => void;
}) {
  return (
    // Вкладок бывает шесть и больше, а на 375 px в ряд помещается три.
    // Горизонтальная прокрутка вместо переноса: ряд вкладок, разъехавшийся
    // на две строки, перестаёт читаться как одна группа. scrollbar скрыт —
    // на мобильном его и так нет, а на десктопе ряд обычно влезает целиком.
    <div className="-mx-3 overflow-x-auto px-3 sm:mx-0 sm:px-0 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      <div className="flex w-max min-w-full gap-1 border-b border-border">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => onChange(t.id)}
            className={cn(
              'shrink-0 whitespace-nowrap rounded-t-md px-3 py-2.5 text-sm transition-colors sm:py-2',
              active === t.id
                ? 'border border-border border-b-transparent bg-card text-primary'
                : 'text-muted hover:text-neutral-100',
            )}
          >
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
  return <p className="text-sm text-red-400">{children}</p>;
}
