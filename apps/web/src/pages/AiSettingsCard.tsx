import { useEffect, useState } from 'react';
import { DEEPSEEK_MODELS, type AiSettingsDto, type AiToolInfoDto } from '@aurum/shared';
import { api } from '../lib/api';
import { Badge, Button, Card, ErrorText, Input, Label, Select, Spinner, Textarea } from '../components/ui';

/**
 * Настройки AI-ассистента на экране «Настройки» (виден только ГМ).
 *
 * Ключ API сюда не возвращается — только флаг «задан». Системный промпт
 * правится прямо здесь: это самый быстрый способ поменять поведение
 * ассистента, не трогая код.
 */
export function AiSettingsCard() {
  const [settings, setSettings] = useState<AiSettingsDto | null>(null);
  const [tools, setTools] = useState<AiToolInfoDto[]>([]);
  const [apiKey, setApiKey] = useState('');
  const [busy, setBusy] = useState(false);
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

  if (!settings) return error ? <Card><ErrorText>{error}</ErrorText></Card> : <Spinner />;

  async function save(patch: Partial<AiSettingsDto> & { apiKey?: string; clearApiKey?: boolean }) {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      const next = await api<AiSettingsDto>('/api/ai/settings', {
        method: 'PUT',
        body: JSON.stringify(patch),
      });
      setSettings(next);
      setApiKey('');
      setSaved('Сохранено.');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const destructive = tools.filter((t) => t.kind === 'destructive');
  const safe = tools.filter((t) => t.kind === 'safe');

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">AI-ассистент (DeepSeek)</h2>
        <Badge variant={settings.enabled && settings.hasApiKey ? 'success' : 'outline'}>
          {settings.enabled && settings.hasApiKey
            ? 'работает'
            : settings.enabled
              ? 'нет ключа'
              : 'выключен'}
        </Badge>
      </div>

      <label className="-mx-2 flex cursor-pointer items-start gap-3 rounded-md px-2 py-2 hover:bg-white/5">
        <input
          type="checkbox"
          className="mt-0.5 h-5 w-5 shrink-0 accent-primary"
          checked={settings.enabled}
          disabled={busy}
          onChange={(e) => void save({ enabled: e.target.checked })}
        />
        <span className="text-sm">
          Включить ассистента
          <span className="mt-1 block text-xs text-muted">
            Кнопка появится в углу панели у сотрудников с правом «ai.chat» (по умолчанию — у
            Админов и Модераторов). Обращения к DeepSeek платные.
          </span>
        </span>
      </label>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Модель</Label>
          <Select
            className="w-full"
            value={settings.model}
            onChange={(v) => void save({ model: v })}
            options={[
              ...DEEPSEEK_MODELS.map((m) => ({ value: m.value, label: m.label })),
              // Имя, которого нет в списке (новая модель), тоже показываем —
              // иначе выпадающий список молча подменил бы настройку.
              ...(DEEPSEEK_MODELS.some((m) => m.value === settings.model)
                ? []
                : [{ value: settings.model, label: `${settings.model} (задана вручную)` }]),
            ]}
          />
        </div>
        <div>
          <Label>Ключ API</Label>
          <div className="flex gap-2">
            <Input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              autoComplete="new-password"
              placeholder={settings.hasApiKey ? 'задан — введите для замены' : 'sk-…'}
            />
            {settings.hasApiKey && (
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => void save({ clearApiKey: true })}
              >
                Удалить
              </Button>
            )}
          </div>
          {apiKey && (
            <Button
              size="sm"
              className="mt-2"
              disabled={busy}
              onClick={() => void save({ apiKey })}
            >
              Сохранить ключ
            </Button>
          )}
        </div>
        <div>
          <Label>Лимит обращений в час на человека</Label>
          <Input
            type="number"
            defaultValue={settings.requestsPerHour}
            onBlur={(e) => {
              const value = Number(e.target.value);
              if (value > 0 && value !== settings.requestsPerHour) void save({ requestsPerHour: value });
            }}
          />
        </div>
        <div>
          <Label>Лимит токенов в сутки на человека</Label>
          <Input
            type="number"
            defaultValue={settings.tokensPerDay}
            onBlur={(e) => {
              const value = Number(e.target.value);
              if (value > 0 && value !== settings.tokensPerDay) void save({ tokensPerDay: value });
            }}
          />
        </div>
      </div>

      <div>
        <Label>Системный промпт</Label>
        <Textarea
          // text-base на мобильном обязателен: поле мельче 16 px заставляет
          // iOS зумить страницу при фокусе (см. раздел про телефон в README).
          // font-mono тут ради читаемости промпта, а не ради размера.
          className="min-h-[160px] font-mono text-base sm:text-xs"
          defaultValue={settings.systemPrompt}
          onBlur={(e) => {
            if (e.target.value !== settings.systemPrompt) void save({ systemPrompt: e.target.value });
          }}
        />
        <p className="mt-1 text-xs text-muted">
          Сохраняется при потере фокуса. Инструкция про недоверенные данные из игры здесь не
          лишняя, но и не единственная защита: разрушительные действия в любом случае требуют
          вашего подтверждения — это устроено в коде, а не в промпте.
        </p>
        <p className="mt-1 text-xs text-muted">
          Перечислять здесь доступные действия не нужно: панель добавляет к промпту отдельное
          сообщение с их точным списком — своим для каждого сотрудника, по его правам. Оттуда же
          берутся правила про идентификаторы. Стереть это правкой промпта нельзя.
        </p>
      </div>

      {error && <ErrorText>{error}</ErrorText>}
      {saved && <p className="text-xs text-emerald-400">{saved}</p>}

      {tools.length > 0 && (
        <div className="space-y-2 border-t border-border pt-3 text-xs text-muted">
          <p>
            <span className="font-semibold text-neutral-100">Выполняются сразу</span> (только
            читают): {safe.map((t) => t.name).join(', ')}.
          </p>
          <p>
            <span className="font-semibold text-amber-400">Требуют подтверждения</span> (меняют
            состояние): {destructive.map((t) => t.name).join(', ')}.
          </p>
          <p>
            Ассистент работает с правами того, кто с ним говорит: инструмент, на который у
            сотрудника нет права, ему не предлагается и не выполнится. Каждое выполненное
            действие попадает в журнал аудита с пометкой «ИИ» и указанием, от чьего имени.
          </p>
        </div>
      )}
    </Card>
  );
}
