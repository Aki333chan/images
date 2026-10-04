import { useEffect, useRef, useState } from 'react';
import { AI_MODELS, type AiProvider, type AiSettingsDto, type AiToolInfoDto } from '@aurum/shared';
import { api } from '../lib/api';
import {
  Badge,
  Button,
  Card,
  ErrorText,
  Input,
  Label,
  Select,
  Spinner,
  Textarea,
} from '../components/ui';
import { useT } from '../i18n';

/** GM only. Keys are write-only, separate for each provider. */
export function AiSettingsCard() {
  const t = useT();
  const [settings, setSettings] = useState<AiSettingsDto | null>(null);
  const [tools, setTools] = useState<AiToolInfoDto[]>([]);
  const [apiKey, setApiKey] = useState('');
  const [busy, setBusy] = useState(false);
  const saving = useRef(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  useEffect(() => {
    api<AiSettingsDto>('/api/ai/settings')
      .then(setSettings)
      .catch((e: Error) => setError(e.message));
    api<{ tools: AiToolInfoDto[] }>('/api/ai/tools')
      .then((r) => setTools(r.tools))
      .catch(() => setTools([]));
  }, []);
  useEffect(() => {
    if (!saved) return;
    const timer = window.setTimeout(() => setSaved(''), 5000);
    return () => window.clearTimeout(timer);
  }, [saved]);

  async function save(patch: Partial<AiSettingsDto> & { apiKey?: string; clearApiKey?: boolean }) {
    if (saving.current || !settings) return;
    saving.current = true;
    setBusy(true);
    setError('');
    setSaved('');
    try {
      const next = await api<AiSettingsDto>('/api/ai/settings', {
        method: 'PUT',
        body: JSON.stringify({ provider: settings.provider, ...patch }),
      });
      setSettings(next);
      setApiKey('');
      setSaved(t('set.ai.saved'));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      saving.current = false;
      setBusy(false);
    }
  }

  if (!settings)
    return error ? (
      <Card>
        <ErrorText>{error}</ErrorText>
      </Card>
    ) : (
      <Spinner />
    );
  const models = AI_MODELS[settings.provider];
  const limits = [
    'requestsPerHour',
    'tokensPerDay',
    'maxInputTokens',
    'maxOutputTokens',
    'dailyBudgetUsd',
    'monthlyBudgetUsd',
  ] as const;
  const labels = {
    requestsPerHour: 'set.ai.rateLimit',
    tokensPerDay: 'set.ai.tokenLimit',
    maxInputTokens: 'set.ai.inputLimit',
    maxOutputTokens: 'set.ai.outputLimit',
    dailyBudgetUsd: 'set.ai.dailyBudget',
    monthlyBudgetUsd: 'set.ai.monthlyBudget',
  };
  const bounds = {
    requestsPerHour: [1, 1000],
    tokensPerDay: [1000, 100000000],
    maxInputTokens: [4096, 131072],
    maxOutputTokens: [256, 16384],
    dailyBudgetUsd: [0.05, 1000],
    monthlyBudgetUsd: [0.05, 10000],
  };

  return (
    <Card className="space-y-3" aria-busy={busy}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{t('set.ai.title')}</h2>
        <Badge variant={settings.enabled && settings.hasApiKey ? 'success' : 'outline'}>
          {t(
            settings.enabled && settings.hasApiKey
              ? 'set.ai.working'
              : settings.enabled
                ? 'set.ai.noKey'
                : 'set.ai.disabled',
          )}
        </Badge>
      </div>
      <fieldset disabled={busy} className="min-w-0 space-y-3">
        <label className="-mx-2 flex cursor-pointer items-center gap-3 rounded-md px-2 py-2 hover:bg-white/5">
          <input
            type="checkbox"
            className="h-5 w-5 shrink-0 accent-primary"
            checked={settings.enabled}
            onChange={(e) => void save({ enabled: e.target.checked })}
          />
          <span className="text-sm">{t('set.ai.enable')}</span>
        </label>
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <label htmlFor="ai-provider" className="mb-1 block text-xs font-medium text-muted">
              {t('set.ai.provider')}
            </label>
            <Select
              id="ai-provider"
              className="w-full"
              value={settings.provider}
              onChange={(provider) => void save({ provider: provider as AiProvider })}
              options={[
                { value: 'deepseek', label: 'DeepSeek' },
                { value: 'gemini', label: 'Gemini' },
              ]}
            />
          </div>
          <div>
            <label htmlFor="ai-model" className="mb-1 block text-xs font-medium text-muted">
              {t('set.ai.model')}
            </label>
            <Select
              id="ai-model"
              className="w-full"
              value={settings.model}
              onChange={(model) => void save({ model })}
              options={[
                ...models.map((m) => ({ value: m.value, label: `${m.name} — ${t(m.noteKey)}` })),
                ...(models.some((m) => m.value === settings.model)
                  ? []
                  : [{ value: settings.model, label: settings.model }]),
              ]}
            />
          </div>
        </div>
        <div>
          <label htmlFor="ai-api-key" className="mb-1 block text-xs font-medium text-muted">
            {t('set.ai.key')} · {settings.provider === 'gemini' ? 'Gemini' : 'DeepSeek'}
          </label>
          <div className="flex gap-2">
            <Input
              id="ai-api-key"
              type="password"
              className="min-w-0"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              autoComplete="new-password"
              placeholder={settings.hasApiKey ? t('set.ai.keySet') : t('set.ai.keyEmpty')}
            />
            {settings.hasApiKey && (
              <Button size="sm" variant="outline" onClick={() => void save({ clearApiKey: true })}>
                {t('set.ai.keyDelete')}
              </Button>
            )}
          </div>
          {apiKey && (
            <Button size="sm" className="mt-2" onClick={() => void save({ apiKey })}>
              {t('set.ai.keySave')}
            </Button>
          )}
          <a
            className="mt-2 inline-block text-xs text-primary underline underline-offset-2"
            target="_blank"
            rel="noopener noreferrer"
            href={
              settings.provider === 'gemini'
                ? 'https://aistudio.google.com/api-keys'
                : 'https://platform.deepseek.com/api_keys'
            }
          >
            {t('set.ai.createKey')}
          </a>
          {settings.provider === 'gemini' && (
            <p className="mt-1 text-xs text-muted">{t('set.ai.geminiBilling')}</p>
          )}
        </div>

        <details className="rounded-md border border-border p-3">
          <summary className="cursor-pointer text-sm font-medium">{t('set.ai.limits')}</summary>
          <form
            key={limits.map((key) => settings[key]).join(':')}
            className="mt-3 space-y-3"
            onSubmit={(e) => {
              e.preventDefault();
              const data = new FormData(e.currentTarget);
              void save(Object.fromEntries(limits.map((key) => [key, Number(data.get(key))])));
            }}
          >
            <div className="grid gap-3 sm:grid-cols-2">
              {limits.map((key) => (
                <div key={key}>
                  <label
                    htmlFor={`ai-${key}`}
                    className="mb-1 block text-xs font-medium text-muted"
                  >
                    {t(labels[key])}
                  </label>
                  <Input
                    id={`ai-${key}`}
                    name={key}
                    type="number"
                    required
                    min={bounds[key]![0]}
                    max={bounds[key]![1]}
                    step={key.endsWith('Usd') ? '0.01' : '1'}
                    defaultValue={settings[key]}
                  />
                </div>
              ))}
            </div>
            <p className="text-xs text-muted">{t('set.ai.budgetHint')}</p>
            <Button type="submit" size="sm">
              {t('common.save')}
            </Button>
          </form>
        </details>
        <details className="rounded-md border border-border p-3">
          <summary className="cursor-pointer text-sm font-medium">{t('set.ai.prompt')}</summary>
          <form
            className="mt-3 space-y-2"
            onSubmit={(e) => {
              e.preventDefault();
              void save({
                systemPrompt: String(new FormData(e.currentTarget).get('prompt') ?? ''),
              });
            }}
          >
            <Label>{t('set.ai.prompt')}</Label>
            <Textarea
              name="prompt"
              aria-label={t('set.ai.prompt')}
              className="min-h-[160px] font-mono text-base sm:text-xs"
              defaultValue={settings.systemPrompt}
              maxLength={8000}
            />
            <Button type="submit" size="sm">
              {t('common.save')}
            </Button>
          </form>
        </details>
      </fieldset>
      {error && <ErrorText>{error}</ErrorText>}
      {saved && (
        <p role="status" className="text-xs text-emerald-400">
          {saved}
        </p>
      )}
      {tools.length > 0 && (
        <details className="border-t border-border pt-3 text-xs text-muted">
          <summary className="cursor-pointer font-medium">
            {t('set.ai.tools', { count: tools.length })}
          </summary>
          <div className="mt-2 space-y-2 break-words">
            <p>
              <span className="font-semibold text-neutral-100">{t('set.ai.safe')}</span>:{' '}
              {tools
                .filter((tool) => tool.kind === 'safe')
                .map((tool) => tool.name)
                .join(', ')}
            </p>
            <p>
              <span className="font-semibold text-amber-400">{t('set.ai.destructive')}</span>:{' '}
              {tools
                .filter((tool) => tool.kind === 'destructive')
                .map((tool) => tool.name)
                .join(', ')}
            </p>
            <p>{t('set.ai.rightsShort')}</p>
          </div>
        </details>
      )}
    </Card>
  );
}
