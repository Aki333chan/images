import type { ReactNode } from 'react';
import { cn } from '../lib/cn';

/**
 * Карточка экранов, которые человек видит до входа: логин, второй фактор,
 * первый вход.
 *
 * Общая на все три намеренно. Это единственные экраны без бокового меню, и
 * до входа человек не знает, куда попал: логотип и название здесь — не
 * украшение, а единственный способ убедиться, что открыта та самая панель,
 * а не похожая страница по похожему адресу.
 */
export function AuthCard({
  title,
  description,
  children,
  className,
}: {
  /** Заголовок под шапкой. Без него карточка — просто форма входа. */
  title?: string;
  description?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    // p-4: без отступа карточка на 375 px прижималась к обоим краям экрана
    // вплотную — max-w-sm (384 px) шире самого экрана.
    <div className="flex min-h-screen items-center justify-center p-4">
      <div
        className={cn(
          'aurum-rise-up relative w-full max-w-sm overflow-hidden rounded-lg bg-card px-7 pb-6 pt-8 shadow-md',
          className,
        )}
      >
        {/* Уголок акцентом. Мелочь, но она задаёт всей карточке верх и левую
            сторону — на сплошной тёмной заливке иначе не видно, где она
            начинается. */}
        <span className="pointer-events-none absolute left-0 top-0 h-0.5 w-[34px] bg-primary" />
        <span className="pointer-events-none absolute left-0 top-0 h-[34px] w-0.5 bg-primary" />

        <div className="mb-6 flex items-center justify-center gap-3">
          <img
            src="/logo-128.png"
            alt=""
            width={42}
            height={42}
            className="h-[42px] w-[42px] object-contain drop-shadow-[0_3px_10px_rgba(0,0,0,.5)]"
          />
          <span className="text-lg font-medium uppercase tracking-[0.1em] text-primary-200">
            Aurum Panel
          </span>
        </div>

        {title && <h1 className="mb-1.5 text-xl font-semibold">{title}</h1>}
        {description && <p className="mb-4 text-[12.5px] leading-relaxed text-muted">{description}</p>}

        {children}
      </div>
    </div>
  );
}
