import { useEffect, useRef } from 'react';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { bracketMatching, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language';
import {
  highlightSelectionMatches,
  openSearchPanel,
  search,
  searchKeymap,
} from '@codemirror/search';
import { yaml } from '@codemirror/lang-yaml';
import { json } from '@codemirror/lang-json';
import { xml } from '@codemirror/lang-xml';
import { oneDark } from '@codemirror/theme-one-dark';
import { highlightLanguage } from '@aurum/shared';
import { useT } from '../i18n';
import { IconSearch } from '../components/icons';
import { Button } from '../components/ui';

/**
 * Редактор текстовых файлов.
 *
 * ПОЧЕМУ CodeMirror, А НЕ Monaco. Нужны подсветка, номера строк и поиск —
 * и всё. Monaco тянет за собой воркеры, языковой сервер и мегабайты, а
 * панель открывают в том числе с телефона. CodeMirror 6 собирается из
 * нужных кусков и не приносит ничего лишнего.
 *
 * Подсветка подключается по расширению файла и только для четырёх языков,
 * которые действительно встречаются в конфигах игровых серверов. Остальное
 * показывается простым текстом — это честнее, чем подсвечивать наугад.
 *
 * ПОИСК ПО ФАЙЛУ. Ctrl+F браузера здесь бесполезен: CodeMirror держит в DOM
 * только видимые строки, и всё, что ниже экрана, браузерный поиск просто не
 * видит. Поэтому Ctrl+F перехватывается и открывает поиск самого редактора —
 * причём из любого места окна, а не только когда курсор стоит в тексте.
 * Рядом есть и кнопка: сочетание клавиш знают не все.
 */
export function CodeEditor({
  value,
  fileName,
  readOnly,
  onChange,
}: {
  value: string;
  fileName: string;
  readOnly?: boolean;
  onChange: (value: string) => void;
}) {
  const t = useT();
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  /**
   * Подписи панели поиска держим в ref по той же причине, что и onChange:
   * их пересборка не должна пересоздавать редактор. Язык при жизни одного
   * открытого файла всё равно не меняется — его выбирают в настройках, и
   * страница после этого перезагружается.
   */
  const phrases = useRef<Record<string, string>>({});
  phrases.current = {
    Find: t('cm.find'),
    Replace: t('cm.replace'),
    next: t('cm.next'),
    previous: t('cm.previous'),
    all: t('cm.all'),
    'match case': t('cm.matchCase'),
    regexp: t('cm.regexp'),
    'by word': t('cm.byWord'),
    replace: t('cm.replaceOne'),
    'replace all': t('cm.replaceAll'),
    close: t('cm.close'),
    'current match': t('cm.currentMatch'),
    'on line': t('cm.onLine'),
    'Go to line': t('cm.goToLine'),
    go: t('cm.go'),
  };
  /**
   * Обработчик держим в ref, а не в зависимостях эффекта.
   *
   * Иначе каждая перерисовка родителя пересоздавала бы редактор — вместе с
   * позицией курсора и историей отмен. Человек, правящий конфиг, потерял бы
   * место в файле на каждом нажатии клавиши.
   */
  const latestOnChange = useRef(onChange);
  latestOnChange.current = onChange;

  useEffect(() => {
    if (!host.current) return;

    const language = highlightLanguage(fileName);
    const languageExtension =
      language === 'yaml' ? [yaml()] :
      language === 'json' ? [json()] :
      language === 'xml' ? [xml()] :
      [];

    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        history(),
        indentOnInput(),
        bracketMatching(),
        highlightActiveLine(),
        highlightSelectionMatches(),
        // top: панель встаёт НАД текстом. Снизу она перекрывала последние
        // строки файла — ровно те, к которым человек и прокрутился.
        search({ top: true }),
        EditorState.phrases.of(phrases.current),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        ...languageExtension,
        keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, indentWithTab]),
        oneDark,
        EditorView.lineWrapping,
        EditorState.readOnly.of(!!readOnly),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) latestOnChange.current(update.state.doc.toString());
        }),
        EditorView.theme({
          '&': { fontSize: '12.5px', height: '100%' },
          '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
          '&.cm-focused': { outline: 'none' },
          // Панель поиска идёт от CodeMirror без оформления: голые input и
          // button поверх тёмного редактора выглядят чужеродно. Приводим их
          // к тем же токенам, что и остальная панель.
          '.cm-panels': { backgroundColor: 'transparent', borderBottom: '1px solid #ffffff1a' },
          '.cm-panel.cm-search': {
            padding: '8px',
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: '6px',
            backgroundColor: '#00000033',
          },
          // Перенос строки внутри панели оставляем, но делаем его разрывом
          // ряда: иначе «заменить» уезжает в хвост первой строки, а поля
          // поиска и замены перестают читаться как пара.
          '.cm-panel.cm-search br': { flexBasis: '100%', height: '0' },
          '.cm-panel.cm-search input, .cm-panel.cm-search button': {
            margin: '0',
            border: '1px solid #ffffff26',
            borderRadius: '6px',
            backgroundColor: '#ffffff0d',
            color: 'inherit',
            font: 'inherit',
            padding: '4px 8px',
          },
          '.cm-panel.cm-search input[type=checkbox]': { padding: '0' },
          '.cm-panel.cm-search button:hover': { backgroundColor: '#ffffff1a' },
          '.cm-panel.cm-search label': {
            display: 'inline-flex',
            alignItems: 'center',
            gap: '4px',
            fontSize: '11px',
          },
          '.cm-panel.cm-search [name=close]': {
            position: 'static',
            marginLeft: 'auto',
            padding: '2px 8px',
          },
        }),
      ],
    });

    const editor = new EditorView({ state, parent: host.current });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // Пересоздаём только при смене файла: value внутри зависимостей означал
    // бы пересоздание на каждое нажатие клавиши.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileName, readOnly]);

  /**
   * Ctrl+F (Cmd+F) открывает поиск редактора из любого места окна.
   *
   * Своё сочетание у CodeMirror уже есть, но срабатывает оно, только когда
   * курсор стоит в тексте. Человек, который открыл файл и сразу нажал
   * Ctrl+F, попадал в поиск браузера — а тот видит лишь строки, которые
   * сейчас на экране, потому что остальные CodeMirror в DOM не держит.
   * Слушаем на всём окне: редактор существует ровно пока открыт файл.
   */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'f' && e.key !== 'F' && e.key !== 'а' && e.key !== 'А') return;
      if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
      const editor = view.current;
      if (!editor) return;
      e.preventDefault();
      openSearchPanel(editor);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  /**
   * Внешняя подмена содержимого — например, после перезагрузки файла.
   *
   * Сравнение с текущим текстом обязательно: без него редактор переписывал
   * бы сам себя на каждое своё же изменение и сбрасывал курсор в начало.
   */
  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const current = editor.state.doc.toString();
    if (current === value) return;
    editor.dispatch({ changes: { from: 0, to: current.length, insert: value } });
  }, [value]);

  return (
    <div className="space-y-2">
      {/* Кнопка рядом с редактором, а не только сочетание клавиш: про Ctrl+F
          в текстовом поле догадываются не все, а искать в конфиге на триста
          строк глазами — то, ради чего поиск и нужен. */}
      <div className="flex items-center justify-end">
        <Button
          size="sm"
          variant="outline"
          title={t('files.findHint')}
          onClick={() => {
            const editor = view.current;
            if (editor) openSearchPanel(editor);
          }}
        >
          <IconSearch size={14} />
          <span>{t('files.find')}</span>
        </Button>
      </div>
      <div
        ref={host}
        className="h-[55vh] min-h-[280px] overflow-hidden rounded-md border border-border"
      />
    </div>
  );
}
