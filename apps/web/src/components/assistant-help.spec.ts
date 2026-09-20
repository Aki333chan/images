import en from '../i18n/catalogs/en.json';
import pl from '../i18n/catalogs/pl.json';
import ru from '../i18n/catalogs/ru.json';
import {
  HELP_QUESTION_IDS,
  HELP_TOPICS,
  helpTopicFor,
  helpTopicHref,
  visibleHelpQuestions,
  visibleHelpTopics,
} from './assistant-help';

describe('contextual assistant help', () => {
  it('selects the open server tab instead of generic server help', () => {
    expect(helpTopicFor('/servers/abc', 'economy').titleKey).toBe('tab.economy');
    expect(helpTopicFor('/servers/abc', 'core:backups').titleKey).toBe('tab.backups');
  });

  it('uses the active settings section and safe fallbacks', () => {
    expect(helpTopicFor('/settings', 'notifications').titleKey).toBe('set.section.notifications');
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

  it('does not expose topics or dangerous answers without their permission', () => {
    const permissions = new Set(['servers.view', 'files.view', 'backups.view']);
    const hasPermission = (key: string) => permissions.has(key);
    const topicIds = visibleHelpTopics(hasPermission).map((topic) => topic.id);

    expect(topicIds).toContain('serverFiles');
    expect(topicIds).not.toContain('access');
    expect(topicIds).not.toContain('serverEconomy');

    const files = HELP_TOPICS.find((topic) => topic.id === 'serverFiles')!;
    expect(visibleHelpQuestions(files, hasPermission)).toEqual(['files.browse', 'general.safety']);
  });

  it('shows an administrator only the access help they can actually use', () => {
    const access = HELP_TOPICS.find((topic) => topic.id === 'access')!;
    const hasPermission = (key: string) => key === 'users.create.moderator';

    expect(visibleHelpQuestions(access, hasPermission)).toEqual([
      'access.createModerator',
      'general.permissions',
    ]);
  });

  it('builds exact server links only from an already open server', () => {
    const economy = HELP_TOPICS.find((topic) => topic.id === 'serverEconomy')!;
    expect(helpTopicHref(economy, '/help')).toBe('/servers');
    expect(helpTopicHref(economy, '/servers/server-1')).toBe('/servers/server-1?tab=economy');
  });
});
