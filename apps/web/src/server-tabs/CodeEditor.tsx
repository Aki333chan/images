import { useEffect, useRef } from 'react';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { bracketMatching, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import { yaml } from '@codemirror/lang-yaml';
import { json } from '@codemirror/lang-json';
import { xml } from '@codemirror/lang-xml';
import { oneDark } from '@codemirror/theme-one-dark';
import { highlightLanguage } from '@aurum/shared';

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
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
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
    <div
      ref={host}
      className="h-[55vh] min-h-[280px] overflow-hidden rounded-md border border-border"
    />
  );
}
