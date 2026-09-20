---
version: 1
slug: "apps-web-src-modules-sevendays-toolspanel-tsx"
primary_target: "apps/web/src/modules/sevendays/ToolsPanel.tsx"
related_targets: []
---

## Direction contract

MODE: Operate. Narrow extension of the incumbent 7DTD Players tab, not a new visual world.

THESIS: Admins select an online player and perform a teleport or a saved item-kit grant without typing IDs, while saved points/kits and Pterodactyl schedule templates share one collapsible block.

OWN-WORLD: Preserve Aurum's existing dark surfaces, Inter, violet accent, spacing, Button/Input/Select/Card primitives and embedded catalogue picker. No new design tokens, raster assets, dialogs or dependencies.

STORY: Open Player management, choose one of three sections, configure/save, then explicitly trigger a game action. Distinguish saved definitions from executed commands and partial/unknown results. Schedule templates remain inactive until inspected in the existing Schedules tab.

FIRST VIEWPORT: Section choices precede the player selector and form; selected context and primary action stay legible. Mobile wraps controls, keeps coordinates in a compact row and has no horizontal overflow. Kit items use real catalogue names, quantities and applicable quality.

QUALITY BAR: Native keyboard-accessible controls; visible errors and pending/disabled states; no automatic replays, no new background polling; permissions remove unavailable actions. Confirmation is for teleport and multi-item batch, not each catalogue selection.

FINISH: Review required desktop/mobile captures for teleport, kit partial result and inactive schedule. Browser fixtures are synthetic; they do not prove live game arrival. Preserve incumbent design files; no new DESIGN.md or sidecar for this extension. Zones and gameplay protection are explicitly outside this release pending owner choice.
