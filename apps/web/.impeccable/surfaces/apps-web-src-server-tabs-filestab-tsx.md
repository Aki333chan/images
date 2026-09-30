# Server files — drag-and-drop upload

Primary target: `apps/web/src/server-tabs/FilesTab.tsx`.
Mode: Operate. Scope: extend the existing shared file manager, not a redesign.
Audience: staff with `files.manage`; read-only staff keep their current controls.
Confirmed preference: individual files and multiple files together; no folder-tree uploads.

## Direction contract

THESIS: Drop local files directly into the open server folder. The existing list stays the task's center; no new tab or full-page upload wizard.

OWN-WORLD: Preserve Nocturne surfaces, existing purple primary, semantic success/error colors, Inter, Card/Button and toast conventions. No new assets or dependencies.

STORY: The staff member sees the destination in breadcrumbs, drags files over the list, sees a clear drop target, follows upload progress and receives a short result. Size limits and permissions remain authoritative on the API.

FIRST VIEWPORT: Keep breadcrumbs and toolbar above the file list. Add one compact dashed upload affordance between toolbar and list; during drag, highlight the existing file surface and show the destination. On phones the same affordance opens the native file picker.

FORM: Precisely specified local extension of the incumbent file manager. No concept seed or comp round applies. Signature interaction: file-only drag highlight with stable enter/leave handling; native upload progress, not decorative motion.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

No shipping rasters are introduced. Preserve incumbent design files; review screenshots are development evidence only.

## Completion and preservation

Completed locally as an extension of the existing Files tab. Finish review: **SHIP**; persistence, fidelity, and turn-ceiling checks passed with no material fixes. For this ordinary extension, completion is recorded in this surface brief; no durable system change was approved, so no global PRODUCT.md, DESIGN.md, or token sidecar is introduced.

The implementation preserves the Nocturne ground (#161826), purple primary, semantic feedback, Inter, compact Card/Button hierarchy, breadcrumbs, toolbar, file list, and toast conventions. The addition provides whole-card file-drag highlighting, a compact picker affordance, and byte-based progress with filename and file count. Individual and multiple files use the current destination; folder drops are rejected. Existing API endpoints, auth/refresh handling, permission split, and the 64 MiB per-file limit remain authoritative. No dependency or shipping raster asset was added; English, Russian, and Polish catalogs cover the new states.

Recorded verification: detector returned `[]` in its single run; TypeScript, production Vite build, 18 Jest suites / 156 tests, and browser smoke passed. Smoke covered multi-upload, frozen destination, byte progress, folder rejection, outside-drop prevention, overwrite cancellation, partial-error recovery, mobile picker, read-only permissions, and no overflow.

Synthetic local QA evidence only: `E:/Codex/2026-08-26/new-chat/work/tooling/aurum-files-dragdrop-evidence-2026-09-30`. The set contains `files-desktop.png` (1440 × 900), `files-mobile.png` (390 × 844), `files-drag-desktop.png`, `files-upload-desktop.png`, incumbent references `files-before-desktop.png` / `files-before-mobile.png`, and `files-fixture.tsx`. These are development fixtures, not shipped assets.

The production panel has not been deployed. This documentation pass changes only this surface brief and canonizes no new global rules or defects.
