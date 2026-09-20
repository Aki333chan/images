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

Scope this increment: separate prison and event presets in the same movement disclosure. Prison has explicit assignments with a local-date release field (empty means manual), online/offline ID entry and release actions staged until Save. Event has an explicit participant list, released on death/logout/restart, with no capture of passersby. Both show the internal return point and opt-in dismount/strict kick, off by default. Hide irrelevant level filters. Retain existing single Save, permission checks, map sizing and theme. No modal or new visual system; schedules remain future work and native behavior remains RC pending live checks.

## Documentation outcome

This is an extension of the incumbent system evidenced by `tailwind.config.ts`, `src/index.css`, `src/components/ui.tsx`, `MapTab.tsx` and `ZoneEditor.tsx`. It adds no global visual system. PRODUCT.md and DESIGN.md were absent at both repository and app roots before this work; the ordinary-extension documentation pass preserves that state rather than inventing brand context or repairing global drift.

The prison and event increment was compared with those sources and both final captures at 1200px and 390px. Both presets extend the existing native movement disclosure between the message fields and command disclosure, reusing the editor's borders, Input and Button primitives, field styling and single Save action. Prison shows explicit assignments, an online-player picker and offline platform-ID entry. Each assignment has a local-time release deadline; an empty deadline means manual release, and release actions remain draft changes until Save. Event exposes an explicit participant list and its death/logout/restart release behavior; passersby are not enrolled. Both modes hide irrelevant level filters and expose an internal return point, priority, cooldown, optional message, and opt-in dismount and strict kick controls, off by default. Assignment rows wrap on narrow screens, priority/cooldown pairs stack, and return coordinates remain in three columns. MapTab supplies its existing online-player data without changing map sizing. This introduces no durable token or component-system change.

Desktop and mobile evidence lives in `.impeccable/review/zones-desktop.png` (1200×3241) and `zones-mobile.png` (390×4342). Both were opened and compared with the current implementation and show the prison preset; event was reviewed in source and through automated browser interaction. The captures render the actual React components and production CSS in a synthetic, deliberately terrain-free fixture, not live gameplay or shipping raster assets. The supplied fresh finish review returned `ship` across all five sections with no fixes; the detector ran once with result `[]`. The browser regression passed prison/event behavior, online/offline assignment, timed/manual release, release staging until Save, permissions, and desktop/mobile overflow checks. Game behavior remains RC pending live checks. No shipping raster assets were added, and preexisting documentation drift remains unchanged.
