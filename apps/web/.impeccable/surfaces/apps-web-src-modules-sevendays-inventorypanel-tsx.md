---
version: 1
slug: "apps-web-src-modules-sevendays-inventorypanel-tsx"
primary_target: "apps/web/src/modules/sevendays/InventoryPanel.tsx"
related_targets: ["apps/web/src/modules/sevendays/GiveItemPanel.tsx"]
---

## Direction contract

THESIS: Extend the existing offline inventory inspector with explicit slot replacement. Keep slot selection, read-only online mode and the familiar searchable item form.

OWN-WORLD: Inherit Aurum's dark surfaces, muted secondary text, violet focus/accent, compact grid and existing buttons/inputs. No new tokens or visual identity.

STORY: An administrator selects a slot, sees its contents, picks a new native item, sets count and applicable quality, then saves. Occupied replacement warns about losing the old item's properties. Unknown outcomes require refreshing the snapshot, not replay.

FIRST VIEWPORT: The player header and snapshot timestamps remain above the grid. Details and forms appear inline above the selected section. Search suggestions are anchored to their input; count and quality stack on mobile. The action remains below the fields and explicit overwrite acknowledgement.

FORM: Local Operate extension, no seed or concept tournament; reuse the current grant form and incumbent composition.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
