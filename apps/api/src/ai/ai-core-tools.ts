import { BadRequestException } from '@nestjs/common';
import {
  MINECRAFT_PERMISSIONS,
  type MinecraftEconomyRuleType,
  type MinecraftEconomyAuditSection,
} from '@aurum/shared';
import type { ToolDefinition } from './ai-tools.service';

const text = (args: Record<string, unknown>, key: string) => String(args[key] ?? '');
const server = { serverId: { type: 'string' } };
const ruleTypes = ['policy', 'exchange', 'starting_balance'];

/** Thin wrappers over the same Companion/Core endpoints used by the economy screen. */
export const AI_CORE_TOOLS: ToolDefinition[] = [
  {
    name: 'list_economy_accounts',
    kind: 'safe',
    permission: MINECRAFT_PERMISSIONS.economyView,
    description:
      'Счета AurumCore: игроки, гильдии, казны, арены и автоматы. Поиск, тип и постраничный список. Возвращает точные ключи профилей и роли счетов.',
    parameters: {
      type: 'object',
      properties: {
        ...server,
        search: { type: 'string' },
        accountType: { type: 'string' },
        offset: { type: 'integer', minimum: 0, maximum: 100000 },
      },
      required: ['serverId'],
    },
    summary: (_a, t) => t('ai.sum.accounts'),
    run: async (_ctx, a, d) => {
      const page = await d.companion.getManagedAccounts(text(a, 'serverId'), {
        search: text(a, 'search'),
        type: text(a, 'accountType'),
        offset: Number(a.offset ?? 0),
        limit: 10,
      });
      if (!page) throw new BadRequestException('ai.err.coreUnavailable');
      return { content: JSON.stringify(page), untrusted: true };
    },
  },
  {
    name: 'economy_account',
    kind: 'safe',
    permission: MINECRAFT_PERMISSIONS.economyView,
    description:
      'Детали одного профиля счёта Core: владелец, статус, роли, валюты, балансы и назначение. Ключ из list_economy_accounts.',
    parameters: {
      type: 'object',
      properties: { ...server, profileKey: { type: 'string', maxLength: 191 } },
      required: ['serverId', 'profileKey'],
    },
    summary: (a, t) => t('ai.sum.account', { account: text(a, 'profileKey') }),
    run: async (_ctx, a, d) => {
      const account = await d.companion.getManagedAccount(
        text(a, 'serverId'),
        text(a, 'profileKey'),
      );
      if (!account) throw new BadRequestException('ai.err.accountUnavailable');
      return { content: JSON.stringify(account), untrusted: true };
    },
  },
  {
    name: 'economy_rules',
    kind: 'safe',
    permission: MINECRAFT_PERMISSIONS.economyView,
    description:
      'Актуальные правила Core из БД: налоги/лимиты (policy), обмен валют (exchange) или стартовый баланс (starting_balance). Возвращает поля и ревизии, не меняет правила.',
    parameters: {
      type: 'object',
      properties: { ...server, ruleType: { type: 'string', enum: ruleTypes } },
      required: ['serverId', 'ruleType'],
    },
    summary: (_a, t) => t('ai.sum.rules'),
    run: async (_ctx, a, d) => {
      const rules = await d.companion.getEconomyRules(
        text(a, 'serverId'),
        text(a, 'ruleType') as MinecraftEconomyRuleType,
      );
      if (!rules) throw new BadRequestException('ai.err.coreUnavailable');
      return { content: JSON.stringify(rules.slice(0, 20)), untrusted: true };
    },
  },
  {
    name: 'economy_ledger',
    kind: 'safe',
    permission: MINECRAFT_PERMISSIONS.economyView,
    description:
      'Журнал операций и диагностика Core: проводки, налоги, обмены, резервы, claims. Для ledger можно указать технический account из роли профиля; не угадывай его.',
    parameters: {
      type: 'object',
      properties: {
        ...server,
        section: {
          type: 'string',
          enum: ['overview', 'ledger', 'policies', 'exchanges', 'holds', 'claims'],
        },
        currency: { type: 'string', maxLength: 32 },
        account: { type: 'string', maxLength: 160 },
      },
      required: ['serverId', 'section'],
    },
    summary: (_a, t) => t('ai.sum.ledger'),
    run: async (_ctx, a, d) => ({
      content: JSON.stringify(
        await d.minecraft.getEconomyAudit(
          text(a, 'serverId'),
          text(a, 'section') as MinecraftEconomyAuditSection,
          text(a, 'currency'),
          text(a, 'account'),
          10,
        ),
      ),
      untrusted: true,
    }),
  },
  {
    name: 'transfer_economy_accounts',
    kind: 'destructive',
    permission: MINECRAFT_PERMISSIONS.economyAdmin,
    description:
      'Принудительный перевод между выбранными счетами Core с причиной. Сначала прочти оба профиля: точные ключи, роли, валюту. Сумма строкой без округления. Требует подтверждения.',
    parameters: {
      type: 'object',
      properties: {
        ...server,
        sourceProfile: { type: 'string', maxLength: 191 },
        sourceRole: { type: 'string', maxLength: 32 },
        targetProfile: { type: 'string', maxLength: 191 },
        targetRole: { type: 'string', maxLength: 32 },
        currency: { type: 'string', maxLength: 32 },
        amount: { type: 'string', pattern: '^(?:0|[1-9]\\d{0,12})(?:\\.\\d{1,8})?$' },
        reason: { type: 'string', minLength: 3, maxLength: 255 },
      },
      required: [
        'serverId',
        'sourceProfile',
        'sourceRole',
        'targetProfile',
        'targetRole',
        'currency',
        'amount',
        'reason',
      ],
    },
    summary: (a, t) =>
      t('ai.sum.transferAccounts', {
        amount: text(a, 'amount'),
        currency: text(a, 'currency'),
        source: `${text(a, 'sourceProfile')}/${text(a, 'sourceRole')}`,
        target: `${text(a, 'targetProfile')}/${text(a, 'targetRole')}`,
        reason: text(a, 'reason'),
      }),
    run: async (ctx, a, d) => {
      if (!ctx.actionId) throw new BadRequestException('ai.err.confirmationRequired');
      const result = await d.companion.mutateManagedAccount(text(a, 'serverId'), {
        operation: 'transfer',
        idempotencyKey: ctx.actionId,
        profileKey: text(a, 'sourceProfile'),
        sourceRole: text(a, 'sourceRole'),
        secondaryProfile: text(a, 'targetProfile'),
        targetRole: text(a, 'targetRole'),
        currency: text(a, 'currency'),
        amount: text(a, 'amount'),
        actor: `ai:${ctx.userId}`,
        reason: text(a, 'reason'),
      });
      if (!result?.ok) throw new BadRequestException(result?.message || 'ai.err.coreUnavailable');
      return { content: JSON.stringify(result), untrusted: true };
    },
  },
  {
    name: 'change_economy_rule',
    kind: 'destructive',
    permission: MINECRAFT_PERMISSIONS.economyAdmin,
    description:
      'Изменить правило Core: налоги, лимиты, обмен или стартовый баланс. Сначала economy_rules. Передай полную копию полей с нужной правкой и ожидаемую ревизию. Панель проверяет preview, показывает до/после; применение только после подтверждения, без обхода через консоль.',
    parameters: {
      type: 'object',
      properties: {
        ...server,
        ruleType: { type: 'string', enum: ruleTypes },
        id: { type: 'string', maxLength: 64, pattern: '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$' },
        expectedRevision: { type: 'integer', minimum: 0 },
        fields: {
          type: 'object',
          maxProperties: 32,
          additionalProperties: { type: 'string', maxLength: 2000 },
        },
        reason: { type: 'string', minLength: 3, maxLength: 255 },
      },
      required: ['serverId', 'ruleType', 'id', 'expectedRevision', 'fields', 'reason'],
    },
    summary: (a, t) =>
      t('ai.sum.changeRule', {
        type: text(a, 'ruleType'),
        id: text(a, 'id'),
        reason: text(a, 'reason'),
      }),
    run: async (ctx, a, d) => {
      if (!ctx.actionId || typeof a._previewToken !== 'string')
        throw new BadRequestException('ai.err.confirmationRequired');
      const result = await d.companion.applyEconomyRule(text(a, 'serverId'), {
        token: a._previewToken,
        actor: `ai:${ctx.userId}`,
        reason: text(a, 'reason'),
      });
      if (!result || !['applied', 'applied_reload_failed'].includes(result.status)) {
        throw new BadRequestException(result?.message || 'ai.err.coreUnavailable');
      }
      return { content: JSON.stringify(result), untrusted: true };
    },
  },
];
