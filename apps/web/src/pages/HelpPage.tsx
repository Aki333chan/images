import { useMemo, useState } from 'react';
import { useT } from '../i18n';
import { useAuth } from '../lib/auth';
import { Input } from '../components/ui';
import { IconSearch } from '../components/icons';
import { HelpQuestions } from '../components/HelpQuestions';
import {
  visibleHelpQuestions,
  visibleHelpTopics,
  type HelpTopic,
} from '../components/assistant-help';

const GROUPS = [
  'help.group.panel',
  'help.group.account',
  'help.group.server',
  'help.group.infrastructure',
] as const;

interface HelpResult {
  topic: HelpTopic;
  questionIds: string[];
}

const normalize = (value: string): string =>
  value
    .normalize('NFKD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase()
    .trim();

export function HelpPage() {
  const t = useT();
  const { hasPermission } = useAuth();
  const [search, setSearch] = useState('');

  const results = useMemo(() => {
    const needle = normalize(search);
    return visibleHelpTopics(hasPermission).flatMap<HelpResult>((topic) => {
      const questionIds = visibleHelpQuestions(topic, hasPermission);
      if (!needle) return [{ topic, questionIds }];

      const titleMatches = normalize(t(topic.titleKey)).includes(needle);
      const matches = titleMatches
        ? questionIds
        : questionIds.filter((id) =>
            normalize(`${t(`ai.help.faq.${id}.q`)} ${t(`ai.help.faq.${id}.a`)}`).includes(needle),
          );
      return matches.length > 0 ? [{ topic, questionIds: matches }] : [];
    });
  }, [hasPermission, search, t]);

  return (
    <div className="mx-auto max-w-4xl space-y-7">
      <header>
        <h1 className="text-xl font-bold">{t('help.title')}</h1>
        <p className="mt-1 max-w-[72ch] text-sm leading-6 text-muted">{t('help.description')}</p>
      </header>

      <div className="relative max-w-2xl">
        <label htmlFor="help-search" className="sr-only">
          {t('help.search')}
        </label>
        <IconSearch
          size={16}
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
        />
        <Input
          id="help-search"
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder={t('help.search')}
          className="pl-10"
        />
      </div>

      {results.length === 0 ? (
        <div className="rounded-lg border border-border px-4 py-8 text-center">
          <p className="font-medium">{t('help.noResults')}</p>
          <p className="mt-1 text-sm text-muted">{t('help.noResultsHint')}</p>
        </div>
      ) : (
        <div className="space-y-9">
          {GROUPS.map((groupKey) => {
            const group = results.filter((result) => result.topic.groupKey === groupKey);
            if (group.length === 0) return null;
            return (
              <section key={groupKey} aria-labelledby={`help-${groupKey}`}>
                <h2 id={`help-${groupKey}`} className="text-base font-semibold text-neutral-100">
                  {t(groupKey)}
                </h2>
                <div className="mt-2 divide-y divide-border">
                  {group.map(({ topic, questionIds }) => (
                    <article key={topic.id} className="py-4 first:pt-2">
                      <h3 className="mb-2 text-sm font-semibold text-primary-200">
                        {t(topic.titleKey)}
                      </h3>
                      <HelpQuestions topic={topic} pathname="/help" questionIds={questionIds} />
                    </article>
                  ))}
                </div>
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
