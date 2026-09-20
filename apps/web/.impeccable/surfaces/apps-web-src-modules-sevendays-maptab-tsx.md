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

Scope this increment: add a native schedule disclosure near the zone's enabled switch, sharing the existing Save. Opt-in weekly weekdays/hours, fixed UTC offset and optional start/end dates in that same offset. Explain overnight intervals, empty days, no automatic daylight-saving change, clock transitions not being player crossings, and event release on closure. Prison uses existing individual sentences, not recurring schedules; selecting prison turns schedule off explicitly in the draft. Preserve theme, map sizing, mobile stacking and permissions. No modal, extra poller, calendar dependency or new visual system. Native behavior remains RC pending live checks.

## Documentation outcome

This is an extension of the incumbent system evidenced by `tailwind.config.ts`, `src/index.css`, `src/components/ui.tsx`, `MapTab.tsx` and `ZoneEditor.tsx`. It adds no global visual system. PRODUCT.md and DESIGN.md were absent at both repository and app roots before this work; the ordinary-extension documentation pass preserves that state rather than inventing brand context or repairing global drift.

The schedule increment was compared with those sources, the English/Russian/Polish schedule copy and both final captures at 1200px and 390px. It adds a native disclosure directly below the zone-enabled checkbox, reusing the existing border, Inter labels, muted help, Input primitive, native select and checkboxes, and single Save action. The disclosure opens when scheduling is enabled. Weekdays wrap; paired hours and date limits stack on mobile. The fixed UTC offset, absence of automatic daylight-saving adjustment, overnight start-day semantics, empty-days inactive state, and optional inclusive-start/exclusive-end date limits are explicit. Native time and datetime-local fields retain the shared field styling. Selecting prison turns the schedule off in the draft and prevents re-enabling it; individual sentences remain the prison mechanism. The copy distinguishes clock changes from entry/exit actions and explains event release on closure and fresh entry after reopening. Map sizing, existing movement controls and permissions remain intact. This introduces no durable token or component-system change.

Desktop and mobile evidence lives in `.impeccable/review/zones-desktop.png` (1200×3752) and `zones-mobile.png` (390×5172), relative to the app root. Both were opened and compared with the current implementation and show the event preset with an enabled schedule. The captures render the actual React components and production CSS in a synthetic, deliberately terrain-free fixture, not live gameplay or shipping raster assets. The supplied fresh finish review returned `ship` across all five sections with no fixes; the detector ran once with result `[]`. The supplied browser regression passed schedule roundtrip, permissions, prison disabling, empty days, native date fields and mobile overflow. Supplied verification passed 805 API tests, 148 web tests, 206 Core tests and native compilation against V3.2 b10. No live game test was performed; native behavior remains RC pending live checks. No shipping raster assets were added, and preexisting documentation drift remains unchanged.
