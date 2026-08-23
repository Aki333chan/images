import { Socket } from 'net';

/**
 * Клиент консоли 7 Days to Die.
 *
 * ЭТО НЕ SOURCE RCON, И ПЕРЕИСПОЛЬЗОВАТЬ ТРАНСПОРТ MINECRAFT ЗДЕСЬ НЕЛЬЗЯ.
 * В serverconfig.xml, который поставляется с игрой, нет ни одного свойства со
 * словом rcon: удалённое администрирование — это встроенный telnet
 * (TelnetEnabled=true, TelnetPort=8081, TelnetPassword). Второй интерфейс,
 * WebDashboard на 8080, по умолчанию выключен и предназначен для просмотра, а
 * не для управления. Source RCON игра не реализует вовсе, и «RCON» в
 * документации хостеров — это то же самое telnet-подключение, названное
 * привычным словом.
 *
 * Отличия от RCON, из которых вытекает всё устройство этого файла:
 *
 *   1. НЕТ ОБРАМЛЕНИЯ. RCON — бинарный протокол с длиной пакета и id запроса,
 *      по которым ответ однозначно сопоставляется с командой. Здесь простой
 *      текстовый поток, и понять, где кончился ответ, нечем.
 *
 *   2. В ТОТ ЖЕ ПОТОК ИДЁТ ЖИВОЙ ЛОГ СЕРВЕРА. Между строками ответа приходят
 *      строки вида «2026-03-14T19:43:54 432.501 INF …» — чужие, не наши.
 *
 * Поэтому кадр выделяется двумя маркерами самого сервера: эхом команды
 * («INF Executing command 'lp' by Telnet from …») и намеренно неизвестной
 * командой-меткой после неё — на неё сервер отвечает строкой
 * «*** ERROR: unknown command '<метка>'». Всё между ними — ответ. Метка
 * случайная, поэтому два одновременных запроса не перепутаются.
 *
 * Пароль в этот файл приходит и уходит только в сокет: ни в логи, ни в
 * сообщения об ошибках он не попадает.
 */

/** Порт telnet по умолчанию из serverconfig.xml игры. */
export const SEVENDAYS_DEFAULT_PORT = 8081;

/** Строки рукопожатия — ровно те, что печатает сервер. */
const PROMPT_PASSWORD = 'Please enter password';
const AUTH_OK = 'Logon successful.';
const AUTH_FAILED = 'Password incorrect';

/** Консоль игры режет команды длиннее этого. */
const MAX_COMMAND_LENGTH = 1000;

/** Перевод строки у telnet — CRLF, а не \n. */
const CRLF = '\r\n';

export interface TelnetOptions {
  host: string;
  port: number;
  password: string;
  /** Сколько ждать соединения и ответа. */
  timeoutMs?: number;
}

/**
 * Одна команда — одно соединение.
 *
 * Постоянное соединение было бы экономнее, но у него две неприятные
 * особенности: сервер закрывает простаивающие сессии молча, а живой лог,
 * который всё это время капает в сокет, приходится куда-то девать. Панель
 * ходит к консоли редко и небольшими порциями — цена лишнего рукопожатия
 * здесь меньше, чем цена неверно склеенного ответа.
 */
