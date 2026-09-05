/**
 * Контракт модуля Minecraft (Paper) между apps/api и apps/web.
 * ВАЖНО: здесь намеренно нет ни RCON-пароля, ни хоста, ни адреса
 * companion-плагина — эти данные никогда не покидают бэкенд.
 */

export interface MinecraftPlayerDto {
  name: string;
  /** UUID приходит только от companion-плагина; по RCON `list` его нет. */
  uuid: string | null;
  /** Пинг в мс — только от companion-плагина. */
  ping: number | null;
  /** Здоровье и позиция — только от companion-плагина. */
  health: number | null;
  maxHealth: number | null;
  world: string | null;
  position: { x: number; y: number; z: number } | null;
}

export interface MinecraftPlayersResponse {
  players: MinecraftPlayerDto[];
  online: number;
  max: number | null;
  /** Откуда получены данные: разбор ответа RCON или структурированный плагин. */
  source: 'rcon' | 'companion';
}

/**
 * Игрок из истории сервера: все, кто когда-либо заходил.
 *
 * Основа — ванильный список Bukkit, поэтому сама запись есть всегда. А вот
 * три поля зависят от соседних плагинов, и null в них значит «неизвестно»,
 * а не «нет»:
 *
 * - `alias` — ник из EssentialsX. Нет плагина или ник не задан — null;
 * - `registered` — есть ли аккаунт в плагине авторизации. Без плагина
 *   делить список не на что, и панель показывает его одним куском;
 * - `lastSeen` — сервер не всегда помнит дату последнего входа.
 *
 * `op` — оператор ли игрок. Это ванильные данные (`isOp()`), они есть
 * на любом сервере и ни от каких плагинов не зависят.
 */
export interface MinecraftKnownPlayerDto {
  uuid: string;
  /** Настоящее игровое имя. */
  name: string;
  /** Ник из EssentialsX; null — нет плагина либо ник не задан. */
  alias: string | null;
  op: boolean;
  online: boolean;
  /** null — плагина авторизации нет. */
  registered: boolean | null;
  /** ISO-время последнего входа; null — сервер не помнит. */
  lastSeen: string | null;
}

export interface MinecraftKnownPlayersResponse {
  available: boolean;
  /** Почему список недоступен: нет companion-плагина либо он не ответил. */
  code?: 'no-companion' | 'plugin-unreachable';
  reason?: string;
  docsUrl?: string;
  players: MinecraftKnownPlayerDto[];
  /** Сколько записей нашлось всего — постранично отдаётся часть. */
  total: number;
  /**
   * Стоит ли на сервере плагин авторизации.
   *
   * false — панель НЕ делит список на зарегистрированных и нет: делить не
   * по чему, и выдуманное деление врало бы. Это ожидаемое состояние, а не
   * поломка.
   */
  authAvailable: boolean;
}

/** Один известный адрес игрока. */
export interface MinecraftPlayerIpDto {
  ip: string;
  firstSeen: string;
  lastSeen: string;
}

/**
 * Известные адреса игрока.
 *
 * Историю ведёт плагин авторизации; без него смотреть нечего, и это
 * ожидаемо — ванильный сервер адреса не хранит.
 */
export interface MinecraftPlayerIpsResponse {
  available: boolean;
  code?: 'no-companion' | 'requires-auth' | 'plugin-unreachable';
  reason?: string;
  docsUrl?: string;
  addresses: MinecraftPlayerIpDto[];
}

export interface MinecraftBanDto {
  id: string;
  serverId: string;
  playerName: string;
  playerUuid: string | null;
  reason: string;
  /** null — бан навсегда. */
  expiresAt: string | null;
  createdAt: string;
  createdByName: string | null;
  pardonedAt: string | null;
  pardonedByName: string | null;
  /** false, если бан снят или истёк срок. */
  active: boolean;
}

export interface MinecraftWhitelistResponse {
  players: string[];
}

