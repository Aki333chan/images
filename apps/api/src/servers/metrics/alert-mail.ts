import { ALERT_TYPE_LABELS, type AlertType } from '@aurum/shared';
import { escapeHtml } from '../../mail/mail-templates';

const BRAND = 'Aurum Panel';
const BG = '#0f0f12';
const CARD = '#17171c';
const BORDER = '#2a2a33';
const TEXT = '#e8e8ee';
const MUTED = '#9a9aa8';
const ACCENT = '#c026d3';
const WARN = '#f59e0b';

export interface AlertMailInput {
  serverName: string;
  type: AlertType;
  /** Текущее значение в процентах ОТ ЛИМИТА сервера. */
  percentOfLimit: number;
  /** Порог, который был превышен, в тех же процентах от лимита. */
  thresholdPercent: number;
  /** Сколько минут превышение уже держится. */
  heldMinutes: number;
  /** Абсолютные цифры для строки под заголовком, уже отформатированные. */
  absolute: string;
  panelUrl: string;
  serverId: string;
  cooldownMinutes: number;
}

/**
 * Письмо о перегрузке.
 *
 * Вёрстка та же старомодная, что и у письма с паролем, и по той же причине:
 * Outlook не понимает ни flex, ни grid, ни внешних стилей.
 *
 * ЧТО ОБЯЗАТЕЛЬНО ЕСТЬ В ТЕКСТЕ. Процент от лимита сам по себе непонятен, если
 * не знать лимита, поэтому рядом всегда стоят абсолютные цифры — «187% из
 * 200%» отвечает на вопрос «сколько это в ядрах», на который «93%» не
 * отвечает. И длительность: письмо приходит не о всплеске, а о том, что так
 * уже некоторое время, и это надо сказать прямо.
 */
export function alertMail(input: AlertMailInput): {
  subject: string;
  html: string;
  text: string;
} {
  const label = ALERT_TYPE_LABELS[input.type];
  const percent = Math.round(input.percentOfLimit);
  const serverUrl = `${input.panelUrl.replace(/\/$/, '')}/servers/${input.serverId}`;

  const summary =
    `${label} на сервере «${input.serverName}» держится выше ${input.thresholdPercent}% ` +
    `от выделенного лимита уже ${input.heldMinutes} мин.`;

  const html = `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(`${BRAND}: перегрузка сервера`)}</title>
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
              <p style="margin:0 0 16px 0;">${escapeHtml(summary)}</p>

              <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
                     style="margin:0 0 20px 0;background:${BG};border:1px solid ${BORDER};border-radius:8px;">
                <tr>
                  <td style="padding:16px 18px;">
                    <div style="font:400 12px/1.4 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${MUTED};padding-bottom:6px;">
                      ${escapeHtml(label)}
                    </div>
                    <div style="font:700 22px/1.3 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${WARN};">
                      ${percent}% от лимита
                    </div>
                    <div style="font:400 13px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${MUTED};padding-top:4px;">
                      ${escapeHtml(input.absolute)}
                    </div>
                  </td>
                </tr>
              </table>

              <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 20px 0;">
                <tr>
                  <td style="background:${ACCENT};border-radius:8px;">
                    <a href="${escapeHtml(serverUrl)}" style="display:inline-block;padding:11px 22px;font:600 14px/1 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#ffffff;text-decoration:none;">
                      Открыть сервер в панели
                    </a>
                  </td>
                </tr>
              </table>

              <p style="margin:0;color:${MUTED};font-size:13px;">
                Повторное письмо по этому серверу придёт не раньше чем через
                ${input.cooldownMinutes} мин, даже если перегрузка продолжается.
                Порог и задержку настраивает ГМ в настройках панели.
              </p>
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

  const text = [
    summary,
    '',
    `${label}: ${percent}% от лимита (${input.absolute})`,
    `Сервер в панели: ${serverUrl}`,
    '',
    `Повторное письмо — не раньше чем через ${input.cooldownMinutes} мин.`,
  ].join('\n');

  return {
    subject: `${BRAND}: перегрузка «${input.serverName}» — ${label.toLowerCase()} ${percent}%`,
    html,
    text,
  };
}
