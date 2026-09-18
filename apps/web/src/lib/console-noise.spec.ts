import { backgroundCommandsFor, isPanelCommandEcho, isConsoleServiceLine } from '@aurum/shared';

const MC = backgroundCommandsFor('minecraft');

describe('7DTD Telnet service log filtering', () => {
  const line = (message: string, level = 'INF') =>
    `2026-09-18T12:04:45 205.234 ${level} ${message}`;
  it.each([
    'Telnet connection from: 10.0.0.1:60828',
    'Telnet connection closed: 10.0.0.1:60828',
    'Started thread TelnetClient_10.0.0.1:60828',
    'Exited thread TelnetClient_10.0.0.1:60828',
    'Started thread: Telnet client',
    'Telnet connection from: [::1]:60828',
    "Executing command 'lp' by Telnet from 10.0.0.1:60828",
    "Executing command 'LP' by Telnet from 10.0.0.1:60828",
    "Executing command 'gettime' by Telnet from 10.0.0.1:60828",
    "Executing command 'aurum0123456789abcdef01234567' by Telnet from 10.0.0.1:60828",
  ])('hides service line %s only for 7DTD', (message) => {
    expect(isConsoleServiceLine(line(message), 'sevendays')).toBe(true);
    expect(isConsoleServiceLine(line(message), 'minecraft')).toBe(false);
  });
  it.each([
    "Executing command 'shutdown' by Telnet from 127.0.0.1:60828",
    "Executing command 'say hello' by Telnet from 10.0.0.1:60828",
    "Executing command 'gso true' by Telnet from 10.0.0.1:60828",
    "Executing command 'unknown' by Telnet from 10.0.0.1:60828",
    'Chat: Player: Telnet connection closed: 10.0.0.1:60828',
    'Telnet connection closed: 10.0.0.1:60828 with error',
    'Exited thread SaveChunks',
    'IOException in TelnetClient_10.0.0.1:60828: socket has been shut down',
  ])('retains actions and diagnostic lines: %s', (message) => {
    expect(isConsoleServiceLine(line(message), 'sevendays')).toBe(false);
  });
  it.each(['WRN', 'ERR', 'EXC', 'DBG'])('retains %s even for a known command', (level) => {
    expect(
      isConsoleServiceLine(
        line("Executing command 'lp' by Telnet from 10.0.0.1:60828", level),
        'sevendays',
      ),
    ).toBe(false);
  });
  it('handles ANSI without altering the raw string', () => {
    const raw = `\x1b[32m${line('Telnet connection closed: 10.0.0.1:60828')}\x1b[0m`;
    expect(isConsoleServiceLine(raw, 'sevendays')).toBe(true);
    expect(raw).toContain('\x1b[32m');
  });
});

/**
 * Фильтр живёт в shared: список фоновых команд должен быть виден и бэкенду,
 * который их шлёт, и консоли, которая их прячет. Тест — здесь, потому что у
 * shared своего запуска тестов нет, а пользуется этим веб.
 */
describe('строки о собственных командах панели', () => {
  it('прячет опрос списка игроков в формате EssentialsX', () => {
    expect(
      isPanelCommandEcho('[15:04:22 INFO]: [Essentials] Rcon issued server command: /list', MC),
    ).toBe(true);
  });

  it('прячет ванильный формат той же строки', () => {
    expect(
      isPanelCommandEcho('[15:04:22 INFO]: [Rcon: Rcon issued server command: /list]', MC),
    ).toBe(true);
  });

  it('прячет остальные фоновые опросы', () => {
    for (const command of ['tps', 'mspt', 'version', 'whitelist list']) {
      expect(isPanelCommandEcho(`[Essentials] Rcon issued server command: /${command}`, MC)).toBe(
        true,
      );
    }
  });

  it('не зависит от регистра и от слэша', () => {
    expect(isPanelCommandEcho('[Essentials] RCON issued server command: LIST', MC)).toBe(true);
  });

  // --- главное: действия человека остаются видны

  it('оставляет команды, запущенные человеком', () => {
    const byHand = [
      'say Привет',
      'kick Steve грубость',
      'whitelist add Alex',
      'give Steve diamond 64',
      'stop',
      'pardon Steve',
    ];
    for (const command of byHand) {
      expect(isPanelCommandEcho(`[Essentials] Rcon issued server command: /${command}`, MC)).toBe(
        false,
      );
    }
  });

  it('различает whitelist list и whitelist add', () => {
    // Обе начинаются одинаково: сравнение по началу строки спрятало бы и
    // добавление в белый список, а это действие администратора.
    expect(isPanelCommandEcho('Rcon issued server command: /whitelist list', MC)).toBe(true);
    expect(isPanelCommandEcho('Rcon issued server command: /whitelist add Steve', MC)).toBe(false);
  });

  it('list не прячет listplayers', () => {
    expect(isPanelCommandEcho('Rcon issued server command: /listplayers', MC)).toBe(false);
  });

  it('не трогает обычные строки сервера', () => {
    expect(isPanelCommandEcho('[15:04:22 INFO]: Done (12.345s)! For help, type "help"', MC)).toBe(
      false,
    );
    expect(isPanelCommandEcho('<Steve> кто-нибудь видел мой list вещей?', MC)).toBe(false);
    expect(isPanelCommandEcho('[INFO]: Loaded plugin ShopList v1.2', MC)).toBe(false);
  });

  // --- набор зависит от модуля

  it('у 7 Days to Die свои фоновые команды', () => {
    const sd = backgroundCommandsFor('sevendays');
    expect(isPanelCommandEcho('Rcon issued server command: lp', sd)).toBe(true);
    expect(isPanelCommandEcho('Rcon issued server command: gettime', sd)).toBe(true);
    // А чужие — не его: tps на этом сервере никто фоном не шлёт.
    expect(isPanelCommandEcho('Rcon issued server command: tps', sd)).toBe(false);
  });

  it('lp из 7DTD не прячется на Minecraft', () => {
    expect(isPanelCommandEcho('Rcon issued server command: /lp user Steve info', MC)).toBe(false);
  });

  it('у модуля без опроса командами не прячется ничего', () => {
    const pw = backgroundCommandsFor('palworld');
    expect(pw).toEqual([]);
    expect(isPanelCommandEcho('Rcon issued server command: /list', pw)).toBe(false);
  });

  it('незнакомый модуль ничего не прячет', () => {
    expect(backgroundCommandsFor('test-dummy')).toEqual([]);
  });
});
