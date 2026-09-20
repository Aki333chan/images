export interface HelpTopic {
  titleKey: string;
  questionIds: readonly string[];
}

const questions = (value: string): string[] => value.slice(4).split('|');

const topics: Record<string, HelpTopic> = {
  general: {
    titleKey: 'ai.help.panel',
    questionIds: questions('faq:general.navigation|general.permissions|general.safety'),
  },
  servers: {
    titleKey: 'nav.servers',
    questionIds: questions('faq:servers.open|servers.status|general.permissions'),
  },
  tickets: {
    titleKey: 'nav.tickets',
    questionIds: questions('faq:tickets.reply|tickets.status|general.permissions'),
  },
  messages: {
    titleKey: 'nav.messages',
    questionIds: questions('faq:messages.use|messages.unread|general.permissions'),
  },
  market: {
    titleKey: 'nav.market',
    questionIds: questions('faq:market.install|market.compatibility|general.safety'),
  },
  access: {
    titleKey: 'nav.access',
    questionIds: questions('faq:access.roles|access.servers|general.permissions'),
  },
  audit: {
    titleKey: 'nav.audit',
    questionIds: questions('faq:audit.find|audit.details|general.permissions'),
  },
  security: {
    titleKey: 'nav.security',
    questionIds: questions('faq:security.totp|security.sessions|general.safety'),
  },
  'settings.profile': {
    titleKey: 'set.section.profile',
    questionIds: questions('faq:settings.profile|settings.language|security.totp'),
  },
  'settings.accounts': {
    titleKey: 'set.section.accounts',
    questionIds: questions('faq:settings.accounts|settings.approvals|access.roles'),
  },
  'settings.notifications': {
    titleKey: 'set.section.notifications',
    questionIds: questions('faq:settings.mail|settings.alerts|general.permissions'),
  },
  'settings.ai': {
    titleKey: 'set.section.ai',
    questionIds: questions('faq:settings.ai|settings.aiLimits|general.safety'),
  },
  serverOverview: {
    titleKey: 'ai.help.server',
    questionIds: questions('faq:server.power|server.module|server.tabs'),
  },
  serverConsole: {
    titleKey: 'tab.console',
    questionIds: questions('faq:console.command|console.quick|general.safety'),
  },
  serverPlayers: {
    titleKey: 'tab.players',
    questionIds: questions('faq:players.manage|players.details|general.permissions'),
  },
  serverBans: {
    titleKey: 'tab.bans',
    questionIds: questions('faq:bans.manage|bans.scope|general.permissions'),
  },
  serverWhitelist: {
    titleKey: 'tab.whitelist',
    questionIds: questions('faq:whitelist.manage|whitelist.access|general.permissions'),
  },
  serverGuilds: {
    titleKey: 'tab.guilds',
    questionIds: questions('faq:guilds.manage|guilds.money|general.permissions'),
  },
  serverEconomy: {
    titleKey: 'tab.economy',
    questionIds: questions('faq:economy.accounts|economy.adjust|general.safety'),
  },
  serverMap: {
    titleKey: 'sdtd.map.title',
    questionIds: questions('faq:map.use|map.layers|general.permissions'),
  },
  serverSettings: {
    titleKey: 'tab.settings',
    questionIds: questions('faq:module.setup|module.connection|general.safety'),
  },
  serverFiles: {
    titleKey: 'tab.files',
    questionIds: questions('faq:files.edit|files.upload|general.safety'),
  },
  serverBackups: {
    titleKey: 'tab.backups',
    questionIds: questions('faq:backups.create|backups.restore|general.safety'),
  },
  serverNetwork: {
    titleKey: 'tab.network',
    questionIds: questions('faq:network.allocations|network.primary|general.safety'),
  },
  serverStartup: {
    titleKey: 'tab.startup',
    questionIds: questions('faq:startup.variables|startup.command|general.safety'),
  },
  serverDatabases: {
    titleKey: 'tab.databases',
    questionIds: questions('faq:databases.create|databases.credentials|general.safety'),
  },
  serverSchedules: {
    titleKey: 'tab.schedules',
    questionIds: questions('faq:schedules.create|schedules.tasks|general.safety'),
  },
};

const routeTopics: Record<string, string> = {
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

export const HELP_QUESTION_IDS = [
  ...new Set(Object.values(topics).flatMap((topic) => topic.questionIds)),
];

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