export interface MinecraftQuickCommandArg {
  name: string;
  labelKey: string;
  required: boolean;
  /** Пример значения. Ключ — когда пример на языке, «Steve» — когда это ник. */
  placeholder?: string;
  placeholderKey?: string;
  /**
   * Как готовить значение перед подстановкой в шаблон.
   *
   * 'json' — значение попадает внутрь JSON-литерала команды (например, в
   * текстовый компонент /title). Без экранирования кавычка в тексте
   * разорвала бы JSON, и сервер отверг бы всю команду.
   */
  escape?: 'json';
  /**
   * Закрытый список допустимых значений — панель рисует выпадающий список.
   * Ровно то, что нужно режиму игры: вариантов четыре, и вводить их руками
   * значит регулярно опечатываться в «adventure».
   */
  options?: { value: string; labelKey: string }[];
  /**
   * Что подсказывать при вводе. 'online-players' — ники тех, кто сейчас
   * в сети.
   *
   * Именно подсказка, а не закрытый список: список онлайна берётся с
   * игрового сервера и может не получиться (RCON молчит, сервер
   * перезапускается). Строгий выпадающий список в этом случае заблокировал
   * бы действие целиком, а подсказка просто не появится — ник можно
   * дописать руками.
   */
  suggest?: 'online-players';
}

export interface MinecraftQuickCommandDto {
  id: string;
  /**
   * Ключи словаря панели, а не готовые подписи.
   *
   * Набор команд один на всех, а язык у каждого свой; собрать фразу на
   * сервере значило бы выбрать язык за того, кто нажимает кнопку.
   */
  labelKey: string;
  descriptionKey: string;
  /** Право, необходимое для запуска (кроме него всегда нужен доступ к серверу). */
  permission: string;
  args: MinecraftQuickCommandArg[];
  /**
   * Плагин, командой которого является действие. `null` — ванильная команда,
   * работает везде. Иначе кнопка показывается, только если этот плагин
   * действительно установлен на сервере (см. MinecraftPluginsDto).
   */
  plugin: string | null;
  /** Показать подтверждение перед запуском: действие заметно для игрока. */
  destructive: boolean;
}

/** Плагины, о которых панель знает и умеет что-то полезное. */
export interface KnownPluginDto {
  /** Имя, под которым плагин регистрируется в Bukkit. */
  id: string;
  /** Человеческое название — оно нередко другое, чем id. */
  displayName: string;
  /**
   * Что панель умеет, если плагин установлен, — ключ словаря, а не текст.
   *
   * Строку собирает браузер: список плагинов одинаков для всех, а язык у
   * каждого свой, и переводить его на сервере значило бы решать за читателя.
   */
  givesKey: string;
  installed: boolean;
  /** Версия с сервера; null, если не установлен. */
  version: string | null;
}

export interface MinecraftPluginsDto {
  /**
   * false — companion-плагин не настроен, список получить неоткуда.
   * Тогда known заполнен, но installed везде false, и это честно показано.
   */
  available: boolean;
  reason?: string;
  /** Всё, что стоит на сервере. Пусто, если available = false. */
  installed: { name: string; version: string; enabled: boolean }[];
  /** Плагины, поддерживаемые панелью, с отметкой «есть/нет». */
  known: KnownPluginDto[];
}

/** Права игрока, как их отдаёт LuckPerms. */
export interface MinecraftPermissionsDto {
  available: boolean;
  /** Причина недоступности: нет companion-плагина либо нет LuckPerms. */
  reason?: string;
  code?: 'no-companion' | 'requires-luckperms' | 'error';
  primaryGroup?: string;
  groups?: string[];
  permissions?: { permission: string; value: boolean }[];
}

/** Одно изменение прав: панель шлёт их по одному, чтобы аудит был читаемым. */
export interface MinecraftPermissionChangeDto {
  kind: 'group' | 'permission';
  key: string;
  value?: boolean;
  remove?: boolean;
}

export interface MinecraftCommandResultDto {
  /** Ответ сервера на команду (может быть пустым). */
  output: string;
}

export interface MinecraftInventoryItemDto {
  slot: number;
  id: string;
  count: number;
  displayName: string | null;
  /** Зачарования: ключ (напр. minecraft:sharpness) -> уровень. */
  enchantments: Record<string, number>;
  lore: string[];
}

/**
 * Почему инвентарь недоступен. Машиночитаемый код нужен, чтобы интерфейс не
 * путал «плагин не установлен» (показываем инструкцию) с «игрок сейчас офлайн»
 * (обычное сообщение).
 */
export type MinecraftInventoryUnavailableCode =
  'no-plugin' | 'player-offline' | 'plugin-unreachable';

/** Доступен ли инвентарь на этом сервере — без секретов, для любой роли с правом просмотра. */
export interface MinecraftInventoryStatusDto {
  companionConfigured: boolean;
  docsUrl: string;
}

