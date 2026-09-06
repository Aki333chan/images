import { ALERT_TYPE_KEYS, type AlertType, type Locale } from '@aurum/shared';
import { escapeHtml, mailLayout, type MailTranslate } from '../../mail/mail-templates';

const BRAND = 'Aurum Panel';
const BG = '#0f0f12';
const BORDER = '#2a2a33';
const MUTED = '#9a9aa8';
const ACCENT = '#c026d3';
const WARN = '#f59e0b';

export interface AlertMailInput {
  serverName: string;
  type: AlertType;
  /** Язык получателя: письмо читают в почте, где Accept-Language взять негде. */
  locale: Locale;
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
export function alertMail(
  input: AlertMailInput,
  t: MailTranslate,
): {
  subject: string;
  html: string;
  text: string;
} {
  const label = t(ALERT_TYPE_KEYS[input.type]);
  const percent = Math.round(input.percentOfLimit);
  const serverUrl = `${input.panelUrl.replace(/\/$/, '')}/servers/${input.serverId}`;

  const summary = t('mail.alert.summary', {
    label,
    server: input.serverName,
    threshold: input.thresholdPercent,
    count: input.heldMinutes,
  });

  const html = mailLayout(
    input.locale,
    t,
    t('mail.alert.title', { brand: BRAND }),
    `
              <p style="margin:0 0 16px 0;">${escapeHtml(summary)}</p>

              <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
                     style="margin:0 0 20px 0;background:${BG};border:1px solid ${BORDER};border-radius:8px;">
                <tr>
                  <td style="padding:16px 18px;">
                    <div style="font:400 12px/1.4 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${MUTED};padding-bottom:6px;">
                      ${escapeHtml(label)}
                    </div>
                    <div style="font:700 22px/1.3 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:${WARN};">
                      ${escapeHtml(t('mail.alert.ofLimit', { percent }))}
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
                      ${escapeHtml(t('mail.alert.open'))}
                    </a>
                  </td>
                </tr>
              </table>

              <p style="margin:0;color:${MUTED};font-size:13px;">
                ${escapeHtml(t('mail.alert.cooldown', { count: input.cooldownMinutes }))}
              </p>
    `,
  );

  const text = [
    summary,
    '',
    `${label}: ${t('mail.alert.ofLimit', { percent })} (${input.absolute})`,
    t('mail.alert.serverLink', { url: serverUrl }),
    '',
    t('mail.alert.cooldownShort', { count: input.cooldownMinutes }),
  ].join('\n');

  return {
    // Подпись метрики в теме подставляется как есть: в русском она с большой
    // буквы посреди фразы смотрится хуже, но переводить регистр за язык
    // нельзя — в немецком существительные так и пишутся.
    subject: t('mail.alert.subject', {
      brand: BRAND,
      server: input.serverName,
      label,
      percent,
    }),
    html,
    text,
  };
}
