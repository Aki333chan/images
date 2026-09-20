import en from '../i18n/catalogs/en.json';
import pl from '../i18n/catalogs/pl.json';
import ru from '../i18n/catalogs/ru.json';
import { HELP_QUESTION_IDS, helpTopicFor } from './assistant-help';

describe('contextual assistant help', () => {
  it('selects the open server tab instead of generic server help', () => {
    expect(helpTopicFor('/servers/abc', 'economy').titleKey).toBe('tab.economy');
    expect(helpTopicFor('/servers/abc', 'core:backups').titleKey).toBe('tab.backups');
  });

  it('uses the active settings section and safe fallbacks', () => {
    expect(helpTopicFor('/settings', 'notifications').titleKey).toBe(
      'set.section.notifications',
    );
    expect(helpTopicFor('/unknown', null).titleKey).toBe('ai.help.panel');
  });

  it('has every question and answer in every panel language', () => {
    for (const catalog of [en, pl, ru] as Record<string, unknown>[]) {
      for (const id of HELP_QUESTION_IDS) {
        expect(catalog[`ai.help.faq.${id}.q`]).toBeTruthy();
        expect(catalog[`ai.help.faq.${id}.a`]).toBeTruthy();
      }
    }
  });
});
