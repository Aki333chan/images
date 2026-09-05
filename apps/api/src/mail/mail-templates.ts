import { LOCALE_TAGS, type Locale } from '@aurum/shared';

/**
 * HTML-шаблоны писем.
 *
 * Вёрстка намеренно старомодная — таблицами и с инлайновыми стилями.
 * Почтовые клиенты (в первую очередь Outlook) не поддерживают ни flex, ни
 * grid, ни внешние таблицы стилей: современная разметка развалится ровно
 * там, где её никто не проверит.
 *
 * Логотип — текстовый: картинку пришлось бы где-то хостить и она всё равно
 * не показалась бы, пока получатель не разрешит загрузку изображений.
 *
 * ЯЗЫК ПИСЬМА — ЯЗЫК ПОЛУЧАТЕЛЯ, а не того, кто его вызвал. Письмо читают в
 * почте, вне панели, и никакого Accept-Language там нет: если не выбрать
 * язык здесь, его не выберет никто. Поэтому переводчик приходит аргументом
 * уже привязанным к нужному языку, а сам шаблон о языках ничего не знает.
 */

const BRAND = 'Aurum Panel';
const BG = '#0f0f12';
const CARD = '#17171c';
const BORDER = '#2a2a33';
const TEXT = '#e8e8ee';
const MUTED = '#9a9aa8';
const ACCENT = '#c026d3';

/** Переводчик, привязанный к языку получателя. */
export type MailTranslate = (key: string, values?: Record<string, string | number>) => string;

/** Экранирование: значения приходят от людей и попадают в HTML. */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Каркас письма.
 *
 * lang в <html> — не украшение: по нему почтовый клиент выбирает правила
 * переноса слов и решает, предлагать ли перевод письма.
 */
export function mailLayout(locale: Locale, t: MailTranslate, title: string, inner: string): string {
  return `<!doctype html>
<html lang="${LOCALE_TAGS[locale]}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
</head>
<body style="margin:0;padding:0;background:${BG};">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${BG};padding:32px 12px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
               style="max-width:520px;background:${CARD};border:1px solid ${BORDER};border-radius:12px;">
          <tr>
            <td style="padding:24px 28px 8px 28px;">
              <div style="font:700 20px/1.2 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${TEXT};">
                <span style="color:${ACCENT};">◆</span> ${BRAND}
              </div>
            </td>
          </tr>
          <tr>
            <td style="padding:8px 28px 28px 28px;font:400 14px/1.6 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${TEXT};">
              ${inner}
            </td>
          </tr>
        </table>
        <div style="max-width:520px;padding:16px 8px;font:400 12px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${MUTED};">
          ${escapeHtml(t('mail.auto'))}
        </div>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

export interface WelcomeMailInput {
  /** Логин — он же адрес получателя. Имён у сотрудников в панели нет. */
  login: string;
  oneTimePassword: string;
  panelUrl: string;
  expiresInHours: number;
  /**
   * true — это сброс пароля уже существующему сотруднику, а не новый аккаунт.
   * Разница не косметическая: человеку с уже выбранным ником незачем читать
   * «выберите никнейм», а человеку, который пароль не терял, важно понять,
   * что его сбросил ГМ.
   */
  reset?: boolean;
  /** Язык получателя. Определяет и текст, и атрибут lang. */
  locale: Locale;
}

/** Письмо с одноразовым паролем — единственное место, где он виден текстом. */
export function welcomeMail(
  input: WelcomeMailInput,
  t: MailTranslate,
): { subject: string; html: string; text: string } {
  const password = escapeHtml(input.oneTimePassword);
  const url = escapeHtml(input.panelUrl);
  const intro = t(input.reset ? 'mail.welcome.introReset' : 'mail.welcome.introNew');
  const validity = t('mail.welcome.validity', { count: input.expiresInHours });

  const html = mailLayout(
    input.locale,
    t,
    t(input.reset ? 'mail.welcome.titleReset' : 'mail.welcome.titleNew', { brand: BRAND }),
    `
    <p style="margin:0 0 16px 0;">${escapeHtml(t('mail.hello'))}</p>
    <p style="margin:0 0 16px 0;">${escapeHtml(intro)}</p>
    <p style="margin:0 0 16px 0;color:${MUTED};font-size:13px;">
      ${escapeHtml(t('mail.welcome.loginIs', { login: input.login }))}
    </p>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
           style="margin:0 0 20px 0;background:${BG};border:1px solid ${BORDER};border-radius:8px;">
      <tr>
        <td style="padding:16px 18px;">
          <div style="font:400 12px/1.4 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${MUTED};padding-bottom:6px;">
            ${escapeHtml(t('mail.welcome.tempPassword'))}
          </div>
          <div style="font:700 20px/1.3 SFMono-Regular,Consolas,Liberation Mono,Menlo,monospace;color:${TEXT};letter-spacing:1px;word-break:break-all;">
            ${password}
          </div>
        </td>
      </tr>
    </table>

    <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 20px 0;">
      <tr>
        <td style="background:${ACCENT};border-radius:8px;">
          <a href="${url}" style="display:inline-block;padding:11px 22px;font:600 14px/1 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#ffffff;text-decoration:none;">
            ${escapeHtml(t('mail.welcome.enter'))}
          </a>
        </td>
      </tr>
    </table>

    <p style="margin:0 0 8px 0;color:${MUTED};font-size:13px;">
      ${escapeHtml(validity)}
    </p>
    <p style="margin:0;color:${MUTED};font-size:13px;">
      ${escapeHtml(t('mail.welcome.unexpected'))}
    </p>
    `,
  );

  // Текстовая версия обязательна: без неё письмо заметно чаще уходит в спам,
  // а часть клиентов HTML не показывает вовсе.
  const text = [
    t('mail.hello'),
    '',
    intro,
    '',
    t('mail.welcome.loginIs', { login: input.login }),
    t('mail.welcome.passwordIs', { password: input.oneTimePassword }),
    t('mail.welcome.panelIs', { url: input.panelUrl }),
    '',
    validity,
    '',
    t('mail.welcome.unexpectedShort'),
  ].join('\n');

  return {
    subject: t(input.reset ? 'mail.welcome.subjectReset' : 'mail.welcome.subjectNew', {
      brand: BRAND,
    }),
    html,
    text,
  };
}
