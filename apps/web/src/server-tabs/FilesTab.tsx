import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import type { PteroDirectoryDto, PteroFileContentDto, PteroFileDto } from '@aurum/shared';
import { MAX_TRANSFER_BYTES, formatTransferLimit, isEditableFile } from '@aurum/shared';
import { api, apiDownload, apiRaw } from '../lib/api';
import { useAuth } from '../lib/auth';
import {
  canGoBack,
  canGoForward,
  currentPath,
  goBack,
  goForward,
  initialHistory,
  visit,
  type DirHistory,
} from '../lib/dir-history';
import { Badge, Button, Card, ErrorText, Input, Label, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import {
  IconArchive,
  IconDownload,
  IconFile,
  IconFolder,
  IconPlus,
  IconSave,
  IconTrash,
  IconUpload,
} from '../components/icons';
/**
 * Редактор грузится отдельным куском и только когда файл открывают.
 *
 * CodeMirror почти удваивает размер бандла, а открывают файлы далеко не
 * все и далеко не каждый раз. Заставлять всех — включая тех, кто зашёл с
 * телефона посмотреть игроков, — качать редактор незачем.
 */
const CodeEditor = lazy(() =>
  import('./CodeEditor').then((m) => ({ default: m.CodeEditor })),
);
import type { ServerTabProps } from './registry';

const base = (serverId: string) => `/api/servers/${serverId}/files`;

/**
 * Файловый менеджер сервера.
 *
 * Это возможность самого Pterodactyl, а не игрового модуля: файл есть файл
 * и на Minecraft, и на Palworld. Поэтому вкладка показывается при любом
 * подключённом модуле.
 *
 * Права разделены на три, и это видно в интерфейсе: у кого нет files.manage,
 * тот не увидит кнопок изменения, а не наткнётся на отказ после нажатия.
 */
export function FilesTab({ serverId }: ServerTabProps) {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('files.manage');
  const canDelete = hasPermission('files.delete');

  const [dir, setDir] = useState<PteroDirectoryDto | null>(null);
  const [path, setPath] = useState('/');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [editing, setEditing] = useState<{ file: PteroFileDto; content: string; truncated: boolean } | null>(null);
  const [dirty, setDirty] = useState(false);
  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState<PteroFileDto | null>(null);
  const uploadInput = useRef<HTMLInputElement>(null);
  /**
   * Что заливается прямо сейчас.
   *
   * null — не заливается ничего. Имя и счётчик, а не просто крутилка: при
   * нескольких файлах без них непонятно, застряло оно или идёт дальше.
   */
  const [uploading, setUploading] = useState<{ name: string; done: number; total: number } | null>(
    null,
  );
  const [history, setHistory] = useState<DirHistory>(() => initialHistory('/'));

  const load = useCallback(
    (target: string) => {
      setError('');
      setSelected(new Set());
      return api<PteroDirectoryDto>(`${base(serverId)}/list?path=${encodeURIComponent(target)}`)
        .then((d) => {
          setDir(d);
          setPath(d.path);
        })
        .catch((e: Error) => setError(e.message));
    },
    [serverId],
  );

  /**
   * Перейти в папку — с записью в историю.
   *
   * Отдельно от {@link load}: тот же вызов используется и для простого
   * перечитывания текущей папки после загрузки или удаления, а это не
   * переход, и в истории ему делать нечего.
   */
  const navigate = useCallback(
    (target: string) => {
      setHistory((h) => visit(h, target));
      return load(target);
    },
    [load],
  );

  /** Шаг по своей истории. null от goBack/goForward значит «идти некуда». */
  const step = useCallback(
    (move: (h: DirHistory) => DirHistory | null) => {
      setHistory((h) => {
        const next = move(h);
        if (!next) return h;
        void load(currentPath(next));
        return next;
      });
    },
    [load],
  );

  useEffect(() => {
    void load('/');
  }, [load]);

  /**
   * Кнопки «назад» и «вперёд» на мыши ходят по папкам, а не уводят со страницы.
   *
   * Браузер по умолчанию понимает mouse4 и mouse5 как навигацию, и в файловом
   * менеджере это выкидывало на список серверов — хотя человек хотел всего
   * лишь подняться на папку выше.
   *
   * Отменять приходится в трёх событиях: браузеры расходятся в том, какое из
   * них запускает переход, и отмены одного mousedown в части из них
   * недостаточно. Слушатели живут ровно столько, сколько открыта вкладка
   * файлов; на остальных вкладках кнопки работают как обычно.
   */
  useEffect(() => {
    const swallow = (event: MouseEvent) => {
      if (event.button === 3 || event.button === 4) event.preventDefault();
    };
    const act = (event: MouseEvent) => {
      if (event.button !== 3 && event.button !== 4) return;
      event.preventDefault();
      step(event.button === 3 ? goBack : goForward);
    };
    window.addEventListener('mousedown', swallow);
    window.addEventListener('auxclick', swallow);
    window.addEventListener('mouseup', act);
    return () => {
      window.removeEventListener('mousedown', swallow);
      window.removeEventListener('auxclick', swallow);
      window.removeEventListener('mouseup', act);
    };
  }, [step]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError('');
    try {
      await action();
      await load(path);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function open(file: PteroFileDto) {
    if (!file.isFile) {
      void navigate(join(path, file.name));
      return;
    }
    if (!isEditableFile(file)) {
      // Не открываем бинарник в текстовом редакторе: сохранить его обратно
      // значило бы испортить файл, а прочитать всё равно не выйдет.
      setError('Такой файл в редакторе не открыть — скачайте его');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const res = await api<PteroFileContentDto>(
        `${base(serverId)}/content?path=${encodeURIComponent(join(path, file.name))}`,
      );
      setEditing({ file, content: res.content, truncated: res.truncated });
      setDirty(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    if (!editing) return;
    setBusy(true);
    setError('');
    try {
      await apiRaw(
        `${base(serverId)}/content?path=${encodeURIComponent(join(path, editing.file.name))}`,
        new Blob([editing.content], { type: 'application/octet-stream' }),
      );
      setDirty(false);
      setEditing(null);
      await load(path);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function download(file: PteroFileDto) {
    // Не ссылкой: доступ подтверждается токеном в памяти вкладки, а обычная
    // навигация его не отправит и получит 401.
    setError('');
    try {
      await apiDownload(
        `${base(serverId)}/download?path=${encodeURIComponent(join(path, file.name))}`,
        file.name,
      );
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function upload(files: FileList | null) {
    if (!files || files.length === 0) return;

    // Размер проверяем ДО отправки. Иначе человек ждёт, пока мегабайты уедут
    // на сервер, и только там получает отказ — а на мобильном интернете это
    // ещё и потраченный трафик. Предел тот же, что и на бэкенде: общая
    // константа, а не переписанное здесь число.
    const tooBig = Array.from(files).find((file) => file.size > MAX_TRANSFER_BYTES);
    if (tooBig) {
      setError(
        `«${tooBig.name}» больше ${formatTransferLimit()} — столько панель не пропускает. ` +
          'Такие файлы загружают по SFTP.',
      );
      if (uploadInput.current) uploadInput.current.value = '';
      return;
    }

    const queue = Array.from(files);
    setBusy(true);
    setError('');
    try {
      for (let i = 0; i < queue.length; i++) {
        const file = queue[i]!;
        // Имя обновляется ПЕРЕД отправкой: иначе, пока файл едет, на экране
        // висело бы имя предыдущего.
        setUploading({ name: file.name, done: i, total: queue.length });
        await apiRaw(
          `${base(serverId)}/upload?path=${encodeURIComponent(path)}&name=${encodeURIComponent(file.name)}`,
          file,
        );
      }
      await load(path);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setUploading(null);
      setBusy(false);
      if (uploadInput.current) uploadInput.current.value = '';
    }
  }

  if (!dir && !error) return <Spinner />;

  const names = [...selected];

  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        {/* Хлебные крошки считает бэкенд: так обе стороны одинаково понимают,
            что такое путь, и не расходятся на первом необычном имени.

            Стрелки слева — то же, что кнопки мыши. Они здесь не ради тех, у
            кого мышь с двумя кнопками, а ради того, чтобы возможность вообще
            была видна: про перехват mouse4 и mouse5 иначе никто не узнает. */}
        <div className="flex flex-wrap items-center gap-1 text-sm">
          <button
            type="button"
            onClick={() => step(goBack)}
            disabled={!canGoBack(history)}
            title="Назад (или кнопка «назад» на мыши)"
            aria-label="Назад"
            className="rounded-sm px-1.5 py-0.5 text-muted transition-colors hover:bg-surface hover:text-neutral-200 disabled:opacity-30 disabled:hover:bg-transparent"
          >
            ←
          </button>
          <button
            type="button"
            onClick={() => step(goForward)}
            disabled={!canGoForward(history)}
            title="Вперёд (или кнопка «вперёд» на мыши)"
            aria-label="Вперёд"
            className="mr-1 rounded-sm px-1.5 py-0.5 text-muted transition-colors hover:bg-surface hover:text-neutral-200 disabled:opacity-30 disabled:hover:bg-transparent"
          >
            →
          </button>
          {dir?.breadcrumbs.map((crumb, i) => (
            <span key={crumb.path} className="flex items-center gap-1">
              {i > 0 && <span className="text-muted">/</span>}
              <button
                type="button"
                onClick={() => void navigate(crumb.path)}
                className="rounded-sm px-1 py-0.5 text-primary-200 transition-colors hover:bg-surface"
              >
                {crumb.name}
              </button>
            </span>
          ))}
        </div>

        {/* Пока файл едет, человеку нужно понимать, что работа идёт и сколько
            её осталось. Раньше кнопки просто гасли, и на большом файле это
            выглядело как зависшая панель. */}
        {uploading && (
          <div className="flex items-center gap-2 rounded border border-border bg-surface/60 px-3 py-2 text-xs">
            <Spinner />
            <span className="min-w-0 flex-1 truncate">
              Загружаю «{uploading.name}»
              {uploading.total > 1 && ` — ${uploading.done + 1} из ${uploading.total}`}
            </span>
          </div>
        )}

        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" onClick={() => void load(path)} disabled={busy}>
            Обновить
          </Button>
          {canManage && (
            <>
              <Button size="sm" variant="outline" onClick={() => setCreating(true)} disabled={busy}>
                <IconPlus size={14} /> Папка
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => uploadInput.current?.click()}
                disabled={busy}
              >
                <IconUpload size={14} /> {uploading ? 'Загружаю…' : 'Загрузить'}
              </Button>
              <input
                ref={uploadInput}
                type="file"
                multiple
                className="hidden"
                onChange={(e) => void upload(e.target.files)}
              />
            </>
          )}
          {canManage && names.length > 0 && (
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() =>
                void run(() =>
                  api(`${base(serverId)}/compress`, {
                    method: 'POST',
                    body: JSON.stringify({ path, names }),
                  }),
                )
              }
            >
              <IconArchive size={14} /> В архив ({names.length})
            </Button>
          )}
          {canDelete && names.length > 0 && (
            <Button
              size="sm"
              variant="destructive"
              disabled={busy}
              onClick={() => {
                if (!confirm(`Удалить безвозвратно: ${names.join(', ')}?`)) return;
                void run(() =>
                  api(`${base(serverId)}/delete`, {
                    method: 'POST',
                    body: JSON.stringify({ path, names }),
                  }),
                );
              }}
            >
              <IconTrash size={14} /> Удалить ({names.length})
            </Button>
          )}
        </div>

        <ErrorText>{error}</ErrorText>

        {dir && dir.entries.length === 0 ? (
          <p className="text-muted">Каталог пуст.</p>
        ) : (
          <ul className="divide-y divide-border">
            {dir?.entries.map((entry) => (
              <li key={entry.name} className="flex items-center gap-2 py-2">
                {(canManage || canDelete) && (
                  <input
                    type="checkbox"
                    className="h-4 w-4 shrink-0 accent-primary"
                    checked={selected.has(entry.name)}
                    onChange={(e) => {
                      const next = new Set(selected);
                      if (e.target.checked) next.add(entry.name);
                      else next.delete(entry.name);
                      setSelected(next);
                    }}
                  />
                )}
                <button
                  type="button"
                  onClick={() => void open(entry)}
                  className="flex min-w-0 flex-1 items-center gap-2 text-left"
                >
                  <span className={entry.isFile ? 'text-muted' : 'text-primary-200'}>
                    {entry.isFile ? <IconFile size={16} /> : <IconFolder size={16} />}
                  </span>
                  <span className="truncate text-sm">{entry.name}</span>
                  {entry.isSymlink && <Badge variant="outline">ссылка</Badge>}
                </button>
                <span className="shrink-0 text-[11px] tabular-nums text-muted">
                  {entry.isFile ? formatSize(entry.size) : ''}
                </span>
                <span className="hidden shrink-0 text-[11px] text-muted sm:inline">
                  {new Date(entry.modifiedAt).toLocaleString('ru-RU')}
                </span>
                <div className="flex shrink-0 gap-1">
                  {entry.isFile && (
                    <Button size="sm" variant="ghost" onClick={() => void download(entry)} title="Скачать">
                      <IconDownload size={14} />
                    </Button>
                  )}
                  {canManage && (
                    <Button size="sm" variant="ghost" onClick={() => setRenaming(entry)} title="Переименовать или перенести">
                      ⋯
                    </Button>
                  )}
                  {canManage && entry.isFile && isArchive(entry.name) && (
                    <Button
                      size="sm"
                      variant="ghost"
                      title="Распаковать"
                      onClick={() =>
                        void run(() =>
                          api(`${base(serverId)}/decompress`, {
                            method: 'POST',
                            body: JSON.stringify({ path: join(path, entry.name) }),
                          }),
                        )
                      }
                    >
                      <IconArchive size={14} />
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {editing && (
        <Modal
          title={editing.file.name}
          wide
          onClose={() => {
            if (dirty && !confirm('Изменения не сохранены. Закрыть?')) return;
            setEditing(null);
          }}
        >
          <div className="space-y-3">
            {editing.truncated && (
              // Сохранить обрезанный файл значило бы стереть его хвост.
              <p className="rounded-md bg-amber-500/10 px-3 py-2 text-xs text-amber-400">
                Файл слишком велик и показан не целиком — сохранение отключено, иначе
                остаток был бы стёрт.
              </p>
            )}
            <Suspense
              fallback={
                <div className="flex h-[55vh] min-h-[280px] items-center justify-center rounded-md border border-border">
                  <Spinner />
                </div>
              }
            >
              <CodeEditor
                value={editing.content}
                fileName={editing.file.name}
                readOnly={!canManage || editing.truncated}
                onChange={(content) => {
                  setEditing((prev) => (prev ? { ...prev, content } : prev));
                  setDirty(true);
                }}
              />
            </Suspense>
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button variant="ghost" onClick={() => setEditing(null)} disabled={busy}>
                Закрыть
              </Button>
              {canManage && !editing.truncated && (
                <Button onClick={() => void save()} disabled={busy || !dirty}>
                  <IconSave size={14} /> Сохранить
                </Button>
              )}
            </div>
          </div>
        </Modal>
      )}

      {creating && (
        <NameModal
          title="Новая папка"
          label="Имя папки"
          onClose={() => setCreating(false)}
          onSubmit={(name) => {
            setCreating(false);
            return run(() =>
              api(`${base(serverId)}/folder`, {
                method: 'POST',
                body: JSON.stringify({ path, name }),
              }),
            );
          }}
        />
      )}

      {renaming && (
        <NameModal
          title={`Переименовать «${renaming.name}»`}
          label="Новое имя или путь"
          initial={renaming.name}
          hint="Можно указать путь — тогда файл переедет: например /plugins/старое.yml"
          onClose={() => setRenaming(null)}
          onSubmit={(value) => {
            const from = join(path, renaming.name);
            const to = value.startsWith('/') ? value : join(path, value);
            setRenaming(null);
            return run(() =>
              api(`${base(serverId)}/move`, {
                method: 'POST',
                body: JSON.stringify({ from, to }),
              }),
            );
          }}
        />
      )}
    </div>
  );
}

function NameModal({
  title,
  label,
  initial = '',
  hint,
  onClose,
  onSubmit,
}: {
  title: string;
  label: string;
  initial?: string;
  hint?: string;
  onClose: () => void;
  onSubmit: (value: string) => void | Promise<void>;
}) {
  const [value, setValue] = useState(initial);
  return (
    <Modal title={title} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <Label>{label}</Label>
          <Input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && value.trim() && void onSubmit(value.trim())}
            autoFocus
          />
          {hint && <p className="mt-1 text-[11px] text-muted">{hint}</p>}
        </div>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={value.trim() === ''} onClick={() => void onSubmit(value.trim())}>
            Готово
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function join(directory: string, name: string): string {
  return directory === '/' ? `/${name}` : `${directory}/${name}`;
}

function isArchive(name: string): boolean {
  return /\.(zip|tar|tar\.gz|tgz|tar\.xz|rar|7z)$/i.test(name);
}

/** Размер в человеческом виде. Байты показываем как есть — так короче. */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ГБ`;
}
