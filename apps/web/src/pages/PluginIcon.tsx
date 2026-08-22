import { useEffect, useState } from 'react';
import { cn } from '../lib/cn';

/**
 * Иконка плагина из маркета.
 *
 * Адрес НЕ подставляется в src напрямую: панель стоит за nginx с
 * Content-Security-Policy, где img-src ограничен своим доменом и crafatar.com,
 * и картинка с cdn.modrinth.com просто не загрузится — браузер покажет значок
 * битого изображения. Перечислять там по домену на каждый источник значит
 * править конфиг живого сервера при каждом новом маркете, а заодно
 * показывать этим CDN адрес каждого, кто открыл список плагинов.
 *
 * Поэтому картинку забирает панель со своего адреса, а если не вышло —
 * рисуется буква названия. Пустой прямоугольник на её месте выглядел бы как
 * «не догрузилось», хотя грузить нечего.
 */
export function PluginIcon({
  url,
  title,
  size = 48,
  className,
}: {
  url: string | null;
  title: string;
  size?: number;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);

  // Сброс при смене плагина: без него карточка нового плагина унаследовала бы
  // отказ от предыдущего и показала бы букву при рабочей иконке.
  useEffect(() => setFailed(false), [url]);

  const box = cn('shrink-0 overflow-hidden rounded bg-white/5', className);
  const style = { width: size, height: size };

  if (!url || failed) {
    return (
      <div
        className={cn(box, 'flex items-center justify-center font-medium text-primary-300')}
        style={{ ...style, fontSize: Math.round(size * 0.42) }}
        aria-hidden
      >
        {title.trim().charAt(0).toUpperCase() || '?'}
      </div>
    );
  }

  return (
    <img
      src={`/api/modules/minecraft/market/icon?url=${encodeURIComponent(url)}`}
      alt=""
      className={cn(box, 'object-contain')}
      style={style}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}