export interface MinecraftInventoryResponse {
  available: boolean;
  code?: MinecraftInventoryUnavailableCode;
  /** Причина недоступности, если available = false. */
  reason?: string;
  docsUrl?: string;
  player?: string;
  /** Основной инвентарь: 36 слотов (0-8 — хотбар). */
  items?: MinecraftInventoryItemDto[];
  /** Броня: 4 слота. */
  armor?: MinecraftInventoryItemDto[];
  offhand?: MinecraftInventoryItemDto | null;
}

/**
 * Строка списка выдачи.
 *
 * Идентификатор — как его знает сам сервер (`minecraft:stone` либо просто
 * `stone`), сверяет его игровой сервер, а не панель: список материалов зависит
 * от версии и установленных модов, и зашитый в панель перечень устарел бы к
 * следующему обновлению.
 */
export interface MinecraftGiveItemDto {
  id: string;
  count: number;
}

/**
 * Итог по одной строке выдачи.
 *
 * Построчно, а не общим «получилось/не получилось»: список выдают целиком, и
 * частичный успех тут норма — два предмета легли, третий не поместился, у
 * четвёртого опечатка. Общий ответ заставил бы гадать, какая строка виновата.
 */
export interface MinecraftGiveResultDto {
  id: string;
  requested: number;
  given: number;
  /** null — строка выдана полностью. */
  error: string | null;
}

export interface MinecraftGiveResponse {
  results: MinecraftGiveResultDto[];
}

/**
 * Что очистить в инвентаре.
 *
 * `all` — отдельным полем, а не «пустой выбор значит всё»: разница между
 * «стереть выбранное» и «стереть весь инвентарь» необратима, и умолчание в
 * такой операции рано или поздно сотрёт лишнее.
 *
 * Броня нумеруется своим индексом 0-3 в порядке Bukkit (ботинки, поножи,
 * нагрудник, шлем) — тем же, в каком она приходит в `armor` инвентаря.
 */
export interface MinecraftInventoryClearDto {
  all?: boolean;
  slots?: number[];
  armor?: number[];
  offhand?: boolean;
}

/** Статус настройки модуля. Секреты сюда не попадают — только флаги. */
export interface MinecraftConfigStatusDto {
  /** Настроен ли RCON (хост/порт/пароль сохранены). */
  rconConfigured: boolean;
  /** Настроен ли companion-плагин (нужен для инвентаря и UUID/пинга). */
  companionConfigured: boolean;
  /** Последняя успешная RCON-команда, ISO-строка. */
  lastSeenAt: string | null;
}

/**
 * Сторонние плагины, с которыми панель умеет работать.
 *
 * id — имя, под которым плагин регистрируется в Bukkit, и оно совпадает не
 * всегда: EssentialsX зовётся Essentials (наследие старого Essentials),
 * InvSee++ — InvSeePlusPlus. Сверка идёт именно по id, поэтому менять их
 * нельзя, не сверившись с plugin.yml соответствующего плагина.
 */
export const KNOWN_PLUGINS = [
  {
    id: 'LuckPerms',
    displayName: 'LuckPerms',
    givesKey: 'mc.plugin.gives.luckperms',
  },
  {
    id: 'Essentials',
    displayName: 'EssentialsX',
    givesKey: 'mc.plugin.gives.essentials',
  },
  {
    id: 'InvSeePlusPlus',
    displayName: 'InvSee++',
    givesKey: 'mc.plugin.gives.invsee',
  },
  {
    // Vault сам по себе экономику не ведёт — он прослойка между плагинами.
    // Панели важен именно он: через него берётся Economy-провайдер, каким
    // бы плагином тот ни предоставлялся (EssentialsX, CMI, любой другой).
    id: 'Vault',
    displayName: 'Vault',
    givesKey: 'mc.plugin.gives.vault',
  },
  {
    // Наш собственный плагин авторизации. Панель обращается к нему не
    // напрямую, а через companion: тот спрашивает у AurumAuth по его
    // публичному API и отдаёт результат сюда.
    id: 'AurumAuth',
    displayName: 'AurumAuth',
    givesKey: 'mc.plugin.gives.aurumauth',
  },
  {
    // Наш плагин гильдий и пати. Панель, как и с AurumAuth, ходит к нему не
    // напрямую: companion берёт его Java API из ServicesManager и отдаёт
    // результат сюда. Второго HTTP-сервера на игровом сервере не появляется.
    id: 'AurumGuilds',
    displayName: 'AurumGuilds',
    givesKey: 'mc.plugin.gives.aurumguilds',
  },
] as const;

export type KnownPluginId = (typeof KNOWN_PLUGINS)[number]['id'];

