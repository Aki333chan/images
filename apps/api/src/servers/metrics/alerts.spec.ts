process.env.NODE_ENV = 'test';

import {
  DEFAULT_ALERT_SETTINGS,
  cpuUsage,
  formatCpu,
  memoryUsage,
  resourceTone,
} from '@aurum/shared';
import type { Locale } from '@aurum/shared';
import { decideAlert } from './server-metrics.service';
import { alertMail } from './alert-mail';
import { I18nService } from '../../i18n/i18n.service';
import { normalizeAlertSettings } from '../../settings/settings.service';

/**
 * Нормализация загрузки CPU и решение об алерте.
 *
 * ПОЧЕМУ ЭТО ПРОВЕРЯЕТСЯ ТАК ПОДРОБНО. Обе вещи ломаются молча: неверная
 * нормализация красит здоровый сервер в красный (и наоборот), а ошибка в
 * условии алерта либо заваливает почту, либо не присылает ничего. Ни то, ни
 * другое не видно на экране до тех пор, пока не станет поздно.
 */

describe('нормализация загрузки CPU', () => {
  it('лимит в 200 означает два ядра: 100% потребления это половина', () => {
    // Ровно тот случай, который панель раньше считала неверно: 100 сравнивалось
    // с порогом 90 и красилось в красный, хотя занята половина выделенного.
    const usage = cpuUsage(100, 200);
    expect(usage.percentOfLimit).toBe(50);
    expect(resourceTone(usage.percentOfLimit)).toBe('normal');
  });

  it('перегрузка видна там, где сырое число выглядит безобидно', () => {
    // 95 из лимита 100 — это почти потолок, и раньше это тоже определялось
    // случайно верно. А вот 380 из 400 сырым сравнением не поймать вовсе.
    expect(resourceTone(cpuUsage(95, 100).percentOfLimit)).toBe('bad');
    expect(resourceTone(cpuUsage(380, 400).percentOfLimit)).toBe('bad');
  });

  it('сервер с большим лимитом не красится по сырому числу', () => {
    // 150% на четырёх ядрах — это меньше сорока процентов выделенного.
    const usage = cpuUsage(150, 400);
    expect(Math.round(usage.percentOfLimit!)).toBe(38);
    expect(resourceTone(usage.percentOfLimit)).toBe('normal');
  });

  it('лимит 0 — это «без лимита», а не «нулевой потолок»', () => {
    const usage = cpuUsage(250, 0);
    expect(usage.unlimited).toBe(true);
    // Ни доли, ни цвета: потолка, относительно которого считать перегрузку,
    // не существует. Делить на ноль и показывать Infinity нельзя.
    expect(usage.percentOfLimit).toBeNull();
    expect(resourceTone(usage.percentOfLimit)).toBe('unknown');
  });

  it('лимит не задан вовсе — ведём себя как без лимита', () => {
    expect(cpuUsage(120, null).unlimited).toBe(true);
  });

  it('подпись показывает абсолютные цифры, а не только долю', () => {
    // «53%» не отвечает на вопрос «сколько это в ядрах» — отвечает подпись.
    expect(formatCpu(cpuUsage(107, 200))).toBe('107% из 200%');
    expect(formatCpu(cpuUsage(250, 0))).toBe('250% (без лимита)');
  });

  it('память считается по тому же правилу', () => {
    const gb = 1024 ** 3;
    expect(memoryUsage(3 * gb, 4 * gb).percentOfLimit).toBe(75);
    expect(memoryUsage(3 * gb, 0).unlimited).toBe(true);
  });

  it('мусор на входе не превращается в NaN на экране', () => {
    expect(cpuUsage(Number.NaN, 200).absolutePercent).toBe(0);
    expect(cpuUsage(-5, 200).absolutePercent).toBe(0);
  });
});

