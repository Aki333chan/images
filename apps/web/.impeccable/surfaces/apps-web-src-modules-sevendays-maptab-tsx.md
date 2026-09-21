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

Scope this increment: replace the single bonus selector with three independent numeric strengths in the existing editor: health regeneration, stamina regeneration and additional running speed. Zero disables an effect; overlaps use the highest strength of each effect, never a sum. Show units and bounds, retain the shared Save, permissions, enable/schedule and existing styles. Three columns on desktop, stacked on mobile. No new zone types, modal, poller, assets or dependencies. Import existing single bonuses without changing their strength. Native behavior remains RC pending live checks.

## Documentation outcome

This is an extension of the incumbent system evidenced by `tailwind.config.ts`, `src/index.css`, `src/components/ui.tsx`, `MapTab.tsx` and `ZoneEditor.tsx`. It adds no global visual system. PRODUCT.md and DESIGN.md were absent at both repository and app roots before this work; the ordinary-extension documentation pass preserves that state rather than inventing brand context or repairing global drift.

The Companion 1.0.20-rc.1 / zones-v7 bonus increment replaces the single selector with three independent numeric fields: health regeneration (0–10 per second), stamina regeneration (0–30 per second) and additional running speed (+0–100%). The existing fieldset, Inter labels, native inputs, muted help and shared Save action remain the visual authority. Fields form three columns on desktop and stack on mobile. Explicit zero disables each effect, fractional values are accepted, and required inputs use browser validation to reject an empty value. Existing single bonuses retain their strength on import. Bonus settings share existing permissions, zone-enabled state, schedule and Save behavior. Help explains that overlaps take the highest strength of each effect without summing and that hydration, other buffs and game settings affect the native result. Preserve these units, bounds, zero and fraction behavior, help, responsive layout and shared Save in subsequent work. This introduces no durable token or component-system change.

Desktop and mobile evidence lives in `.impeccable/review/zones-desktop.png` (1200×4040) and `.impeccable/review/zones-mobile.png` (390×5717), relative to the app root. The main agent opened both final recaptures. They render the actual React components and production CSS in a synthetic, deliberately terrain-free fixture; they are not live gameplay or shipping raster assets. The detector ran once with result `[]`. The supplied fresh `bonus_finish_reviewer` full review found one scored defect: bonus inputs lacked `required`. That attribute was added, followed by one rebuild and recapture; browser verification confirmed native empty-field validation. The reviewer then returned `ship` on resolution of that sole scored fix. This confirmation was not another whole-surface review or a gameplay pass.

Supplied verification passed the full 816-test API suite, then two newly added schema-gating tests in a targeted run; 818 is the expected API total for deployment, not a completed full-suite result here. The full 148-test web suite, 225 Core tests, lint and browser checks passed. Native and stub builds completed with zero warnings and errors. The release ZIP `AurumCompanion-7DTD-v1.0.20-rc.1.zip` was built; panel deployment is pending. No live game test was performed, and native behavior remains RC pending live validation. No shipping raster assets were added, and preexisting documentation drift remains unchanged.

## Mobile map and quick-actions increment — Companion 1.0.23-rc.1

This narrow Operate refinement retains the incumbent Aurum dark surfaces, Inter, violet controls and shared UI. Quick actions omit the duplicate Stop action, including when an older backend supplies `shutdown`; Save and Announce remain permission-filtered, and the top power controls remain unchanged. The user explicitly rejected additional actions or links. Map gestures are contained by an HTML wrapper around the SVG, preserving map sizing and page scrolling outside the map. Pointer cancellation and lost capture clear only the matching active drag and restore the appropriate cursor. Preserve marker selection, zone interaction and the existing tile/zoom behavior. The ordinary-items catalogue fix is an independent native item-ID correction in this release, with no new catalogue UI or visual system.

The main agent opened `.impeccable/review/7dtd-map-fixed-desktop.png` (1200×900) and `.impeccable/review/7dtd-map-fixed-mobile.png` (390×700), relative to the app root. These captures use the actual React components and production CSS with a synthetic green-terrain fixture; they are review evidence, not shipping assets or live gameplay. The detector ran once with result `[]`. The fresh `mobile_fix_finish_reviewer` returned `ship` with no material fixes. Supplied Chromium CDP checks in `check-sevendays-map.cjs` passed repeated touch swipes and panning without page scroll on the map, page scrolling outside it, marker interaction, continuous tiles through zoom/polling, and hiding the duplicate shutdown action.

At this documentation handoff, 231 Core tests and native/stub builds passed; the full API/web suites and panel deployment remain pending. Real iPhone Safari and live gameplay were not checked. No new raster assets, global tokens, PRODUCT.md or DESIGN.md were introduced, and historical documentation and preexisting drift remain unchanged.
