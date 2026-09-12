# AurumCore 0.9.0

Authoritative economy foundation for the Aurum ecosystem. AurumCore owns the
MariaDB ledger in `active` mode, exposes `AurumEconomyApi` to our plugins and
registers the highest-priority Vault economy provider for third-party plugins.
Vault remains the compatibility API; it is not the money store.

`passive` and `shadow` remain non-authoritative. They never change live
Vault/Essentials balances. Shadow mode creates immutable snapshots, imports
them into the dormant ledger, verifies every balance and exports rollback CSV.

## Safe installation and migration

1. Keep EssentialsX and VaultUnlocked unchanged.
2. Copy `AurumCore-0.7.0.jar` to `plugins/`.
3. Start with `economy.mode: passive` and `database.enabled: false`.
4. Run `/aurum status`; it must show the current Vault provider and writes OFF.
5. Configure MariaDB, set `database.enabled: true` and `economy.mode: shadow`.
6. Run `/amigrate dry-run`, inspect `/amigrate status`, then run
   `/amigrate import <run-id> CONFIRM` and `/amigrate verify <run-id>`.
7. Run `/amigrate rollback-export` and back up MariaDB plus the server plugins.
8. In a maintenance window set `economy.mode: active` and restart. Active mode
   refuses its first start unless the latest imported snapshot still verifies.
9. Confirm `/aurum status`, `/abal`, `/atreasury`, `/apay` and one reversible
   test through another Vault-dependent plugin before opening the server.

Do not disable the old provider or delete its data before the staging and
rollback tests pass. `active.require-verified-migration: false` is only for a
deliberately fresh, empty ledger; a non-empty unverified ledger is rejected.

## Commands and permissions

- `/aurum balance [player] [currency]`, short form `/abal [currency]` — `aurum.balance`; viewing
  another online player also requires `aurum.balance.others`.
- `/pay <player> <amount> [reason]`, conflict-free `/apay` — `aurum.pay`.
- `/aurum treasury [currency]`, short form `/atreasury [currency]` — `aurum.admin`.
- `/aurum economy give|take|set <player> <amount> [currency:<id>] [reason]`, short form
  `/aeco ...` — `aurum.admin.economy`.
- `/aurum migrate ...`, short form `/amigrate ...` — `aurum.admin.migrate`.
- `/aurum policy ...`, short form `/apolicy ...` — `aurum.admin.policy`.
- `/aurum exchange ...`, short form `/aexchange ...` — `aurum.admin.exchange`.
- `/aurum claims [list [plugin]|inspect <id>|retry <id>|drop <id>]` — `aurum.admin.claims`.
- `/trade <player|accept|item|money <amount>|clear|confirm|cancel|view>` — `aurum.trade`.

The top-level commands `give`, `take` and `set` are intentionally not
registered. Commands have context-aware tab completion. Player names are
accepted only when known to the server, preventing misspellings from creating
money accounts.

## Financial policies

The policy pipeline supports percentage taxes, fees and commissions in
`INCLUDED` or `ADDED` mode, treasury-funded cashback and subsidies, transaction
limits and scoped exemptions. Several matching rules form one balanced ledger
plan and commit atomically with the original payment. Exact per-rule amounts,
rule IDs and immutable rule revisions are retained for audit.

Rules can be prepared in YAML and imported only on an empty policy database, or
explicitly re-imported with `/apolicy import-config CONFIRM`. Runtime edits are
stored as MariaDB revisions with actor, reason and schedule. The master switch
`financial-policies.enabled` changes only after restart. Raw Vault compatibility
deposits/withdrawals, migration, refunds and administrative adjustments are
protected from policies; our game plugins receive policy processing when they
move to `AurumEconomyApi` categories.

## Multiple currencies and exchange

`economy.currencies` defines up to 16 enabled currencies, while
`economy.primary-currency` selects the only currency exposed through Vault.
Native Aurum plugins can query and transfer every currency explicitly through
`AurumEconomyApi`. Exchange is a single MariaDB transaction: debit, fee,
settlement and credit either all commit or all roll back. A quote records the
rule revision, exact output and expiry; execution recalculates and rejects a
stale or changed quote.

Rules support fixed rates, percentage fees paid to the global treasury,
minimum/maximum input, time windows, priorities and account/metadata
conditions. `MINT_BURN` is convenient for centrally managed currencies;
`RESERVE` requires target liquidity and never creates it during exchange.
Fund or withdraw that liquidity with
`/aexchange reserve <rule> <currency> <give|take> <amount>`.

See `../docs/aurum-multi-currency.md` for the full command and API contract.

## Durable holds

Version 0.7.0 adds asynchronous `createHold`, `captureHold`, `releaseHold` and
`hold` methods to `AurumEconomyApi`. A hold reserves the complete payer debit,
including ADDED policies, without moving money before the game-side action is
ready. Every ordinary ledger debit respects active holds. Capture commits the
original category and policies exactly once; release restores available balance
because reserved funds never left the account.

Hold lifetime is capped by `holds.max-ttl-seconds` (300 by default, 10..3600).
Expired holds stop reducing available balance. Database failures fail closed,
and a capture retry after a lost response resumes through its stable transaction
key instead of charging twice. See `../docs/aurum-holds.md` for the lifecycle.

## Durable delivery claims

Version 0.8.0 adds `AurumClaimApi`, registered as its own service. A hold
protects the money half of a cross-system operation; a claim protects the half
that happens in Minecraft, and it exists because holds alone leave one window
open — the expensive one.

Consider an NPC shop. The plugin reserves the price, hands over the items, then
writes its journal and captures. Kill the process between the items and the
journal and the restarted plugin sees a reservation it believes was never
applied: it releases the money, and the player keeps the goods for free.
Post-purchase console commands are worse, because they run after capture and
simply vanish if the process dies first — and re-running them blindly is not
safe either.