export async function telnetCommand(options: TelnetOptions, command: string): Promise<string> {
  if (command.length > MAX_COMMAND_LENGTH) {
    throw new Error(`Команда длиннее ${MAX_COMMAND_LENGTH} символов`);
  }
  if (/[\r\n]/.test(command)) {
    // Перевод строки внутри команды — это вторая команда. Пропустить её
    // значило бы позволить выполнить что угодно там, где ожидался ник.
    throw new Error('Команда не может содержать перевод строки');
  }

  const timeoutMs = options.timeoutMs ?? 8_000;
  const marker = `aurum${Math.random().toString(36).slice(2, 10)}`;

  return new Promise<string>((resolve, reject) => {
    const socket = new Socket();
    let buffer = '';
    let authorised = false;
    let sent = false;
    let done = false;

    const finish = (error: Error | null, value?: string) => {
      if (done) return;
      done = true;
      socket.destroy();
      if (error) reject(error);
      else resolve(value ?? '');
    };

    const timer = setTimeout(
      () => finish(new Error('Сервер 7 Days to Die не ответил вовремя')),
      timeoutMs,
    );
    socket.setTimeout(timeoutMs);
    socket.on('timeout', () => finish(new Error('Сервер 7 Days to Die не ответил вовремя')));
    socket.on('error', (e) => finish(new Error(`Не удалось подключиться к консоли: ${e.message}`)));
    socket.on('close', () => {
      clearTimeout(timer);
      if (!done) finish(new Error('Сервер закрыл соединение до ответа'));
    });

    socket.on('data', (chunk) => {
      // Байты \x00 в потоке встречаются — они не часть текста.
      buffer += chunk.toString('utf8').replace(/\0/g, '');

      if (!authorised) {
        if (buffer.includes(AUTH_FAILED)) {
          return finish(new Error('Пароль telnet-консоли не подошёл'));
        }
        if (buffer.includes(AUTH_OK)) {
          authorised = true;
          buffer = '';
        } else if (buffer.includes(PROMPT_PASSWORD) && !sent) {
          sent = true;
          socket.write(options.password + CRLF);
        }
        return;
      }

      if (!buffer.includes(`unknown command '${marker}'`)) return;
      clearTimeout(timer);
      finish(null, extractResponse(buffer, command, marker));
    });

    socket.connect(options.port, options.host, () => {
      // Пустой пароль сервер не спрашивает — он пускает сразу, но только с
      // локального адреса. Отправляем команду и метку следом; если приглашение
      // всё-таки придёт, пароль уйдёт из обработчика data.
      setTimeout(() => {
        if (done) return;
        if (!authorised && !sent && options.password) {
          sent = true;
          socket.write(options.password + CRLF);
        }
        authorised = true;
        socket.write(command + CRLF);
        socket.write(marker + CRLF);
      }, 150);
    });
  });
}

/**
 * Вырезает из потока ответ на нашу команду.
 *
 * Границы: эхо команды, которое сервер печатает в лог сам, и строка про
 * неизвестную команду-метку. Строки живого лога, попавшие внутрь, отбрасываем
 * по их же префиксу — это не ответ, а то, что происходило на сервере в те же
 * секунды.
 *
 * Экспортируется ради тестов: разбор чужого текстового потока ломается тихо.
 */
export function extractResponse(raw: string, command: string, marker: string): string {
  // Резать нужно по границе СТРОКИ, а не по тексту метки: сама метка стоит в
  // середине строки «*** ERROR: unknown command '…'», и обрыв по ней оставил
  // бы в конце ответа огрызок «*** ERROR:».
  const at = raw.indexOf(`unknown command '${marker}'`);
  let body = raw;
  if (at !== -1) {
    const lineStart = raw.lastIndexOf('\n', at);
    body = lineStart === -1 ? '' : raw.slice(0, lineStart);
  }

  const lines = body.split(/\r?\n/);
  // Эхо команды — последняя её отметка перед ответом: если та же команда
  // выполнялась раньше в этом же куске потока, нам нужна свежая.
  const echo = `Executing command '${command}' by Telnet`;
  const echoAt = lines.map((l) => l.includes(echo)).lastIndexOf(true);
  const tail = echoAt === -1 ? lines : lines.slice(echoAt + 1);

  return tail
    .filter((line) => !isLogLine(line))
    .map((line) => line.trimEnd())
    .join('\n')
    .trim();
}

/**
 * Строка живого лога сервера, а не ответ на команду.
 *
 * Формат: «2026-03-14T19:43:54 432.501 INF …» — дата, секунды с запуска и
 * уровень. Уровни игра печатает свои: INF, WRN, ERR.
 */
export function isLogLine(line: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\s+[\d.]+\s+(INF|WRN|ERR)\b/.test(line.trim());
}
