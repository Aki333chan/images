---
version: 1
slug: "apps-web-src-modules-sevendays-tabs-tsx"
primary_target: "apps/web/src/modules/sevendays/tabs.tsx"
related_targets: []
---

## Direction contract

THESIS: Let administrators follow one player's recent events without leaving the Players tab or typing an ID.

OWN-WORLD: Preserve Aurum's dark surfaces, Inter typography, muted secondary text, violet accents and existing compact controls. Reuse the existing event list; no new tokens or visual identity.

STORY: Open history from an online/saved player or a participant in an event. Read joins, leaves, chat, deaths and PvP for that stable ID; filter and page backwards. Return to the collapsed five-event global log with All players. No additional modal or live game polling.

FIRST VIEWPORT: The selected player's heading receives focus. The name, All players action, collapse control and filter identify the context before at most ten events. Mobile controls wrap and event text breaks without horizontal scrolling; navigation and stable ID follow the entries.

FORM: Narrow Operate extension of the incumbent Players tab. No new/replacement world, concept roll or comp; preserve existing system and information density. Player and attacker links, dates and coordinates are functional evidence, not decoration.

FINISH: Bounded desktop/mobile fixture review and finish reviewer; preserve DESIGN.md and design tokens during documenter check. No shipping raster assets are introduced.

## Direction contract — quick imprisonment

THESIS: Select an online player and an existing prison without transcribing names or IDs.

OWN-WORLD: Preserve the current dark Aurum controls and shared Modal; no new tokens or navigation buttons.

STORY: Open Imprison, search the online list, select a player and an enabled prison, set an optional duration, submit once. Load only on opening or explicit refresh. Existing zones and permissions remain authoritative.

FIRST VIEWPORT: Player search and a bounded selectable list precede prison and duration controls. Names wrap; IDs distinguish duplicate names. Loading, empty, conflict and permission states explain recovery. Cancel and Imprison finish the form; phone layout uses the existing full-screen modal.

FORM: Precise Operate extension, code-led and inherited from Minecraft's quick jail workflow. No concept seed or replacement identity. Native radio controls and selection avoid a fragile floating suggestion panel.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance. For this extension, preserve incumbent design files; no shipping raster assets.

## Implementation record — quick imprisonment

`src/modules/sevendays/JailAction.tsx` extends the server actions in `tabs.tsx`. It reuses shared Modal, Button, Input, ErrorText and Spinner primitives, with Minecraft's `JailPanel.tsx` as the incumbent workflow reference. The desktop dialog, full-screen phone modal, muted supporting text, violet primary action and stacked mobile footer preserve the current system; no design tokens or shared components change.

The online-player search filters names and stable IDs. Native radio rows show both, so duplicate names remain distinguishable; names and IDs wrap inside the bounded list. Only enabled prison zones are offered, command-bearing zones retain their permission restrictions, and a single permitted prison is preselected. An empty duration means until cancelled; an explicit sentence accepts whole minutes from 1 to 525600. Existing sentences and a full prison prevent submission.

Data loads on opening or explicit refresh, without polling. Saving appends the selected stable ID through the existing versioned zones PUT, preserving concurrent-edit conflicts and existing permissions. Busy state prevents repeat submission; errors retain the form and explain refresh recovery. Release and sentence editing remain in map zone settings. Prison containment already applies only to sentenced players; the added `UnsentencedVisitorCanEnterAndLeavePrisonWithoutBecomingAResident` regression in `ZoneContainmentTests.cs` protects free entry and exit for visitors.

Evidence: `review/7dtd-jail-desktop.png` (1200 × 900) and `review/7dtd-jail-mobile.png` (390 × 844), relative to `.impeccable`, capture the actual shared and feature components with synthetic data, including duplicate Unicode names and distinct IDs. Both were inspected for layout; the browser interaction test passed. These captures are review evidence, not shipping raster assets. Independent finish-review disposition: ship, no material fixes for this narrow extension; both viewports inspected. Detector returned no findings. Web suite: 148 tests; Companion Core: 232 tests. Live game testing remains pending.

Context gaps: PRODUCT.md and DESIGN.md are absent at both repository and web-app roots. This preexisting documentation gap is recorded without creating a new visual system or repairing unrelated project context.
