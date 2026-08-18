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
 */

const BRAND = 'Aurum Panel';
const BG = '#0f0f12';
const CARD = '#17171c';
const BORDER = '#2a2a33';
const TEXT = '#e8e8ee';
const MUTED = '#9a9aa8';
const ACCENT = '#c026d3';

/** Экранирование: значения приходят от людей и попадают в HTML. */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function layout(title: string, inner: string): string {
  return `<!doctype html>
<html lang="ru">
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
          Письмо отправлено автоматически, отвечать на него не нужно.
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
}

/** Письмо с одноразовым паролем — единственное место, где он виден текстом. */
export function welcomeMail(input: WelcomeMailInput): { subject: string; html: string; text: string } {
  const login = escapeHtml(input.login);
  const password = escapeHtml(input.oneTimePassword);
  const url = escapeHtml(input.panelUrl);
  const intro = input.reset
    ? 'Ваш пароль в панели администрирования сброшен. Войдите по временному паролю ниже — ' +
      'сразу после входа панель попросит задать новый постоянный пароль.'
    : 'Для вас создана учётная запись в панели администрирования. Войти можно по ' +
      'временному паролю ниже — при первом входе панель попросит задать свой ' +
      'пароль и выбрать никнейм.';

  const html = layout(
    input.reset ? `Новый пароль к ${BRAND}` : `Доступ к ${BRAND}`,
    `
    <p style="margin:0 0 16px 0;">Здравствуйте!</p>
    <p style="margin:0 0 16px 0;">${escapeHtml(intro)}</p>
    <p style="margin:0 0 16px 0;color:${MUTED};font-size:13px;">
      Логин — этот адрес: ${login}
    </p>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
           style="margin:0 0 20px 0;background:${BG};border:1px solid ${BORDER};border-radius:8px;">
      <tr>
        <td style="padding:16px 18px;">
          <div style="font:400 12px/1.4 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${MUTED};padding-bottom:6px;">
            Временный пароль
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
            Войти в панель
          </a>
        </td>
      </tr>
    </table>

    <p style="margin:0 0 8px 0;color:${MUTED};font-size:13px;">
      Пароль действует ${input.expiresInHours} часа и работает один раз.
      Если не успеете — попросите выдать новый.
    </p>
    <p style="margin:0;color:${MUTED};font-size:13px;">
      Если вы не ожидали это письмо, просто удалите его: без пароля войти нельзя.
    </p>
    `,
  );

  // Текстовая версия обязательна: без неё письмо заметно чаще уходит в спам,
  // а часть клиентов HTML не показывает вовсе.
  const text = [
    'Здравствуйте!',
    '',
    intro,
    '',
    `Логин: ${input.login}`,
    `Временный пароль: ${input.oneTimePassword}`,
    `Адрес панели: ${input.panelUrl}`,
    '',
    `Пароль действует ${input.expiresInHours} часа и работает один раз.`,
    '',
    'Если вы не ожидали это письмо, просто удалите его.',
  ].join('\n');

  return {
    subject: input.reset ? `${BRAND}: новый пароль` : `${BRAND}: доступ к панели`,
    html,
    text,
  };
}
