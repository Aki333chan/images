import {
  MINECRAFT_FORGE_PERMISSIONS,
  MINECRAFT_NEOFORGE_PERMISSIONS,
  MINECRAFT_PERMISSIONS,
  PALWORLD_PERMISSIONS,
  SEVENDAYS_PERMISSIONS,
} from '@aurum/shared';

export type HelpAccess = string | readonly string[];

export interface HelpTopic {
  id: string;
  groupKey: string;
  titleKey: string;
  questionIds: readonly string[];
  /** Строка — обязательное право, массив — достаточно любого из прав. */
  access?: HelpAccess;
  /** Безопасная цель. Для вкладки сервера адрес уточняется текущим serverId. */
  href: string;
  serverTab?: string;
}

const questions = (value: string): string[] => value.slice(4).split('|');

const playerViewPermissions = [
  MINECRAFT_PERMISSIONS.playersView,
  MINECRAFT_FORGE_PERMISSIONS.playersView,
  MINECRAFT_NEOFORGE_PERMISSIONS.playersView,
  PALWORLD_PERMISSIONS.playersView,
  SEVENDAYS_PERMISSIONS.playersView,
] as const;

const banPermissions = [
  MINECRAFT_PERMISSIONS.ban,
  MINECRAFT_FORGE_PERMISSIONS.ban,
  MINECRAFT_NEOFORGE_PERMISSIONS.ban,
  PALWORLD_PERMISSIONS.ban,
  SEVENDAYS_PERMISSIONS.ban,
] as const;

const whitelistPermissions = [
  MINECRAFT_PERMISSIONS.whitelist,
  MINECRAFT_FORGE_PERMISSIONS.whitelist,
  MINECRAFT_NEOFORGE_PERMISSIONS.whitelist,
  SEVENDAYS_PERMISSIONS.whitelist,
] as const;

const configurePermissions = [
  MINECRAFT_PERMISSIONS.configure,
  MINECRAFT_FORGE_PERMISSIONS.configure,
  MINECRAFT_NEOFORGE_PERMISSIONS.configure,
  PALWORLD_PERMISSIONS.configure,
  SEVENDAYS_PERMISSIONS.configure,
] as const;

const quickActionPermissions = [
  MINECRAFT_PERMISSIONS.quickCommands,
  MINECRAFT_FORGE_PERMISSIONS.quickCommands,
  MINECRAFT_NEOFORGE_PERMISSIONS.quickCommands,
  PALWORLD_PERMISSIONS.quickActions,
  SEVENDAYS_PERMISSIONS.quickActions,
] as const;

const restrictedQuestionIds = questions('faq:server.power|console.command|console.quick');