A claim reverses the order. Money moves first, the debt is recorded, and only
then is anything handed over:

1. `promise` — idempotent by key; promising twice owes once.
2. `take` — leases the claim. Exactly one worker wins; a second server racing
   for it is told CONFLICT rather than quietly succeeding.
3. `advance` — after **each** step takes effect, not after the batch. The
   number recorded here is what a restarted server trusts.
4. `settle`, or `defer` if it may work later, or `quarantine` if it cannot.

A crash lets the lease run out: the claim returns to the queue with its last
recorded cursor. This is durable at-least-once delivery. A crash after a
Minecraft effect but before `advance` is inherently ambiguous, so money steps
need a stable idempotency key and item/command consumers need their own receipt,
escrow or compensation. The lease prevents concurrent workers; it does not turn
MariaDB and a Minecraft inventory into one transaction.

The built-in trade delivery and AddonsNPC item steps add that receipt. They stamp
the claim step into the player's PDC, save the inventory and stamp in the same
player-data snapshot, and only then advance MariaDB. A retry that sees the stamp
records the missing cursor without applying the item mutation again. The forced
save happens once per completed item hand-off, never in a join or periodic loop.
Arbitrary console commands remain outside this guarantee unless their target
accepts the claim/step key idempotently.

Payloads are opaque. Core stores them, hands them back and never parses them —
items, commands and their encoding belong to the plugin. Core owns only what a
plugin cannot get right alone: the record survives a crash, one worker holds it
at a time, and recorded progress is remembered.

`claims.max-lease-seconds` (120 by default, 10..600) caps how long a crashed
server keeps a player's goods locked away; a live delivery renews its lease as
it makes progress. After `claims.max-attempts` (5) failed hand-backs the claim
quarantines itself and waits for `/aurum claims`, because a delivery that keeps
failing needs a person, not another login-time retry. See
`../docs/aurum-claims.md`.

## Guaranteed player trade

Version 0.9.0 turns on `/trade`, off by default (`trading.enabled`). It is the
first operation in the ecosystem where goods move in **both** directions, and
the first consumer of delivery claims that is not a shop.

**An item put on the table leaves the player's inventory immediately** and
exists only as a durable blob in MariaDB. There is no second, live copy
anywhere: two places that can disagree about an item are two places that can
duplicate it.

**Every edit to either offer clears both confirmations**, and a confirmation
names the revision it was given for. A confirmation that arrives after the
table changed is refused, not adjusted. That refusal is the feature — it is
what stops the oldest trade scam there is, swapping the goods between the other
player's look and their click. The check lives in the SQL `UPDATE`, not in the
caller: deciding from a read taken one edit ago is exactly the mistake the
revision exists to prevent.

**One trade per player.** Otherwise the same stack could be offered in two
windows, and whichever settled second would be settling goods that are no
longer there.

**Money moves last, through holds.** Both sides may be offering money, and two
plain transfers can half-succeed — unwinding the first is a refund nobody asked
for. Reserving both first turns "can this trade pay for itself" into a question
answered before anything moves. A side that cannot cover its offer puts the
trade back on the table with nothing moved.

The recorded design reserved money as soon as it was offered; this reserves it
at settlement. The guarantee is identical — nothing moves unless both sides are
funded — without creating and releasing a hold on every edit of the amount.

**Every ending owes the goods to somebody.** Settling owes each side's table to
the other; cancelling and timing out owe each table back to its owner. All three
go through the same delivery claim, so a full inventory, an offline player or a
restart delays the handover instead of losing it.

Items are serialized with Bukkit's own object stream and stamped with a format
version. A hand-rolled encoding does not fail loudly on enchantments, lore or
attribute modifiers — it hands back a sword that quietly lost them, and the
player who paid for it cannot prove what it was. An unreadable blob is
quarantined for `/aurum claims`, never guessed at.

### The window

The trade window opens for both players as soon as the invitation is accepted,
and it is a **view of the table, nothing more**. Every slot holds a display
copy; the real goods left the player's inventory when they were offered and
exist only as the record in Core. A window that held the real stacks would be a
second place that can disagree with the first, and the moment those two
disagree, an item has been duplicated.

So every click is cancelled and turned into a call on the same service the
commands use. Click a stack in your inventory or drop one on your half of the
table to offer it; click an offered stack to take just that one back.

**The confirm button sends the revision the window was drawn with** — not
whatever is current. If the table changed in between, Core refuses, and the
refusal is the point.

**Closing the window calls the trade off.** There are other people's goods on
the table; walking away silently would leave them stranded until the session
timeout, so the close is the cancellation, and cancelling gives both tables
back.

Money is set with `/trade money <amount>` and shown in the window: a chat
command takes a number, an inventory slot does not.

## Runtime behavior

Balance and PlaceholderAPI reads use authoritative memory snapshots and never
query MariaDB on the server tick. Vault mutations are synchronous because the
Vault contract requires a definitive result; each mutation commits atomically
or fails closed. Keep MariaDB close to the game server and monitor the
rate-limited warning emitted when a Vault write takes 50 ms or more.

Placeholders: `%aurum_balance%`, `%aurum_balance_raw%`, `%aurum_currency%`,
`%aurum_currency_symbol%`, `%aurum_treasury_balance%`,
`%aurum_money_supply%`, `%aurum_taxes_collected%`.
Currency-specific variants include `%aurum_balance_tokens%`,
`%aurum_balance_raw_tokens%`, `%aurum_treasury_balance_tokens%`,
`%aurum_money_supply_tokens%` and `%aurum_currency_tokens_symbol%`.

See `../docs/aurum-core-architecture.md` and
`../docs/aurum-policy-engine.md` for commands, configuration and semantics.