describe('решение об алерте', () => {
  const base = {
    threshold: 90,
    enabled: true,
    sustainedMinutes: 5,
    cooldownMinutes: 60,
    breachingSince: null as Date | null,
    lastNotifiedAt: null as Date | null,
    now: new Date('2026-08-24T12:00:00Z'),
  };
  const minutesAgo = (n: number) => new Date(base.now.getTime() - n * 60_000);

  it('ниже порога — письма нет и отметка сброшена', () => {
    const d = decideAlert({ ...base, percentOfLimit: 40 });
    expect(d.notify).toBe(false);
    expect(d.breachingSince).toBeNull();
  });

  it('первое превышение только запоминается, письма ещё нет', () => {
    const d = decideAlert({ ...base, percentOfLimit: 95 });
    expect(d.notify).toBe(false);
    expect(d.breachingSince).toEqual(base.now);
  });

  it('всплеск короче задержки письма не вызывает', () => {
    // Сервер в потолке две минуты из пяти — это запуск, а не авария.
    const d = decideAlert({ ...base, percentOfLimit: 99, breachingSince: minutesAgo(2) });
    expect(d.notify).toBe(false);
  });

  it('превышение дольше задержки — письмо уходит', () => {
    const d = decideAlert({ ...base, percentOfLimit: 99, breachingSince: minutesAgo(6) });
    expect(d.notify).toBe(true);
  });

  it('падение ниже порога обнуляет счётчик, а не приостанавливает его', () => {
    // Иначе «пять минут подряд» набирались бы из отдельных секундных
    // всплесков за целый день, и письмо приходило бы про несуществующую
    // непрерывную перегрузку.
    const dropped = decideAlert({ ...base, percentOfLimit: 20, breachingSince: minutesAgo(4) });
    expect(dropped.breachingSince).toBeNull();

    const again = decideAlert({ ...base, percentOfLimit: 99, breachingSince: null });
    expect(again.notify).toBe(false);
    expect(again.breachingSince).toEqual(base.now);
  });

  it('кулдаун не даёт слать повторно, пока проблема тянется', () => {
    const d = decideAlert({
      ...base,
      percentOfLimit: 99,
      breachingSince: minutesAgo(30),
      lastNotifiedAt: minutesAgo(10),
    });
    expect(d.notify).toBe(false);
    // Отметку о начале превышения при этом НЕ сбрасываем: проблема та же.
    expect(d.breachingSince).toEqual(minutesAgo(30));
  });

  it('после кулдауна затянувшаяся перегрузка напоминает о себе снова', () => {
    const d = decideAlert({
      ...base,
      percentOfLimit: 99,
      breachingSince: minutesAgo(200),
      lastNotifiedAt: minutesAgo(61),
    });
    expect(d.notify).toBe(true);
  });

  it('выключенные алерты не шлют ничего и не копят отметку', () => {
    const d = decideAlert({
      ...base,
      enabled: false,
      percentOfLimit: 99,
      breachingSince: minutesAgo(60),
    });
    expect(d.notify).toBe(false);
    expect(d.breachingSince).toBeNull();
  });

  it('порог не задан — по этому ресурсу не следим', () => {
    const d = decideAlert({ ...base, threshold: null, percentOfLimit: 99 });
    expect(d.notify).toBe(false);
  });

  it('без лимита алерта быть не может', () => {
    // percentOfLimit === null означает, что у сервера нет потолка. Считать по
    // нему перегрузку не от чего, и молчание здесь — единственный честный
    // ответ.
    const d = decideAlert({ ...base, percentOfLimit: null, breachingSince: minutesAgo(60) });
    expect(d.notify).toBe(false);
    expect(d.breachingSince).toBeNull();
  });

  it('ровно на пороге считается превышением', () => {
    // «Превышение порога 90» — это 90 и выше: иначе настройка «90» означала бы
    // на самом деле «91», и объяснить это человеку было бы нечем.
    const d = decideAlert({ ...base, percentOfLimit: 90, breachingSince: minutesAgo(6) });
    expect(d.notify).toBe(true);
  });
});

