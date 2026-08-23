import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { SevenDaysConfigService } from './sevendays-config.service';
import { consoleError } from './sevendays-parsers';
import { telnetCommand } from './telnet/telnet-client';

/**
 * Единственная дверь к консоли игры.
 *
 * Всё, что модуль делает с сервером, проходит здесь, и по одной причине:
 * у telnet нет ни кода ответа, ни разделения аргументов. Команда — это
 * строка, и если склеивать её в каждом месте по-своему, рано или поздно
 * ник вида `Lost" ; shutdown` или ник с переводом строки выполнит чужую
 * команду. Поэтому аргументы собираются только через arg() ниже.
 *
 * Ни адрес, ни порт, ни пароль консоли в логи не пишутся — ни при успехе,
 * ни в тексте ошибки. Знание «где слушает консоль» само по себе половина
 * взлома, а сообщения об ошибках имеют привычку попадать в чужие глаза.
 */
@Injectable()
export class SevenDaysConsoleService {
  private readonly logger = new Logger(SevenDaysConsoleService.name);

  constructor(private readonly config: SevenDaysConfigService) {}

  /**
   * Выполняет команду и возвращает её вывод.
   *
   * Отказ консоли приходит обычной строкой, а не кодом — поэтому вывод
   * всегда проверяется на текст отказа. Без этого панель показала бы
   * «готово» там, где сервер ничего не сделал.
   */
  async run(serverId: string, command: string): Promise<string> {
    const creds = await this.config.require(serverId);

    let output: string;
    try {
      output = await telnetCommand(
        { host: creds.host, port: creds.port, password: creds.password },
        command,
      );
    } catch (e) {
      // Сообщение транспорта адреса не содержит — но команда могла бы
      // содержать ник, поэтому в лог идёт только первое слово.
      this.logger.warn(`Команда «${command.split(' ')[0]}» не выполнена: ${(e as Error).message}`);
      throw new BadRequestException((e as Error).message);
    }

    const failure = consoleError(output);
    if (failure) throw new BadRequestException(failure);

    await this.config.markSeen(serverId);
    return output;
  }

  /**
   * Как run, но отказ консоли не считается ошибкой запроса.
   *
   * Нужно для чтения состояния: если `gettime` не поддержан сборкой сервера,
   * панель должна показать прочерк вместо дня, а не отдать 400 на всю
   * страницу.
   */
  async tryRun(serverId: string, command: string): Promise<string | null> {
    try {
      return await this.run(serverId, command);
    } catch {
      return null;
    }
  }
}

/**
 * Аргумент команды.
 *
 * Консоль 7 Days to Die разбирает строку по пробелам, а составные значения
 * берёт в двойные кавычки. Своих кавычек внутри значения она не понимает
 * никак — ни экранирования, ни удвоения, — поэтому значение с кавычкой
 * отвергается, а не «чинится»: тихо изменённый ник забанил бы не того
 * человека.
 *
 * Перевод строки внутри аргумента — это вторая команда; транспорт такое
 * тоже не пропустит, но отказывать надо здесь, с понятным текстом.
 */
export function arg(value: string, label = 'Значение'): string {
  const trimmed = value.trim();
  if (trimmed === '') throw new BadRequestException(`${label} не может быть пустым`);
  if (/[\r\n]/.test(trimmed)) {
    throw new BadRequestException(`${label} не может содержать перевод строки`);
  }
  if (trimmed.includes('"')) {
    throw new BadRequestException(
      `${label} не может содержать двойные кавычки — консоль игры их не понимает`,
    );
  }
  // Кавычки ставим всегда: ник с пробелом иначе распался бы на два аргумента,
  // а ник без пробела от кавычек не страдает.
  return `"${trimmed}"`;
}

/** То же, но для необязательных значений: пустое просто исчезает из команды. */
export function optionalArg(value: string | undefined | null, label?: string): string | null {
  const trimmed = (value ?? '').trim();
  return trimmed === '' ? null : arg(trimmed, label);
}
