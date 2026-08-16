process.env.NODE_ENV = 'test';

import { generateOneTimePassword } from './account-provisioning.service';
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
    displayName: 'Вася',
    oneTimePassword: 'AbCd2345EfGh6789',
    panelUrl: 'https://manage.aurumgg.ovh',
    expiresInHours: 72,
  };

  it('содержит пароль, ссылку и срок', () => {
    const mail = welcomeMail(input);
    expect(mail.html).toContain('AbCd2345EfGh6789');
    expect(mail.html).toContain('https://manage.aurumgg.ovh');
    expect(mail.html).toContain('72');
    expect(mail.subject).toContain('Aurum Panel');
  });

  it('есть текстовая версия — без неё письмо чаще уходит в спам', () => {
    const mail = welcomeMail(input);
    expect(mail.text).toContain('AbCd2345EfGh6789');
    expect(mail.text).not.toContain('<');
  });

  it('вёрстка табличная и со встроенными стилями: почтовики не знают flex', () => {
    const mail = welcomeMail(input);
    expect(mail.html).toContain('<table');
    expect(mail.html).toContain('style=');
    expect(mail.html).not.toContain('display:flex');
    expect(mail.html).not.toContain('<link');
  });

  it('имя пользователя экранируется, а не попадает в разметку как есть', () => {
    const mail = welcomeMail({ ...input, displayName: '<script>alert(1)</script>' });
    expect(mail.html).not.toContain('<script>');
    expect(mail.html).toContain('&lt;script&gt;');
  });
});

describe('escapeHtml', () => {
  it('закрывает все пять опасных символов', () => {
    expect(escapeHtml(`<>&"'`)).toBe('&lt;&gt;&amp;&quot;&#39;');
  });
});