describe('настройки алертов', () => {
  it('дефолты разумные: 90% и пять минут', () => {
    expect(DEFAULT_ALERT_SETTINGS.cpuThresholdPercent).toBe(90);
    expect(DEFAULT_ALERT_SETTINGS.sustainedMinutes).toBe(5);
    expect(DEFAULT_ALERT_SETTINGS.cooldownMinutes).toBe(60);
  });

  it('по умолчанию выключены', () => {
    // Рассылка писем сотрудникам — то, что владелец панели включает
    // осознанно, а не обнаруживает по факту.
    expect(DEFAULT_ALERT_SETTINGS.enabled).toBe(false);
  });

  it('значения вне допустимого зажимаются, а не принимаются молча', () => {
    const s = normalizeAlertSettings({
      enabled: true,
      cpuThresholdPercent: 5000,
      memoryThresholdPercent: 1,
      sustainedMinutes: 0,
      cooldownMinutes: 999999,
    });
    expect(s.cpuThresholdPercent).toBe(100);
    expect(s.memoryThresholdPercent).toBe(50);
    expect(s.sustainedMinutes).toBe(1);
    expect(s.cooldownMinutes).toBe(24 * 60);
  });

  it('null сохраняется: это «не следить», а не пустое значение', () => {
    const s = normalizeAlertSettings({ cpuThresholdPercent: null });
    expect(s.cpuThresholdPercent).toBeNull();
    // А вот отсутствующие поля берут дефолт.
    expect(s.sustainedMinutes).toBe(DEFAULT_ALERT_SETTINGS.sustainedMinutes);
  });
});

describe('письмо об алерте', () => {
  const i18n = new I18nService();
  const say =
    (locale: Locale) =>
    (key: string, values?: Record<string, string | number>) =>
      i18n.t(locale, key, values);

  const input = {
    serverName: 'Выживание',
    type: 'cpu' as const,
    percentOfLimit: 187,
    thresholdPercent: 90,
    heldMinutes: 12,
    absolute: '187% из 200%',
    panelUrl: 'https://manage.aurumgg.ovh',
    serverId: 'srv-1',
    cooldownMinutes: 60,
    locale: 'ru' as const,
  };

  it('называет метрику, порог, длительность и абсолютные цифры', () => {
    // Процент от лимита сам по себе непонятен, если не знать лимита, а
    // письмо приходит не о всплеске, а о том, что так уже некоторое время.
    const mail = alertMail(input, say('ru'));

    expect(mail.text).toContain('Загрузка CPU');
    expect(mail.text).toContain('187% из 200%');
    expect(mail.text).toContain('90%');
    expect(mail.text).toContain('12 минут');
    expect(mail.html).toContain('https://manage.aurumgg.ovh/servers/srv-1');
  });

  it('язык письма — язык получателя', () => {
    const pl = alertMail({ ...input, locale: 'pl' }, say('pl'));

    expect(pl.html).toContain('lang="pl-PL"');
    expect(pl.subject).toContain('przeciążenie');
    // Подпись метрики тоже переводится: она приходит ключом из shared.
    expect(pl.text).toContain('Obciążenie CPU');
    // Название сервера — имя собственное, его переводить нельзя.
    expect(pl.text).toContain('Выживание');
  });

  it('длительность и задержка склоняются по числу минут', () => {
    const one = alertMail({ ...input, heldMinutes: 1, cooldownMinutes: 1 }, say('ru'));
    expect(one.text).toContain('1 минуту');
    expect(alertMail({ ...input, heldMinutes: 3 }, say('ru')).text).toContain('3 минуты');
  });

  it('вёрстка табличная: почтовые клиенты не знают flex', () => {
    const mail = alertMail(input, say('ru'));
    expect(mail.html).toContain('<table');
    expect(mail.html).not.toContain('display:flex');
  });
});
