# UC-099: Read about Mise's setup and capabilities (user manual)

> Retro-spec of ticket [#69](https://github.com/petrixh/mise-demo/issues/69) ("Mise User Manual"), implemented in commit `2049eaa`. The deliverable is a standalone PDF for someone who runs the demo (via UC-098) and wants to configure, use, and extend it — not a developer studying the codebase. Companion to UC-098: that UC gets them running, this one tells them what they're running.

---

**As a** user of the Mise demo, **I want to** read a standalone manual covering setup, day-to-day usage with example queries, and how to add my own content **so that** I can configure and get value out of Mise without reading the source code or the spec tree.

**Status:** Implemented
**Date:** 2026-06-11

---

## Main Flow

- I find the manual linked near the top of the README and open [`docs/manual/mise-manual.pdf`](../../docs/manual/mise-manual.pdf) (≈13 pages).
- **Welcome** — one page tells me what Mise is (an AI-assisted weekly meal planner where a week of dinners is always already there, shaped via natural language), introduces the three views (Plan, Shopping, Reports) plus the chat dock that spans them, and an honesty box sets demo expectations: one household, no login, static seed catalogs, stubbed nutrition.
- **Setup & configuration** — I learn the one mandatory decision (an OpenAI-compatible LLM endpoint) and the env vars that configure it, including the `/v1` gotcha; which local models are recommended (from the project's model bake-off); the no-Docker path (download the release run's JAR bundle, Java 21+, run in place); what persists where and how to factory-reset; how first-run onboarding and persona selection interact; and that there's an H2 console for the curious.
- **Using Mise** — view by view, I learn to read the week, edit meals via chat, pin, undo, ask "why?", navigate weeks, generate future weeks (Plan); use the consolidated aisle-grouped list, pantry subtraction, the One store / Cheapest mix toggle, and detour-vs-savings reasoning (Shopping); read the standard charts and reshape charts/grids via chat, with added widgets persisting across restarts (Reports); and that the chat is one conversation across all views, view-aware, with advisory dismissable insights.
- **Example queries** — a table of 12 real utterances with their verified outcomes and affected view, including one graceful-failure example showing the no-fabrication guardrail declining politely.
- **Make it yours** — I learn where seed data lives (`demo/data/recipes|stores/*.yaml`, `demo/data/personas/*.json`), follow a complete annotated recipe YAML, and am warned about the two gotchas (ingredient names must match a store catalog item to get priced; aisle names drive shopping-list grouping). Stores (prices, sales, `detourMinutesFromRoute`) and personas (first-run-only materialization) are covered, ending with a restart-vs-DB-reset table.
- **Troubleshooting / FAQ** — the classics: chat silent (missing `/v1`), data gone (volume not mounted), persona edit "ignored" (first-run-only seeding), where to file issues.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | **The manual's source of truth is reviewable text**, not a binary: authored in Typst at [`docs/manual/mise-manual.typ`](../../docs/manual/mise-manual.typ), rendered with the documented one-command build `typst compile docs/manual/mise-manual.typ docs/manual/mise-manual.pdf`. The rendered PDF is committed alongside the source so it can be reviewed before releases and read without a Typst install. Never hand-edit the PDF. |
| BR-02 | **Mise-specific only.** The setup chapter assumes the reader can run a container/app; it documents env vars (`MISE_MODEL_BASE_URL` incl. the mandatory `/v1`, `MISE_MODEL_API_KEY`, `MISE_MODEL_NAME`, `MISE_MODEL_MAX_TOKENS` default 16384, `PORT` default 8080), the persistence volume and factory reset, persona selection mechanics, and the H2 console — never Docker 101. |
| BR-03 | **No promised-but-absent behavior.** Every example query in the manual is verified against a running build of the documented version before publication, including the graceful-failure example. If a release changes behavior, the affected queries are re-verified or removed before the manual ships. |
| BR-04 | **Screenshots are real and regenerable.** They are live captures from the dockerized build, produced by [`docs/manual/capture-screenshots.cjs`](../../docs/manual/capture-screenshots.cjs) against a running instance (the script also strips the Vaadin commercial banner, which hides in a closed shadow root on `<body>`). The capture method lives next to the images so they can be redone after UI changes; desktop shots for all three views plus at least one mobile shot. |
| BR-05 | **It stays a manual, not a spec**: ~20 pages maximum (currently ≈13), linking to the GitHub repo / spec tree for depth. |
| BR-06 | The README links to the PDF and names the source file + rebuild command, so readers and contributors both find their entry point. |
| BR-07 | **Release hygiene**: the manual (queries per BR-03, screenshots per BR-04, setup wording per UC-098 — image name, volume path) is checked for currency as part of preparing a release tag. |

---

## Acceptance Criteria

- [x] PDF renders from reviewable source (no hand-edited binary) via a documented one-command build.
- [x] Setup chapter covers all `MISE_MODEL_*` vars + `PORT`, the volume/persistence path, persona selection, and factory reset — without re-explaining Docker itself.
- [x] Usage chapter covers all three views + chat, undo, "why?", week navigation, and insights.
- [x] ≥8 example queries with expected outcomes, each verified against a running build, including one graceful-failure example. (Shipped: 12, verified against the running UC-098 container, including reshape persistence across a container restart.)
- [x] "Add your own recipes" chapter includes a complete working YAML example and both pricing/aisle gotchas; covers stores and personas too.
- [x] Screenshots are current and regenerable (capture method documented next to the images).
- [x] README links to the manual.

---

## UI / Routes

Not applicable — the deliverable is a document, not app UI. No routes added or changed.

Key artifacts: [`docs/manual/mise-manual.typ`](../../docs/manual/mise-manual.typ) (source), [`docs/manual/mise-manual.pdf`](../../docs/manual/mise-manual.pdf) (committed render), [`docs/manual/capture-screenshots.cjs`](../../docs/manual/capture-screenshots.cjs) + [`docs/manual/images/`](../../docs/manual/images/) (screenshot tooling; note the `.gitignore` exemption for `docs/manual/images/`), README manual link.

---

## Verification

**Verified by:** ticket #69 implementation run (commit `2049eaa`)
**Date:** 2026-06-10

#### Functional

- [x] `typst compile docs/manual/mise-manual.typ docs/manual/mise-manual.pdf` reproduces the committed PDF from source
- [x] All 12 §4 example queries verified live against the running #68 container, including the graceful-failure case and Reports-widget persistence across a container restart
- [x] Screenshots regenerated by `capture-screenshots.cjs` against the dockerized build (banner stripped)
- [x] README link resolves; page count within BR-05 budget (13 pages)

#### Result

- **Status:** Pass
- **Notes:** Per BR-07, re-verify queries/screenshots and the setup-chapter wording (image name, volume path, and the direct-JAR section — artifact name, Java version — which landed 2026-06-11 with UC-098 Alternate Flow B) before each release tag.