const topics: Record<string, HelpTopic> = {
  general: {
    id: 'general',
    groupKey: 'help.group.panel',
    titleKey: 'ai.help.panel',
    questionIds: questions('faq:general.navigation|general.permissions|general.safety'),
    href: '/help',
  },
  servers: {
    id: 'servers',
    groupKey: 'help.group.panel',
    titleKey: 'nav.servers',
    questionIds: questions('faq:servers.open|servers.status|general.permissions'),
    access: 'servers.view',
    href: '/servers',
  },
  tickets: {
    id: 'tickets',
    groupKey: 'help.group.panel',
    titleKey: 'nav.tickets',
    questionIds: questions('faq:tickets.reply|tickets.status|general.permissions'),
    access: 'tickets.view',
    href: '/tickets',
  },
  messages: {
    id: 'messages',
    groupKey: 'help.group.panel',
    titleKey: 'nav.messages',
    questionIds: questions('faq:messages.use|messages.unread|general.permissions'),
    href: '/messages',
  },
  market: {
    id: 'market',
    groupKey: 'help.group.panel',
    titleKey: 'nav.market',
    questionIds: questions('faq:market.install|market.compatibility|general.safety'),
    access: 'minecraft.plugins.install',
    href: '/market',
  },
  access: {
    id: 'access',
    groupKey: 'help.group.panel',
    titleKey: 'nav.access',
    questionIds: questions(
      'faq:access.createModerator|access.roles|access.servers|general.permissions',
    ),
    access: ['users.create.moderator', 'users.manage'],
    href: '/access',
  },
  audit: {
    id: 'audit',
    groupKey: 'help.group.panel',
    titleKey: 'nav.audit',
    questionIds: questions('faq:audit.find|audit.details|general.permissions'),
    access: 'audit.view',
    href: '/audit',
  },
  security: {
    id: 'security',
    groupKey: 'help.group.account',
    titleKey: 'nav.security',
    questionIds: questions('faq:security.totp|security.sessions|general.safety'),
    href: '/security',
  },
  'settings.profile': {
    id: 'settings.profile',
    groupKey: 'help.group.account',
    titleKey: 'set.section.profile',
    questionIds: questions('faq:settings.profile|settings.language|security.totp'),
    href: '/settings?section=profile',
  },
  'settings.accounts': {
    id: 'settings.accounts',
    groupKey: 'help.group.account',
    titleKey: 'set.section.accounts',
    questionIds: questions('faq:settings.accounts|settings.approvals|access.roles'),
    access: 'users.manage',
    href: '/settings?section=accounts',
  },
  'settings.notifications': {
    id: 'settings.notifications',
    groupKey: 'help.group.account',
    titleKey: 'set.section.notifications',
    questionIds: questions('faq:settings.mail|settings.alerts|general.permissions'),
    access: 'users.manage',
    href: '/settings?section=notifications',
  },
  'settings.ai': {
    id: 'settings.ai',
    groupKey: 'help.group.account',
    titleKey: 'set.section.ai',
    questionIds: questions('faq:settings.ai|settings.aiLimits|general.safety'),
    access: 'users.manage',
    href: '/settings?section=ai',
  },
  serverOverview: {
    id: 'serverOverview',
    groupKey: 'help.group.server',
    titleKey: 'ai.help.server',
    questionIds: questions('faq:server.power|server.module|server.tabs'),
    access: 'servers.view',
    href: '/servers',
    serverTab: '',
  },
  serverConsole: {
    id: 'serverConsole',
    groupKey: 'help.group.server',
    titleKey: 'tab.console',
    questionIds: questions('faq:console.command|console.quick|general.safety'),
    access: 'servers.view',
    href: '/servers',
    serverTab: 'console',
  },
  serverPlayers: {
    id: 'serverPlayers',
    groupKey: 'help.group.server',
    titleKey: 'tab.players',
    questionIds: questions('faq:players.manage|players.details|general.permissions'),
    access: playerViewPermissions,
    href: '/servers',
    serverTab: 'playerList',
  },
  serverBans: {
    id: 'serverBans',
    groupKey: 'help.group.server',
    titleKey: 'tab.bans',
    questionIds: questions('faq:bans.manage|bans.scope|general.permissions'),
    access: banPermissions,
    href: '/servers',
    serverTab: 'banKick',
  },
  serverWhitelist: {
    id: 'serverWhitelist',
    groupKey: 'help.group.server',
    titleKey: 'tab.whitelist',
    questionIds: questions('faq:whitelist.manage|whitelist.access|general.permissions'),
    access: whitelistPermissions,
    href: '/servers',
    serverTab: 'whitelist',
  },
  serverGuilds: {
    id: 'serverGuilds',
    groupKey: 'help.group.server',
    titleKey: 'tab.guilds',
    questionIds: questions('faq:guilds.manage|guilds.money|general.permissions'),
    access: MINECRAFT_PERMISSIONS.guildsView,
    href: '/servers',
    serverTab: 'guilds',
  },
  serverEconomy: {
    id: 'serverEconomy',
    groupKey: 'help.group.server',
    titleKey: 'tab.economy',
    questionIds: questions('faq:economy.accounts|economy.adjust|general.safety'),
    access: MINECRAFT_PERMISSIONS.economyView,
    href: '/servers',
    serverTab: 'economy',
  },
  serverMap: {
    id: 'serverMap',
    groupKey: 'help.group.server',
    titleKey: 'sdtd.map.title',
    questionIds: questions('faq:map.use|map.layers|general.permissions'),
    access: SEVENDAYS_PERMISSIONS.mapView,
    href: '/servers',
    serverTab: 'worldMap',
  },
  serverSettings: {
    id: 'serverSettings',
    groupKey: 'help.group.server',
    titleKey: 'tab.settings',
    questionIds: questions('faq:module.setup|module.connection|general.safety'),
    access: configurePermissions,
    href: '/servers',
    serverTab: '__settings',
  },
  serverFiles: {
    id: 'serverFiles',
    groupKey: 'help.group.infrastructure',
    titleKey: 'tab.files',
    questionIds: questions('faq:files.browse|files.edit|files.upload|general.safety'),
    access: 'files.view',
    href: '/servers',
    serverTab: 'core:files',
  },
  serverBackups: {
    id: 'serverBackups',
    groupKey: 'help.group.infrastructure',
    titleKey: 'tab.backups',
    questionIds: questions('faq:backups.view|backups.create|backups.restore|general.safety'),
    access: 'backups.view',
    href: '/servers',
    serverTab: 'core:backups',
  },
  serverNetwork: {
    id: 'serverNetwork',
    groupKey: 'help.group.infrastructure',
    titleKey: 'tab.network',
    questionIds: questions('faq:network.allocations|network.primary|general.safety'),
    access: 'allocations.manage',
    href: '/servers',
    serverTab: 'core:network',
  },
  serverStartup: {
    id: 'serverStartup',
    groupKey: 'help.group.infrastructure',
    titleKey: 'tab.startup',
    questionIds: questions('faq:startup.variables|startup.command|general.safety'),
    access: 'startup.manage',
    href: '/servers',
    serverTab: 'core:startup',
  },
  serverDatabases: {
    id: 'serverDatabases',
    groupKey: 'help.group.infrastructure',
    titleKey: 'tab.databases',
    questionIds: questions('faq:databases.create|databases.credentials|general.safety'),
    access: 'databases.manage',
    href: '/servers',
    serverTab: 'core:databases',
  },
  serverSchedules: {
    id: 'serverSchedules',
    groupKey: 'help.group.infrastructure',
    titleKey: 'tab.schedules',
    questionIds: questions('faq:schedules.create|schedules.tasks|general.safety'),
    access: 'schedules.manage',
    href: '/servers',
    serverTab: 'core:schedules',
  },
};

