---
version: 1
slug: "apps-web-src-modules-sevendays-maptab-tsx"
primary_target: "apps/web/src/modules/sevendays/MapTab.tsx"
related_targets: ["apps/web/src/modules/sevendays/ZoneEditor.tsx"]
---

# 7DTD map zones

Mode: Operate. Extend the existing Aurum map, not its visual identity. Admins draw two opposite corners or enter coordinates, edit a zone in place and explicitly save. Map tiles/panning and existing POI/claim selections stay intact. View-only users cannot mutate zones.

## Direction contract

THESIS: The map is the editor; selecting a rectangle opens its settings without an extra page or modal.

OWN-WORLD: Preserve existing dark surfaces, violet controls, Inter, border tokens and compact native form controls. No new asset or visual system.

STORY: Open zones, add a rectangle with two clicks, inspect separate PvP/damage/creature settings, save and see the success acknowledgment. Despawn is distinct from spawn prevention and has an irreversible-action warning.

FIRST VIEWPORT: Add one Zones control alongside existing map tools. Keep the map sizing unchanged. A compact editor below it groups identity, protection, creatures and messages, with save/close/delete at the end. Mobile stacks the form groups and keeps coordinate fields in two columns, without page overflow.

FORM: Precisely specified local extension; no concept seed or comp. Signature interaction is two-corner drawing with a visible first-point marker and the completed draft rectangle after the second click; keyboard users have coordinate fields and selectable zone outlines.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

Scope this increment: extend the same editor with restricted-entry and portal presets, and one native disclosure for movement settings. Mode, combined level/player-list conditions, an online-player picker, destination block coordinates, priority, cooldown and optional message share the existing Save action. No new modal, map size or theme changes. Clear copy distinguishes periodic return from a physical barrier, unloaded destinations from unsafe ones, and the absence of implicit admin bypass. Reuse command-management permission for automatic movement. Schedules and containment remain future work; all game behavior remains RC pending live checks.

## Documentation outcome

This is an extension of the incumbent system evidenced by `tailwind.config.ts`, `src/index.css`, `src/components/ui.tsx`, `MapTab.tsx` and `ZoneEditor.tsx`. It adds no global visual system. PRODUCT.md and DESIGN.md were absent at both repository and app roots before this work; the ordinary-extension documentation pass preserves that state rather than inventing brand context or repairing global drift.

The access and portal increment was compared with those sources and both final captures at 1200px and 390px. Its native movement disclosure follows the message fields and precedes the existing command disclosure, reusing the editor's borders, fields and Save action. Active movement reveals combined level/player-list conditions, an online-player ID picker, destination X/Y/Z, priority, cooldown and an optional message. Level and priority/cooldown pairs stack on mobile; destination coordinates remain in three columns, and the player-ID field uses the established monospace stack. MapTab supplies its existing online-player data without changing map sizing. This introduces no durable token or component-system change.

Desktop and mobile evidence lives in `.impeccable/review/zones-desktop.png` (1200×3118) and `zones-mobile.png` (390×4223). Both were opened and compared with the current implementation; they render the actual React components and production CSS in a synthetic, deliberately terrain-free fixture, not live gameplay or shipping raster assets. The supplied fresh finish review returned `ship` across all five sections with no material UI fixes; the detector ran once with result `[]`, and the browser regression passed settings, unavailable-destination draft retention, permissions and mobile checks. Game behavior remains RC pending live checks. Preexisting documentation drift remains unchanged.
