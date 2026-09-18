import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { IconCheck, IconClose, IconWarning } from './icons';
import { useT } from '../i18n';

type ToastTone = 'success' | 'error' | 'info';
type ToastInput = { message: string; tone?: ToastTone; duration?: number };
type ToastEntry = Required<ToastInput> & { id: number };

const ToastContext = createContext<{
  show: (toast: ToastInput) => void;
  success: (message: string) => void;
  error: (message: string) => void;
} | null>(null);

let nextToastId = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastEntry[]>([]);

  const show = useCallback((toast: ToastInput) => {
    const entry: ToastEntry = {
      id: ++nextToastId,
      message: toast.message,
      tone: toast.tone ?? 'info',
      duration: toast.duration ?? 5500,
    };
    setToasts((current) => [...current.slice(-2), entry]);
  }, []);

  const value = useMemo(
    () => ({
      show,
      success: (message: string) => show({ message, tone: 'success' }),
      error: (message: string) => show({ message, tone: 'error' }),
    }),
    [show],
  );

  const remove = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        aria-live="polite"
        aria-atomic="false"
        className="pointer-events-none fixed inset-x-3 top-[calc(1rem+env(safe-area-inset-top))] z-[90] flex flex-col items-end gap-2 sm:left-auto sm:right-5 sm:w-[min(420px,calc(100vw-2.5rem))]"
      >
        {toasts.map((toast) => (
          <ToastItem key={toast.id} toast={toast} onRemove={remove} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside ToastProvider');
  return context;
}

function ToastItem({ toast, onRemove }: { toast: ToastEntry; onRemove: (id: number) => void }) {
  const t = useT();
  const [leaving, setLeaving] = useState(false);
  const closing = useRef(false);

  const dismiss = useCallback(() => {
    if (closing.current) return;
    closing.current = true;
    setLeaving(true);
    window.setTimeout(() => onRemove(toast.id), 180);
  }, [onRemove, toast.id]);

  useEffect(() => {
    const timer = window.setTimeout(dismiss, toast.duration);
    return () => window.clearTimeout(timer);
  }, [dismiss, toast.duration]);

  const Icon = toast.tone === 'error' ? IconWarning : IconCheck;

  return (
    <div
      role={toast.tone === 'error' ? 'alert' : 'status'}
      className={
        'aurum-toast pointer-events-auto flex w-full items-start gap-3 rounded-lg border bg-card/95 p-3 shadow-md ' +
        (toast.tone === 'error'
          ? 'border-destructive/50 text-red-300 '
          : 'border-ok/45 text-emerald-300 ') +
        (leaving ? 'aurum-toast-leave' : '')
      }
    >
      <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-current/10">
        <Icon size={15} />
      </span>
      <p className="min-w-0 flex-1 whitespace-pre-wrap break-words pt-0.5 text-sm leading-5 text-neutral-100">
        {toast.message}
      </p>
      <button
        type="button"
        onClick={dismiss}
        aria-label={t('common.close')}
        className="-m-2 flex min-h-10 min-w-10 shrink-0 items-center justify-center rounded-md text-muted transition-colors hover:bg-white/5 hover:text-neutral-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/45"
      >
        <IconClose size={15} />
      </button>
    </div>
  );
}