export const MINECRAFT_PERMISSIONS = {
  playersView: 'minecraft.players.view',
  /**
   * Известные IP-адреса игрока.
   *
   * Отдельным правом и намеренно весомее просмотра списка игроков: адрес
   * — это личные данные, по нему видно и провайдера, и город, и то, что
   * два ника принадлежат одному человеку. Модерации для её работы этого
   * не нужно.
   */
  playerIps: 'minecraft.players.ips',
  kick: 'minecraft.kick',
  ban: 'minecraft.ban',
  pardon: 'minecraft.ban.pardon',
  whitelist: 'minecraft.whitelist',
  quickCommands: 'minecraft.quick-commands',
  commandRaw: 'minecraft.command.raw',
  inventoryView: 'minecraft.inventory.view',
  /**
   * Менять инвентарь: выдавать предметы, удалять выбранное, стирать целиком.
   *
   * Отдельно от просмотра и намеренно весомее. Посмотреть чужой инвентарь —
   * рутина модерации, а вот полная очистка необратима: панель не умеет
   * вернуть стёртое, и восстановить его можно разве что из бэкапа мира.
   */
  inventoryEdit: 'minecraft.inventory.edit',
  configure: 'minecraft.configure',
  permissionsView: 'minecraft.permissions.view',
  permissionsEdit: 'minecraft.permissions.edit',
  economyView: 'minecraft.economy.view',
  economyEdit: 'minecraft.economy.edit',
  /**
   * Сброс пароля игрока.
   *
   * Отдельным правом, а не под кик/бан: выданный токен на двадцать минут даёт
   * доступ к чужому аккаунту, и это заметно весомее, чем выгнать с сервера.
   */
  passwordReset: 'minecraft.password.reset',
  /** Смотреть гильдии и их состав. */
  guildsView: 'minecraft.guilds.view',
  /**
   * Вмешиваться в чужие гильдии: распустить, передать лидерство, исключить.
   *
   * Отдельно от просмотра и намеренно весомее: роспуск необратим и уносит
   * состав вместе с общаком, а посмотреть список гильдий безобидно.
   */
  guildsManage: 'minecraft.guilds.manage',
} as const;

/**
 * Выданный токен сброса пароля.
 *
 * Показывается администратору ровно один раз — сервер его не хранит в
 * открытом виде и повторить не сможет. Потерянный токен не восстанавливается,
 * выдаётся новый.
 */
export interface MinecraftPasswordResetDto {
  username: string;
  token: string;
  /** ISO-время, после которого токен перестаёт действовать. */
  expiresAt: string;
}

/**
 * Производительность игрового сервера: TPS и время тика.
 * Команды `tps` и `mspt` есть в Paper/Spigot; на ванильном сервере их нет,
 * поэтому поля обнуляются, а `supported` показывает, что именно недоступно.
 */
export interface MinecraftPerformanceDto {
  tps1m: number | null;
  tps5m: number | null;
  tps15m: number | null;
  /** Среднее время тика, мс. Норма — меньше 50. */
  mspt: number | null;
  /** false — сервер не знает команду (не Paper/Spigot). */
  tpsSupported: boolean;
  msptSupported: boolean;
}

// ------------------------------------------------------------- Экономика
//
// Работает через Vault — прослойку, за которой может стоять любой плагин
// экономики (EssentialsX, CMI и прочие). Панель с конкретным плагином не
// разговаривает и знать про него не обязана.

/** Баланс игрока. */
export interface MinecraftBalanceDto {
  available: boolean;
  /** Почему недоступно: нет companion-плагина, нет Vault, нет провайдера. */
  reason?: string;
  code?: 'no-companion' | 'requires-vault' | 'no-provider' | 'error';
  balance?: number;
  /** Отформатированная провайдером строка: «1 234,50 монет». */
  formatted?: string;
  /** Название валюты во множественном числе — для подписей полей. */
  currency?: string;
}

/** Результат начисления или списания. */
export interface MinecraftBalanceChangeDto {
  ok: boolean;
  /** Текст ошибки от провайдера, если не вышло. */
  error?: string;
  balanceBefore: number;
  balanceAfter: number;
  formatted?: string;
}