/** Более опасные ответы скрыты отдельно от доступности всей вкладки. */
const questionAccess: Record<string, HelpAccess> = {
  'tickets.reply': 'tickets.respond',
  'access.createModerator': 'users.create.moderator',
  'access.roles': 'users.manage',
  'access.servers': 'users.manage',
  'settings.accounts': 'users.manage',
  'settings.approvals': 'users.manage',
  'settings.mail': 'users.manage',
  'settings.alerts': 'users.manage',
  'settings.ai': 'users.manage',
  'settings.aiLimits': 'users.manage',
  [restrictedQuestionIds[0]!]: 'servers.power',
  'server.module': 'servers.manage',
  [restrictedQuestionIds[1]!]: 'servers.power',
  [restrictedQuestionIds[2]!]: quickActionPermissions,
  'guilds.manage': MINECRAFT_PERMISSIONS.guildsManage,
  'economy.adjust': MINECRAFT_PERMISSIONS.economyAdmin,
  'module.setup': configurePermissions,
  'module.connection': configurePermissions,
  'files.edit': 'files.manage',
  'files.upload': 'files.manage',
  'backups.create': 'backups.manage',
  'backups.restore': 'backups.manage',
};

const routeTopics: Record<string, string> = {
  '/help': 'general',
  '/servers': 'servers',
  '/tickets': 'tickets',
  '/messages': 'messages',
  '/market': 'market',
  '/access': 'access',
  '/audit': 'audit',
  '/security': 'security',
};

const serverTabTopics: Record<string, string> = {
  console: 'serverConsole',
  playerList: 'serverPlayers',
  banKick: 'serverBans',
  whitelist: 'serverWhitelist',
  guilds: 'serverGuilds',
  economy: 'serverEconomy',
  worldMap: 'serverMap',
  __settings: 'serverSettings',
  'core:files': 'serverFiles',
  'core:backups': 'serverBackups',
  'core:network': 'serverNetwork',
  'core:startup': 'serverStartup',
  'core:databases': 'serverDatabases',
  'core:schedules': 'serverSchedules',
};

export const HELP_TOPICS = Object.values(topics);

export const HELP_QUESTION_IDS = [...new Set(HELP_TOPICS.flatMap((topic) => topic.questionIds))];

export function hasHelpAccess(
  access: HelpAccess | undefined,
  hasPermission: (permission: string) => boolean,
): boolean {
  if (!access) return true;
  return typeof access === 'string'
    ? hasPermission(access)
    : access.some((permission) => hasPermission(permission));
}

export function visibleHelpTopics(hasPermission: (permission: string) => boolean): HelpTopic[] {
  return HELP_TOPICS.filter((topic) => hasHelpAccess(topic.access, hasPermission));
}

export function visibleHelpQuestions(
  topic: HelpTopic,
  hasPermission: (permission: string) => boolean,
): string[] {
  return topic.questionIds.filter((id) => hasHelpAccess(questionAccess[id], hasPermission));
}

/**
 * Ссылка никогда не угадывает serverId: точная вкладка открывается только
 * с уже открытого доступного сервера. Из общего справочника сначала ведём к
 * списку серверов, где ACL снова отфильтрует доступные карточки.
 */
export function helpTopicHref(topic: HelpTopic, pathname: string): string {
  if (topic.serverTab === undefined) return topic.href;
  if (!/^\/servers\/[^/]+$/.test(pathname)) return '/servers';
  if (!topic.serverTab) return pathname;
  return `${pathname}?tab=${encodeURIComponent(topic.serverTab)}`;
}

/** Возвращает справку именно для экрана и вкладки, открытых при нажатии кнопки. */
export function helpTopicFor(pathname: string, activeTab: string | null): HelpTopic {
  if (/^\/servers\/[^/]+$/.test(pathname)) {
    return topics[serverTabTopics[activeTab ?? ''] ?? 'serverOverview']!;
  }
  if (pathname === '/settings') {
    return topics[`settings.${activeTab ?? 'profile'}`] ?? topics['settings.profile']!;
  }
  return topics[routeTopics[pathname] ?? 'general']!;
}
