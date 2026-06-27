---
description: "Run the pre-release checklist: docs freshness, full test suite, live-runtime smoke test, and secret/config audit"
argument-hint: "[--fix]"
user-invokable: true
name: pre-release
---

# /pre-release — Release-Readiness Checker

You are a release-readiness checker. Your job is to run the pre-release checklist and report a pass/warn/fail verdict so a release can be cut with confidence. Never block on ambiguity — surface it and keep going. Treat `pom.xml` as the source of truth for versions.

## Usage

```
/pre-release          # read-only report
/pre-release --fix    # also auto-correct unambiguous doc drift and rebuild the user manual
```

## Severity legend

- **Error** — must fix before release (verdict → NOT READY).
- **Warning** — should fix; release at your discretion.
- **Info** — FYI, no action required.

## Steps

### Check 0 — Endpoint selection (ask the operator; never hardcode)

The live-LLM steps — the AIIT layer (Check 2) and the runtime smoke test (Check 4) — need OpenAI-compatible endpoints, and those are **environment-specific and rotate** (LAN IP, Tailscale name, localhost; e.g. this environment reaches them over Tailscale). Do **not** hardcode or guess them in this skill or in committed config. Before running any live step, **ask the operator** (use `AskUserQuestion`) for:

- **llama.cpp endpoint** — the reference runtime. **The full test suite (Check 2, including AIIT) runs against this.** Required; if not provided, skip the AIIT layer (**Warning**) and run Check 4 only against whatever is given.
- **LM Studio endpoint** — **smoke-tested only** (Check 4); never used for the test suite. Optional — skip its smoke test if not given.
- **ollama endpoint** — planned; ask, but expect "skip" until it's been validated.

When asking, remind the operator:
- Include the **`/v1`** path on each base-url. Spring AI's OpenAI client appends `/chat/completions`, so a bare `host:port` fails — and LM Studio in particular answers the bare path with a **200 status wrapping an error body**, which later surfaces as a misleading `conversationId cannot be null`.
- Capture base-url **and** model name per runtime. Never echo API keys.

Write the chosen **llama.cpp** endpoint into `application-local.properties` (with `/v1`) for Check 2; hold the others for Check 4.

### Check 1 — Documentation freshness

1. Read `pom.xml` and extract the authoritative versions: `<java.version>`, the Spring Boot parent/BOM version, `<vaadin.version>`, `<spring-ai.version>`.
2. Cross-check those against the version strings in `README.md` (the "Stack" bullets) and the "Stack" section of `CLAUDE.md`. Any disagreement is an **Error** — name the file, the stale value, and the correct value. (Known drift to expect: README may say Vaadin `25.2.0-alpha5` / Spring AI `2.0.0-M4` while `pom.xml` is on `beta1` / `M5`.)
3. Verify `README.md` documents all three test layers (`./mvnw test`, `./mvnw -Pit verify`, `./mvnw -Pai-it verify`). A missing command is a **Warning**.
4. Compare the README "Current state" / use-case-status prose against `spec/use-cases/` (number of UC files, any marked implemented). Obvious drift is a **Warning** — code lags spec by design, so don't be strict.
5. Scan `README.md` for leftover placeholders and dead relative links into `spec/` or `docs/`. Report as **Info**.

### Check 1b — User manual freshness

The manual is `docs/manual/mise-manual.pdf`, built by hand from `docs/manual/mise-manual.typ` plus screenshots in `docs/manual/images/` (captured via `docs/manual/capture-screenshots.cjs`). It is committed with **no CI rebuild**, so it silently goes stale.

1. **Staleness via git timestamps.** Get the last-commit epoch of the PDF and each of its inputs with `git log -1 --format=%ct -- <path>`. Inputs: `docs/manual/mise-manual.typ`, `docs/manual/images/*`, and the UI view sources under `src/main/java/com/example/mise/ui/`. If any input's last commit is newer than the PDF's, flag **Error**: "manual likely stale — rebuild with `typst compile docs/manual/mise-manual.typ docs/manual/mise-manual.pdf`" (note whether UI views changed, which means screenshots also need recapturing).
2. Cross-check any hardcoded versions inside `mise-manual.typ` (Java / Spring Boot / Vaadin / Spring AI) against `pom.xml`. Disagreement is an **Error**.
3. In report-only mode, stop at reporting + the rebuild commands. In `--fix` mode, rebuild the manual (see the `--fix` section).

### Check 1c — Manual feature coverage

Freshness (1b) only proves the manual was recently rebuilt — not that it documents what the code actually does. A newly-shipped feature can be absent from the manual even when the PDF is "fresh". So check coverage, not just timestamps:

1. Build the list of **implemented** use-cases/features: read `spec/use-cases/use-case-*.md` (note the `## Verification` result / `Status`), and scan recent history (`git log --oneline -30`) for `UC-NNN` / feature commits. Treat anything with passing verification or a merged feature commit as "shipped".
2. For each shipped UC, check it is represented in `mise-manual.typ` — both in the prose sections **and**, for any user-facing chat capability, in the **Example queries** table (the manual's showcase, which claims every entry is "verified against a running build"). A shipped chat-driven UC with **no Example queries row** is a **Warning** (it reads as "the feature doesn't exist"); a UC mentioned nowhere in the manual is an **Error**.
   - Concretely: map each `UC-NNN` to a query/section. E.g. UC-011 (generate future weeks) → expect a "Plan next week" / "plan June" row; UC-010 (week navigation) → a navigation example or the header description; UC-006 (detour) → the Lido row; etc.
3. Report the matrix of shipped-UC → covered? and recommend the specific rows/sections to add. Under `--fix`, add them per the `--fix` section (verifying each new example against the running build first).

### Check 2 — Full test suite

1. **Pre-flight the live endpoint.** Confirm `application-local.properties` exists at the project root and points at the **llama.cpp endpoint chosen in Check 0** (the test baseline, with `/v1`) — AIIT drives the **live LLM** and onboarding depends on it. If it is missing, emit a **Warning** and **skip** the AIIT layer rather than letting it hang (~30 min on a stale-default endpoint). When present, surface the configured `spring.ai.openai.base-url` (host only — never the key) so the reader knows what it will hit.
2. Run each layer, capturing the exit code and the failure summary verbatim. **Do not pass `-q`** — it suppresses the Surefire/Failsafe `Tests run:` summary lines. If a run was already done quietly, recover the counts from `target/surefire-reports/*.txt` and `target/failsafe-reports/*.txt` rather than inferring pass from the exit code alone.
   - `./mvnw test` — unit + browserless.
   - `./mvnw -Pit verify` — Playwright IT. Note: the first run rebuilds the Vaadin bundle (3–5 min).
   - `./mvnw -Pai-it verify` — AI Tool IT (live LLM, 2-fork parallel). Skipped per step 1 if no endpoint.
3. Report each layer's result with its `Tests run / Failures / Errors / Skipped` counts. Any failing layer is an **Error**.

### Check 3 — Secret / config audit

1. Confirm `/application-local.properties` is gitignored **and** not tracked: `git ls-files --error-unmatch application-local.properties` should fail. If it is tracked, that is a critical **Error**.
2. Inspect each committed properties file and confirm secret-bearing fields use placeholders or env-var fallbacks, not real values:
   - `src/main/resources/application.properties`
   - `src/main/resources/application-prod.properties`
   - `src/test/resources/application-it.properties`
   - `src/test/resources/application-ai-it.properties`
   - `application-local.properties.example`
3. Grep all **git-tracked** files (`git ls-files`) for secret-looking patterns. Report file and line but **never echo the full secret value** (mask it). Severity depends on what the hit is:
   - **Credentials** — `api-key=`/`password=`/`secret=`/`token=` followed by a non-placeholder value, `sk-…` keys, bearer/authorization literals. Any concrete hit is an **Error**.
   - **Private/internal hosts** — match a real host:port (RFC-1918 IP as a 4-octet address with a port, e.g. `192.168.\d+.\d+:\d+`, or a `*.ts.net` Tailscale name), **not** bare decimals — anchor on `://` or `:\d` so floats like `10.0`/`10.50` don't false-positive. A host that is only a **default fallback** in a committed config (inside `${ENV:...}`, overridden by env/`application-local.properties`) is a **Warning** (ships internal topology, not a credential); a host hardcoded with no override is an **Error**.

### Check 4 — Live-runtime smoke test (real UI streaming path)

Check 2's AIIT layer drives a plain Spring AI `ChatClient` and **bypasses Vaadin's `SpringAILLMProvider` + `AIOrchestrator` streaming path — the path the running app actually uses.** So endpoint- and chat-template-specific failures that hit real users pass AIIT silently: e.g. a strict chat template rejecting onboarding's opening turn (`No user query found in messages`), or a missing `/v1` surfacing as `conversationId cannot be null`. This check exercises the real path through a browser.

Run it for each runtime named in Check 0 — **llama.cpp** (must pass) and **LM Studio** (smoke only); add **ollama** once validated. Run them **sequentially** (only one process can bind 8080); never two at once.

For each endpoint:

1. Point the app at it (env vars `MISE_MODEL_BASE_URL` (with `/v1`) + `MISE_MODEL_NAME`, or `application-local.properties`) and start the dev server on 8080. Ensure **no Household exists** so `/welcome` shows onboarding — move `./data/mise.mv.db` aside if needed (mind the file-H2 data hazard noted in Check 1c).
2. Drive **one real onboarding turn** through the browser. The Playwright **MCP cannot launch Chromium in this sandbox** — use a short headless **node script** against the global `playwright` lib: load `/welcome`, type a household description (size + budget + a restriction) into the message input, submit, and wait for the streamed assistant reply. Poll for it — a real tool-calling turn can take 20–40 s, so do **not** conclude "done" the instant the user bubble appears.
3. **Pass** = a non-empty assistant reply streams in **and** the server log shows none of: `Error during LLM streaming`, `conversationId cannot be null`, `No user query found`, `Aggregation Error`. A turn that reaches `recordHousehold` → redirect to `/plan` is a stronger pass.
4. **Severity:** a streaming failure on **llama.cpp** is an **Error** (the baseline). On **LM Studio** it is a **Warning** — surfaced for the operator's call; it does not block release, since the test suite runs on llama.cpp.
5. **Stop the server** before moving to the next runtime.

Never hang: if an endpoint is unreachable or the app won't start within a couple of minutes, skip that runtime with a clear message and keep going.

### Report

Print a report with **Errors**, **Warnings**, and **Info** sections, a summary count, and an overall verdict line: `READY FOR RELEASE` or `NOT READY`. Print the full report even when everything passes.

## `--fix` behavior

When `$ARGUMENTS` contains `--fix`, after reporting, auto-correct only unambiguous issues:

- **Version drift** — rewrite the stale version strings in `README.md`, `CLAUDE.md`, and `mise-manual.typ` to match `pom.xml`.
- **Missing test commands** — add the missing test-layer command line(s) to `README.md`, matching the surrounding format.
- **Rebuild the user manual** (the manual is assumed perpetually stale, so always attempt this):
  - **Recompile only** — if no UI view source changed since the PDF's last commit (screenshots still valid), just run `typst compile docs/manual/mise-manual.typ docs/manual/mise-manual.pdf` after applying any `.typ` version edits.
  - **Recapture + recompile** — if UI views changed, take ownership of the runtime lifecycle (as `implement-uc` does): ensure the live endpoint is configured, start the dev server on 8080, **establish a fully-seeded demo household**, run `node docs/manual/capture-screenshots.cjs`, then `typst compile`, then stop the server.
    - **Critical ordering / data hazard:** the Check 2 unit layer runs non-`@Transactional` `@SpringBootTest`s against the **file** H2 (`./data/mise`) and wipes the dev demo data. So **never trust whatever household is left in `./data/mise` after the tests** — it will be empty or partial, and the capture will silently produce blank `€0.00 / "ask Mise to fill"` screenshots. Before capturing, re-seed/onboard a rich household (via the onboarding chat) and verify the Plan view actually shows a full week. Either capture **before** running the file-H2 tests, or re-seed after them — don't skip this.
    - **Verify the output, don't trust exit 0:** after capture, open at least `plan-desktop.png` and confirm it shows real meals and non-zero costs (and no license banner). If the screenshots are blank/degraded, **discard them** (`git checkout -- docs/manual/images/`), do NOT recompile, and report the manual as still-stale with the cause — a bad PDF is worse than a stale one.
  - **Preflight + graceful fallback** — if `typst` is not on `PATH`, the app will not start, no live endpoint is configured for onboarding, or a seeded household cannot be established, do NOT hang: skip the rebuild, keep the **Error**, and report exactly what is missing plus the manual command to finish by hand.
- **Add missing feature coverage** (from Check 1c) — for each shipped UC with no Example queries row, add a row to the table in `mise-manual.typ`. **Verify each example against the running build first** (the table claims every entry is "verified against a running build"): with the dev server up and a seeded household, send the query in the chat dock, read the actual assistant reply, and quote it in the "What happens" cell. Never invent a reply. Then recompile the PDF. If you can't run the app to verify, do NOT add a fabricated row — leave the **Warning** and report it.

After fixing, re-run the affected checks and report the updated state. Leave rebuilt artifacts (PDF, screenshots) **staged but uncommitted** for the user to review.

## Rules

- This command is **read-only by default**. Only modify files when `--fix` is passed.
- Never auto-touch secrets, and never echo a secret value in any output (mask it).
- Respect `CLAUDE.md` guardrails: never edit `pom.xml`, `vite.config.ts`, or `spec/architecture.md` — report issues with those for the user to fix manually.
- `pom.xml` is the source of truth for versions; fixes flow from it to the docs, never the reverse.
- Never let a check hang. If a prerequisite (live endpoint, `typst`, running app) is absent, skip with a clear message and keep going.
- **Never hardcode live-LLM endpoints** in this skill or commit them to config — they are environment-specific and rotate. Always ask the operator (Check 0). The test suite runs against llama.cpp; LM Studio (and later ollama) are smoke-tested only.