/** Экономика сервера целиком: общий объём денег и самые богатые. */
export interface MinecraftEconomyDto {
  available: boolean;
  reason?: string;
  code?: 'no-companion' | 'requires-vault' | 'no-provider' | 'error';
  /** Сумма балансов всех, кто когда-либо заходил на сервер. */
  total?: number;
  totalFormatted?: string;
  currency?: string;
  /** Сколько игроков учтено в сумме. */
  playersCounted?: number;
  /** Доска богатства: топ по балансу. */
  top?: { name: string; uuid: string; balance: number; formatted: string }[];
  /**
   * Когда посчитано, ISO-строка. Значение кэшируется: пересчёт обходит всех
   * игроков сервера, и делать это на каждое открытие страницы незачем.
   */
  calculatedAt?: string;
  /** true — отдана закэшированная величина, а не свежий пересчёт. */
  cached?: boolean;
}

/**
 * Гильдия в панели.
 *
 * Один тип и на строку списка, и на карточку: отличаются они только тем,
 * заполнен ли `members`. Разводить их на два интерфейса значило бы дублировать
 * восемь полей ради одного различия.
 */
export interface MinecraftGuildDto {
  id: number;
  name: string;
  tag: string;
  leaderUuid: string;
  leaderName: string;
  memberCount: number;
  /** Баланс общака. 0, если Vault на игровом сервере нет. */
  bankBalance: number;
  /** ISO-время создания. */
  createdAt: string;
  /** Состав. Пуст в списке, заполнен в карточке. */
  members: MinecraftGuildMemberDto[];
}

/** Ранг в гильдии. Тот же набор, что и в самом плагине. */
export type MinecraftGuildRank = 'leader' | 'officer' | 'member';

export interface MinecraftGuildMemberDto {
  uuid: string;
  name: string;
  rank: MinecraftGuildRank;
  /** ISO-время вступления. */
  joinedAt: string;
}

/** В какой гильдии состоит игрок — для его карточки. */
export interface MinecraftGuildMembershipDto {
  guildId: number;
  guildName: string;
  guildTag: string;
  rank: MinecraftGuildRank;
  joinedAt: string;
}

/** Кого назначить лидером при принудительной передаче. */
export interface MinecraftGuildTransferDto {
  /** Ник участника этой же гильдии. */
  target: string;
}

/** Кого исключить из его гильдии. */
export interface MinecraftGuildRemoveMemberDto {
  /** Ник игрока. Гильдию искать не нужно: он состоит максимум в одной. */
  target: string;
}

/**
  * Ключи подписей рангов — одни и те же в списке, карточке и модалке игрока.
  *
  * Здесь ключи, а не готовые слова: подписи живут в словарях панели, и «лидер»
  * на польском интерфейсе должен быть «lider», а не остатком русского текста
  * посреди переведённой карточки.
  */
export const MINECRAFT_GUILD_RANK_KEYS: Record<MinecraftGuildRank, string> = {
  leader: 'mc.rank.leader',
  officer: 'mc.rank.officer',
  member: 'mc.rank.member',
};

/**
 * Вид бонуса гильдии.
 *
 * Список повторяет перечисление в плагине, и это осознанная цена: панель
 * должна знать, что предложить в выпадающем списке, до того как сходит на
 * игровой сервер. Названия и границы величин при этом приходят ОТ ПЛАГИНА —
 * дублировать ещё и их значило бы, что новый вид надо добавлять в двух местах
 * и оба раза не ошибиться.
 */
export type MinecraftBonusType =
  | 'mining_speed'
  | 'movement_speed'
  | 'block_drops'
  | 'mob_drops'
  | 'experience';

export const MINECRAFT_BONUS_TYPES: MinecraftBonusType[] = [
  'mining_speed',
  'movement_speed',
  'block_drops',
  'mob_drops',
  'experience',
];

/** Ключи подписей для выпадающего списка, пока плагин не прислал свои. */
export const MINECRAFT_BONUS_KEYS: Record<MinecraftBonusType, string> = {
  mining_speed: 'mc.bonus.miningSpeed',
  movement_speed: 'mc.bonus.movementSpeed',
  block_drops: 'mc.bonus.blockDrops',
  mob_drops: 'mc.bonus.mobDrops',
  experience: 'mc.bonus.experience',
};

export interface MinecraftGuildBonusDto {
  type: string;
  /** Название вида — приходит от плагина, а не собирается здесь. */
  title: string;
  /** Величина: множитель или уровень эффекта, смотря по multiplier. */
  magnitude: number;
  /** true — величина это множитель; false — уровень эффекта зелья. */
  multiplier: boolean;
  /** ISO-время истечения; null — бонус постоянный. */
  expiresAt: string | null;
  grantedBy: string;
  grantedAt: string;
}
