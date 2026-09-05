process.env.NODE_ENV = 'test';

import type { Locale } from '@aurum/shared';
import { generateOneTimePassword } from './account-provisioning.service';
import { I18nService } from '../i18n/i18n.service';
import { escapeHtml, welcomeMail } from '../mail/mail-templates';

describe('одноразовый пароль', () => {
  it('нужной длины и из безопасного алфавита', () => {
    const password = generateOneTimePassword();
    expect(password).toHaveLength(16);
    // Ни 0/O, ни 1/l/I: пароль набирают руками с экрана.
    expect(password).not.toMatch(/[0O1lI]/);
    expect(password).toMatch(/^[A-Za-z2-9]+$/);
  });

  it('не повторяется', () => {
    const seen = new Set(Array.from({ length: 200 }, () => generateOneTimePassword()));
    expect(seen.size).toBe(200);
  });

  it('использует весь алфавит, а не узкий его кусок', () => {
    // Ловит поломку генератора, при которой пароли внешне выглядят
    // случайными, но берутся из десятка символов.
    const chars = new Set(
      Array.from({ length: 100 }, () => generateOneTimePassword()).join(''),
    );
    expect(chars.size).toBeGreaterThan(40);
  });
});

describe('письмо с одноразовым паролем', () => {
  const input = {
    login: 'vasya@aurumgg.ovh',
    oneTimePassword: 'AbCd2345EfGh6789',
    panelUrl: 'https://manage.aurumgg.ovh',
    expiresInHours: 72,
    locale: 'ru' as const,
  };

  // Тот же переводчик, что и в бою: с настоящими словарями, а не заглушкой.
  // Заглушка, возвращающая ключ, прошла бы мимо забытого ключа и мимо формы
  // множественного числа — то есть мимо ровно того, что здесь и ломается.
  const i18n = new I18nService();
  const say =
    (locale: Locale) =>
    (key: string, values?: Record<string, string | number>) =>
      i18n.t(locale, key, values);
  const ru = say('ru');

  it('содержит пароль, ссылку и срок', () => {
    const mail = welcomeMail(input, ru);
    expect(mail.html).toContain('AbCd2345EfGh6789');
    expect(mail.html).toContain('https://manage.aurumgg.ovh');
    expect(mail.html).toContain('72');
    expect(mail.subject).toContain('Aurum Panel');
  });

  it('есть текстовая версия — без неё письмо чаще уходит в спам', () => {
    const mail = welcomeMail(input, ru);
    expect(mail.text).toContain('AbCd2345EfGh6789');
    expect(mail.text).not.toContain('<');
  });

  it('вёрстка табличная и со встроенными стилями: почтовики не знают flex', () => {
    const mail = welcomeMail(input, ru);
    expect(mail.html).toContain('<table');
    expect(mail.html).toContain('style=');
    expect(mail.html).not.toContain('display:flex');
    expect(mail.html).not.toContain('<link');
  });

  it('логин экранируется, а не попадает в разметку как есть', () => {
    const mail = welcomeMail({ ...input, login: '<script>alert(1)</script>' }, ru);
    expect(mail.html).not.toContain('<script>');
    expect(mail.html).toContain('&lt;script&gt;');
  });

  it('в письме есть логин — без имени только он и подсказывает, чей это доступ', () => {
    expect(welcomeMail(input, ru).text).toContain('vasya@aurumgg.ovh');
  });

  it('письмо про сброс говорит о сбросе и не зовёт выбирать ник', () => {
    const mail = welcomeMail({ ...input, reset: true }, ru);

    expect(mail.subject).toContain('новый пароль');
    expect(mail.text).toContain('сброшен');
    // Ник у такого сотрудника уже есть — просить придумать новый незачем.
    expect(mail.text).not.toContain('никнейм');
    expect(mail.html).not.toContain('никнейм');
  });

  it('письмо про новый аккаунт по-прежнему зовёт выбрать ник', () => {
    expect(welcomeMail(input, ru).html).toContain('никнейм');
  });

  it('язык письма — язык получателя, а не панели', () => {
    // Письмо читают в почте, где никакого Accept-Language нет: если язык не
    // выбран здесь, его не выберет никто, и полякам придёт русский текст.
    const pl = welcomeMail({ ...input, locale: 'pl' }, say('pl'));

    expect(pl.html).toContain('lang="pl-PL"');
    expect(pl.subject).toContain('dostęp do panelu');
    expect(pl.text).toContain('Hasło tymczasowe');
    expect(pl.text).not.toMatch(/[А-Яа-яЁё]/);

    const en = welcomeMail({ ...input, locale: 'en' }, say('en'));
    expect(en.html).toContain('lang="en-GB"');
    expect(en.text).not.toMatch(/[А-Яа-яЁё]/);
  });

  it('срок жизни пароля склоняется по числу часов', () => {
    // 72 часа и 1 час — разные формы в русском и польском. Зашитое «часа»
    // дало бы «1 часа» на первом же изменении константы.
    const one = welcomeMail({ ...input, expiresInHours: 1 }, ru);
    expect(one.text).toContain('1 час ');
    expect(welcomeMail({ ...input, expiresInHours: 72 }, ru).text).toContain('72 часа');
    expect(welcomeMail({ ...input, expiresInHours: 5 }, ru).text).toContain('5 часов');
  });
});

describe('escapeHtml', () => {
  it('закрывает все пять опасных символов', () => {
    expect(escapeHtml(`<>&"'`)).toBe('&lt;&gt;&amp;&quot;&#39;');
  });
});
