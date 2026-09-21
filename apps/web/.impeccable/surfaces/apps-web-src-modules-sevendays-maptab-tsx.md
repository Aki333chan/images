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

Scope this increment: two opt-in native checkboxes in a separate Block protection fieldset after player protection, sharing Save. Distinguish native creature attacks and explosion damage (including player-initiated explosions). Explain that this does not forbid player mining/building, protect props or structural collapse, or protect entities; the initiating explosive is still consumed. Both default off and respect zone enable/schedule. Preserve theme, map sizing, existing controls and permissions; no modal, poller, new assets or dependencies. Native behavior remains RC pending live checks.

## Documentation outcome

This is an extension of the incumbent system evidenced by `tailwind.config.ts`, `src/index.css`, `src/components/ui.tsx`, `MapTab.tsx` and `ZoneEditor.tsx`. It adds no global visual system. PRODUCT.md and DESIGN.md were absent at both repository and app roots before this work; the ordinary-extension documentation pass preserves that state rather than inventing brand context or repairing global drift.

The Companion 1.0.18-rc.1 / zones-v6 Block protection increment was compared with the incumbent sources and the supplied final desktop and mobile evidence. A separate Block protection fieldset follows player protection, reusing the existing Inter labels, native checkboxes, muted help text and single Save action. Its `noCreatureBlockDamage` and `noExplosionBlockDamage` settings both default to false and share the existing permissions, zone-enabled state and schedule. The explanation distinguishes protection against native creature attacks and explosion damage, including player-initiated explosions, from player damage protection. It states that the initiating explosive is still consumed and that player mining/building, props, structural collapse and entity protection are outside this feature. The opt-in controls, separate groups and explicit limits are the finish review's preservation requirements. The fieldset follows the existing responsive form layout without changing map sizing or movement controls. This introduces no durable token or component-system change.

Desktop and mobile evidence lives in `.impeccable/review/zones-desktop.png` (1200×3924) and `.impeccable/review/zones-mobile.png` (390×5396), relative to the app root. The main agent opened and inspected both against the current implementation. The captures render the actual React components and production CSS in a synthetic, deliberately terrain-free fixture; they are not live gameplay or shipping raster assets. The supplied fresh `block_finish_reviewer` review returned `ship` with no material fixes; the detector ran once with result `[]`. Supplied verification passed the browser zones regression, 806 API tests, 148 web tests, 209 Core tests, builds and lint. Native compilation completed with zero errors and zero warnings. The release package was built; deployment is pending. No live game test was performed, and native behavior remains RC pending live validation. No shipping raster assets were added, and preexisting documentation drift remains unchanged.
