process.env.NODE_ENV = 'test';

import { KNOWN_PLUGINS } from '@aurum/shared';
import { I18nService } from '../../i18n/i18n.service';
import { MINECRAFT_QUICK_COMMANDS, NICKNAME_ARG_NAMES } from './quick-commands.config';

/**
 * Каталог быстрых действий — конфигурация, и ошибиться в ней легко:
 * опечатка в имени плагина не ломает сборку, а просто навсегда прячет кнопки.
 */
describe('каталог быстрых действий', () => {
  it('идентификаторы уникальны', () => {
    const ids = MINECRAFT_QUICK_COMMANDS.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('каждый плейсхолдер шаблона объявлен в args, и наоборот', () => {
    for (const command of MINECRAFT_QUICK_COMMANDS) {
      // Шаблон может быть парой команд — склеиваем для проверки плейсхолдеров.
      const template = Array.isArray(command.template)
        ? command.template.join(' ')
        : command.template;
      const placeholders = [...template.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
      const declared = command.args.map((a) => a.name);

      for (const placeholder of placeholders) {
        expect({ id: command.id, placeholder, declared }).toEqual({
          id: command.id,
          placeholder,
          declared: expect.arrayContaining([placeholder]),
        });
      }
      // Обратное тоже важно: объявленный, но не используемый аргумент —
      // поле в форме, которое никуда не попадёт.
      for (const name of declared) {
        expect(`${command.id}:${template}`).toContain(`{${name}}`);
      }
    }
  });

  it('имя плагина совпадает с одним из известных панели', () => {
    const knownIds = new Set(KNOWN_PLUGINS.map((p) => p.id));
    for (const command of MINECRAFT_QUICK_COMMANDS) {
      if (command.plugin === null) continue;
      expect({ id: command.id, plugin: command.plugin }).toEqual({
        id: command.id,
        plugin: expect.stringMatching(new RegExp(`^(${[...knownIds].join('|')})$`)),
      });
    }
  });

  // Регрессия на самую вероятную ошибку: в Bukkit EssentialsX зовётся
  // «Essentials», и написание «EssentialsX» спрятало бы все его кнопки.
  it('EssentialsX указан своим bukkit-именем Essentials', () => {
    const essentials = MINECRAFT_QUICK_COMMANDS.filter((c) => c.id.startsWith('ess-'));
    expect(essentials.length).toBeGreaterThan(0);
    for (const command of essentials) {
      expect(command.plugin).toBe('Essentials');
    }
    expect(MINECRAFT_QUICK_COMMANDS.some((c) => c.plugin === 'EssentialsX')).toBe(false);
  });

  it('есть стартовый набор EssentialsX', () => {
    const ids = MINECRAFT_QUICK_COMMANDS.map((c) => c.id);
    // Только то, чего в ванили НЕТ. gamemode и tp здесь намеренно
    // отсутствуют: EssentialsX перехватывает их прозрачно, и его копии были
    // побайтово теми же командами — см. следующий тест.
    for (const id of ['ess-heal', 'ess-god', 'ess-fly', 'ess-kit']) {
      expect(ids).toContain(id);
    }
  });

  it('нет двух кнопок с одинаковой командой', () => {
    // Регрессия на реальную жалобу: на сервере с EssentialsX человек видел
    // две одинаковые кнопки «Сменить режим игры» и «Телепорт к игроку» с
    // побайтово одинаковыми шаблонами и гадал, чем они отличаются. Ничем.
    //
    // Правильный вариант — ванильный: он работает на любом сервере и не
    // зависит от того, стоит ли плагин. Дублировать его командой плагина
    // можно только там, где плагин делает что-то ДРУГОЕ.
    const seen = new Map<string, string>();
    for (const command of MINECRAFT_QUICK_COMMANDS) {
      const key = (Array.isArray(command.template) ? command.template : [command.template]).join(
        ' && ',
      );
      expect({ id: command.id, sameAs: seen.get(key) ?? null }).toEqual({
        id: command.id,
        sameAs: null,
      });
      seen.set(key, command.id);
    }
  });

  it('подписи кнопок не повторяются', () => {
    // Одинаковая команда под разными подписями — уже проверено выше; здесь
    // обратный случай: разные команды под одной подписью так же непонятны.
    const labels = MINECRAFT_QUICK_COMMANDS.map((c) => c.labelKey);
    expect(new Set(labels).size).toBe(labels.length);
  });

  it('аргументы с ником названы так, чтобы попасть под валидацию ника', () => {
    // Если назвать поле «nickname», проверка ника молча не применится,
    // и в RCON-команду уедет что угодно.
    for (const command of MINECRAFT_QUICK_COMMANDS) {
      for (const arg of command.args) {
        if (!/player|who|nick/i.test(arg.labelKey)) continue;
        expect({ id: command.id, arg: arg.name }).toEqual({
          id: command.id,
          arg: expect.stringMatching(new RegExp(`^(${[...NICKNAME_ARG_NAMES].join('|')})$`)),
        });
      }
    }
  });

  // Ввод ника и режима руками — источник опечаток («adventrue», «Steeve»),
  // каждая из которых стоит одного невыполненного действия.
  it('режим игры задан закрытым списком, а не свободным вводом', () => {
    const withMode = MINECRAFT_QUICK_COMMANDS.filter((c) => c.args.some((a) => a.name === 'mode'));
    expect(withMode.length).toBeGreaterThan(0);
    for (const command of withMode) {
      const mode = command.args.find((a) => a.name === 'mode')!;
      expect({ id: command.id, options: mode.options?.map((o) => o.value) }).toEqual({
        id: command.id,
        options: ['survival', 'creative', 'adventure', 'spectator'],
      });
    }
  });

  it('у вариантов режима есть человеческие подписи', () => {
    const mode = MINECRAFT_QUICK_COMMANDS.find((c) => c.id === 'vanilla-gamemode')!.args.find(
      (a) => a.name === 'mode',
    )!;
    // Подпись — ключ словаря; проверяем, что он есть и что это не сам
    // машинный value, выданный за название.
    const i18n = new I18nService();
    for (const option of mode.options ?? []) {
      expect(option.labelKey).not.toBe(option.value);
      expect(i18n.known(option.labelKey)).toBe(true);
    }
  });

  it('действия, заметные для игрока, помечены как требующие подтверждения', () => {
    const byId = new Map(MINECRAFT_QUICK_COMMANDS.map((c) => [c.id, c]));
    for (const id of ['broadcast', 'ess-god', 'ess-fly', 'ess-kit']) {
      expect({ id, destructive: byId.get(id)?.destructive }).toEqual({ id, destructive: true });
    }
    // А безобидные — нет, иначе подтверждения обесценятся.
    expect(byId.get('save-all')?.destructive).toBe(false);
    expect(byId.get('ess-heal')?.destructive).toBe(false);
  });
});
