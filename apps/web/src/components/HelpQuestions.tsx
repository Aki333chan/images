import { Link } from 'react-router-dom';
import { useT } from '../i18n';
import { useAuth } from '../lib/auth';
import { IconCaretDown } from './icons';
import { helpTopicHref, visibleHelpQuestions, type HelpTopic } from './assistant-help';

export function HelpQuestions({
  topic,
  pathname,
  questionIds,
  onNavigate,
}: {
  topic: HelpTopic;
  pathname: string;
  questionIds?: readonly string[];
  onNavigate?: () => void;
}) {
  const t = useT();
  const { hasPermission } = useAuth();
  const allowed = questionIds ?? visibleHelpQuestions(topic, hasPermission);
  const href = helpTopicHref(topic, pathname);

  return (
    <div className="divide-y divide-border border-y border-border">
      {allowed.map((id) => (
        <details key={id} className="group">
          <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 rounded-sm py-3 text-sm font-medium transition-colors hover:text-primary-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 [&::-webkit-details-marker]:hidden">
            <span>{t(`ai.help.faq.${id}.q`)}</span>
            <IconCaretDown
              size={14}
              className="shrink-0 text-muted transition-transform duration-200 group-open:rotate-180"
            />
          </summary>
          <div className="max-w-[72ch] pb-3 pr-7 text-sm leading-6 text-muted">
            <p>{t(`ai.help.faq.${id}.a`)}</p>
            {href !== '/help' && (
              <Link
                to={href}
                onClick={onNavigate}
                className="mt-2 inline-flex min-h-8 items-center rounded-sm text-xs font-medium text-primary-200 underline decoration-primary/40 underline-offset-4 transition-colors hover:text-primary-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70"
              >
                {t(
                  topic.serverTab !== undefined && href === '/servers'
                    ? 'help.chooseServer'
                    : 'help.openSection',
                )}
              </Link>
            )}
          </div>
        </details>
      ))}
    </div>
  );
}
