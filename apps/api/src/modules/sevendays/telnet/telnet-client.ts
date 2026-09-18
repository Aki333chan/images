import { Socket } from 'net';
import { randomBytes } from 'crypto';
import { StringDecoder } from 'string_decoder';

/**
 * Клиент консоли 7 Days to Die.
 *
 * ЭТО НЕ SOURCE RCON, И ПЕРЕИСПОЛЬЗОВАТЬ ТРАНСПОРТ MINECRAFT ЗДЕСЬ НЕЛЬЗЯ.
 * В serverconfig.xml, который поставляется с игрой, нет ни одного свойства со
 * словом rcon: удалённое администрирование — это встроенный telnet
 * (TelnetEnabled=true, TelnetPort=8081, TelnetPassword). Второй интерфейс,
 * WebDashboard на 8080 — отдельный HTTP API, не этот транспорт.
 * Source RCON игра не реализует, и «RCON» в
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

/** Стандартный Telnet-порт egg Pterodactyl / панели. */
export const SEVENDAYS_DEFAULT_PORT = 8081;

/** Строки рукопожатия — ровно те, что печатает сервер. */
const PROMPT_PASSWORD = 'Please enter password';
const AUTH_OK = 'Logon successful.';
const AUTH_FAILED = 'Password incorrect';
const READY_BANNER = "Press 'help' to get a list of all commands. Press 'exit' to end session.";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const CLOSE_GRACE_MS = 1000;

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
  if (!command.trim()) throw new Error('Команда не может быть пустой');
  if (command.length > MAX_COMMAND_LENGTH) {
    throw new Error(`Команда длиннее ${MAX_COMMAND_LENGTH} символов`);
  }
  if (/[\r\n]/.test(command)) {
    // Перевод строки внутри команды — это вторая команда. Пропустить её
    // значило бы позволить выполнить что угодно там, где ожидался ник.
    throw new Error('Команда не может содержать перевод строки');
  }
  if (command.includes('\0') || /[\r\n\0]/.test(options.password)) {
    throw new Error('Недопустимый управляющий символ в параметрах telnet');
  }

  const timeoutMs = options.timeoutMs ?? 8_000;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2_147_483_647) {
    throw new Error('Некорректный таймаут telnet');
  }
  const marker = `aurum${randomBytes(12).toString('hex')}`;
  // Require the entire marker reply line, not a substring in an echoed chat message.
  const endOfResponse = new RegExp(
    `(?:^|\\r?\\n)\\*\\*\\* ERROR: unknown command '${marker}'\\r?\\n`,
  );

  return new Promise<string>((resolve, reject) => {
    const socket = new Socket();
    const decoder = new StringDecoder('utf8');
    let buffer = '';
    let receivedBytes = 0;
    let passwordSent = false;
    let commandSent = false;
    let result: string | undefined;
    let done = false;
    let closeTimer: ReturnType<typeof setTimeout> | undefined;

    const finish = (error: Error | null, value?: string) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (closeTimer) clearTimeout(closeTimer);
      socket.destroy();
      if (error) reject(error);
      else resolve(value ?? '');
    };

    const timer = setTimeout(
      () =>
        finish(
          new Error(
            commandSent
              ? 'Сервер не подтвердил результат команды вовремя; не повторяйте её автоматически'
              : 'Сервер 7 Days to Die не завершил подключение или авторизацию вовремя',
          ),
        ),
      timeoutMs,
    );
    socket.on('error', () => {
      // A fully framed result remains valid even if graceful teardown fails.
      if (result !== undefined) finish(null, result);
      else
        finish(
          new Error(
            commandSent
              ? 'Соединение прервано до подтверждения результата команды; не повторяйте её автоматически'
              : 'Не удалось подключиться к telnet-консоли',
          ),
        );
    });
    socket.on('close', () => {
      if (result !== undefined) finish(null, result);
      else
        finish(
          new Error(
            commandSent
              ? 'Сервер закрыл соединение до подтверждения результата; не повторяйте команду автоматически'
              : 'Сервер закрыл соединение до авторизации',
          ),
        );
    });

    socket.on('data', (chunk) => {
      // Keep draining while the game processes exit. Do not grow a post-result buffer.
      if (done || result !== undefined) return;
      receivedBytes += chunk.length;
      if (receivedBytes > MAX_RESPONSE_BYTES) {
        return finish(
          new Error('Ответ telnet превысил допустимый размер; результат команды не подтверждён'),
        );
      }
      // Байты \x00 в потоке встречаются — они не часть текста.
      buffer += decoder.write(chunk).replace(/\0/g, '');

      if (!commandSent) {
        if (buffer.includes(AUTH_FAILED)) {
          return finish(new Error('Пароль telnet-консоли не подошёл'));
        }
        // A nonempty configured password must get an explicit authentication ACK.
        // Passwordless local sessions instead receive the game's welcome banner.
        if (
          buffer.includes(AUTH_OK) ||
          (!options.password && !passwordSent && buffer.includes(READY_BANNER))
        ) {
          commandSent = true;
          buffer = '';
          socket.write(command + CRLF + marker + CRLF);
        } else if (buffer.includes(PROMPT_PASSWORD) && !passwordSent) {
          if (!options.password) return finish(new Error('Telnet-сервер требует пароль'));
          passwordSent = true;
          socket.write(options.password + CRLF);
        }
        return;
      }

      if (!endOfResponse.test(buffer)) return;
      result = extractResponse(buffer, command, marker);
      buffer = '';
      clearTimeout(timer);
      // Do not destroy/end while the game is still writing. V3.2 TelnetConnection
      // consumes exit itself and closes the session before its next write cycle.
      closeTimer = setTimeout(() => finish(null, result), CLOSE_GRACE_MS);
      socket.write('exit' + CRLF);
    });

    try {
      socket.connect(options.port, options.host);
    } catch {
      finish(new Error('Не удалось открыть telnet-соединение'));
    }
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
  const allLines = raw.split(/\r?\n/);
  const markerAt = allLines.indexOf(`*** ERROR: unknown command '${marker}'`);
  const lines = markerAt === -1 ? allLines : allLines.slice(0, markerAt);
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
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\s+[\d.]+\s+(INF|WRN|ERR|EXC|DBG)\b/.test(
    line.trim(),
  );
}
