import { useEffect, useState } from 'react';
import { useT } from '../i18n';

/**
 * Адрес сервера с копированием в один тап.
 *
 * Это та строка, которую диктуют игрокам, поэтому она моноширинная (в ней
 * различимы 0 и O, 1 и l) и выделяется целиком по нажатию — на телефоне
 * выделить «ip:port» пальцем, не захватив соседний текст, почти невозможно.
 */
export function ServerAddress({ address }: { address: string | null }) {
  const t = useT();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(timer);
  }, [copied]);

  if (!address) {
    return (
      <p className="text-xs text-muted">
        {t('address.unknown')}
      </p>
    );
  }

  async function copy() {
    try {
      // clipboard есть не везде: в Safari он доступен только по https, а на
      // http-адресе панели его просто нет. Молча ничего не делать нельзя —
      // адрес в этом случае всё равно виден и выделяется руками.
      await navigator.clipboard.writeText(address!);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <button
      type="button"
      onClick={() => void copy()}
      title="Скопировать адрес"
      className="-mx-2 flex min-h-11 items-center gap-2 rounded px-2 text-left hover:bg-white/5 sm:mx-0 sm:min-h-0 sm:px-0 sm:hover:bg-transparent"
    >
      <span className="select-all break-all font-mono text-base font-semibold text-neutral-100">
        {address}
      </span>
      <span className="shrink-0 text-[11px] text-muted">{copied ? 'скопировано' : 'копировать'}</span>
    </button>
  );
}
