import { isInstalled, latestRelease } from './addons-releases';

/** Релиз в том виде, в каком его отдаёт GitHub. Лишние поля опущены. */
function release(
  tag: string,
  publishedAt: string,
  assets: { name: string; browser_download_url: string }[] = [
    { name: 'AurumCompanion-1.0.0.jar', browser_download_url: 'https://example.invalid/a.jar' },
  ],
  extra: Record<string, unknown> = {},
) {
  return { tag_name: tag, published_at: publishedAt, assets, ...extra };
}

describe('выбор релиза аддона', () => {
  it('берёт релиз с нужным префиксом тега, а не первый попавшийся', () => {
    const found = latestRelease(
      [
        release('guilds-v2.0.0', '2026-09-05T00:00:00Z'),
        release('companion-v0.3.1', '2026-09-01T00:00:00Z'),
      ],
      'companion-v',
    );
    expect(found?.tag).toBe('companion-v0.3.1');
    expect(found?.version).toBe('0.3.1');
  });

  it('сравнивает по дате публикации, а не по строке тега', () => {
    // Строкой «0.10.0» меньше «0.9.0» — на этом ломаются самодельные
    // сравнения версий, и после десятого релиза панель начала бы ставить
    // девятый.
    const found = latestRelease(
      [
        release('companion-v0.9.0', '2026-01-01T00:00:00Z'),
        release('companion-v0.10.0', '2026-06-01T00:00:00Z'),
      ],
      'companion-v',
    );
    expect(found?.version).toBe('0.10.0');
  });

  it('пропускает черновики и предрелизы', () => {
    const found = latestRelease(
      [
        release('companion-v2.0.0', '2026-09-05T00:00:00Z', undefined, { draft: true }),
        release('companion-v1.9.0', '2026-09-04T00:00:00Z', undefined, { prerelease: true }),
        release('companion-v1.8.0', '2026-09-03T00:00:00Z'),
      ],
      'companion-v',
    );
    // Черновик виден только владельцу репозитория и по ссылке не скачается,
    // а предрелиз автор пометил как «ещё не для всех».
    expect(found?.version).toBe('1.8.0');
  });

  it('релиз без jar не останавливает поиск, а пропускается', () => {
    const found = latestRelease(
      [
        release('companion-v3.0.0', '2026-09-05T00:00:00Z', [
          { name: 'CHANGELOG.md', browser_download_url: 'https://example.invalid/c.md' },
        ]),
        release('companion-v2.9.0', '2026-09-04T00:00:00Z'),
      ],
      'companion-v',
    );
    expect(found?.version).toBe('2.9.0');
  });

  it('нет подходящего релиза — null, а не исключение', () => {
    expect(latestRelease([release('auth-v1.0.0', '2026-01-01T00:00:00Z')], 'companion-v')).toBeNull();
    expect(latestRelease([], 'companion-v')).toBeNull();
    // Ответ не массивом означает, что GitHub отдал ошибку объектом; падать
    // на этом нельзя — установка должна сказать «версии нет», а не 500.
    expect(latestRelease({ message: 'rate limit' }, 'companion-v')).toBeNull();
  });
});

describe('распознавание уже установленного', () => {
  it('находит плагин по началу имени файла', () => {
    expect(isInstalled('AurumCompanion', ['AurumCompanion-0.3.1.jar'])).toBe(true);
    expect(isInstalled('AurumCompanion', ['aurumcompanion.jar'])).toBe(true);
    // Регистр и знаки не важны: руками файл могли положить как угодно.
    expect(isInstalled('AurumGuilds', ['Aurum-Guilds_1.0.jar'])).toBe(true);
  });

  it('не путает разные наши плагины между собой', () => {
    expect(isInstalled('AurumAuth', ['AurumGuilds-1.0.jar', 'AurumCompanion-1.0.jar'])).toBe(false);
  });

  it('пустая папка — ничего не установлено', () => {
    expect(isInstalled('AurumCompanion', [])).toBe(false);
  });
});
