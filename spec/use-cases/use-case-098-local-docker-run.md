# UC-098: Try out Mise locally (Docker image or direct JAR)

> Retro-spec of ticket [#68](https://github.com/petrixh/mise-demo/issues/68) ("Easily runnable demo"), implemented in commit `3fb4739`. Covers the published Docker image, the tag-triggered GHCR release workflow, and self-building from a checkout. Alternate Flow B (run the JAR directly on a local Java installation) was implemented 2026-06-11 after explicit go-ahead; the BR-11 design decision is recorded below.

---

**As a** curious user, **I want to** run the Mise demo on my own machine with a single `docker run` — no JDK, Maven, or checkout — **so that** I can try out the AI meal planner against my own LLM endpoint in minutes.

**Status:** Implemented (Docker + direct-JAR flows)
**Date:** 2026-06-11

---

## Main Flow — run the published image

- I pull the image: `docker pull ghcr.io/petrixh/mise-demo:latest` (no login — the package is public).
- I start it with my LLM endpoint and a volume for persistence:

  ```bash
  docker run -p 8080:8080 \
    -e MISE_MODEL_BASE_URL=https://api.openai.com/v1 \
    -e MISE_MODEL_API_KEY=sk-... \
    -e MISE_MODEL_NAME=gpt-4o-mini \
    -v mise-data:/data \
    ghcr.io/petrixh/mise-demo:latest
  ```

- I open `http://localhost:8080` and land in the onboarding chat (UC-001); the conversation round-trips against my configured endpoint.
- I plan a week, then `docker restart` the container. My plans, pantry, preferences, and conversation history are all still there (H2 lives on the `/data` volume).
- When I want a clean slate, I remove the volume (`docker volume rm mise-data`); the next start reseeds from the bundled catalogs (factory reset).

### Alternate Flow A — build the image myself

- From a clean checkout, `docker build -t mise .` succeeds with **no Vaadin keys**: the build falls back to Vaadin's watermarked `commercialWithBanner` production build (vaadin-chart is a commercial component). The full Maven + frontend build runs inside the container (`standalone` Dockerfile target).
- To customize the demo content, I mount my own seed directory over the bundled one: `-v $(pwd)/my-data:/app/demo/data` — recipes, stores, personas, and `active_persona.txt` are read from the filesystem at startup, not from inside the JAR.

### Alternate Flow C — maintainer publishes a release

- A maintainer pushes a `v*` tag (e.g. `v0.1.0`).
- [`release.yml`](../../.github/workflows/release.yml) builds the JAR **once, natively** (with the `VAADIN_OFFLINE_KEY` secret when set, watermarked-with-warning otherwise), then `buildx`-builds only the runtime stage (`release` Dockerfile target, which copies the host-built JAR) for `linux/amd64 + linux/arm64`, and pushes `ghcr.io/petrixh/mise-demo:<semver>` and `:latest`.
- One-time follow-up after the first publish: set the GHCR package visibility to public so anonymous pulls work.

### Alternate Flow B — run the JAR directly on a local Java installation

- I don't have (or don't want) Docker, but I have Java 21 (or newer) installed — the only prerequisite; the docs state the version requirement and nothing more (no Java-installation tutorial).
- From the repo's Actions tab I open the Release run for the `v*` tag that produced the Docker image and download the `mise-demo-<version>-jar` artifact (a GitHub login is required, and workflow artifacts expire after 90 days).
- The artifact zip contains the runnable `mise-demo-<version>.jar` **plus the `demo/` seed-catalog directory** it reads at startup. I extract it and run from the extracted directory:

  ```bash
  MISE_MODEL_BASE_URL=https://api.openai.com/v1 \
  MISE_MODEL_API_KEY=sk-... \
  MISE_MODEL_NAME=gpt-4o-mini \
  java -jar mise-demo-<version>.jar
  ```

- The app serves on `:8080`, seeds from `./demo/data` on first run, and persists to `./data/mise` relative to my working directory. Re-running from the same directory resumes my data; deleting `./data` is the factory reset.

---

## Business Rules

### Implemented (Docker flows)

| ID | Rule |
|----|------|
| BR-01 | The **only mandatory configuration is the LLM endpoint**: `MISE_MODEL_BASE_URL` (OpenAI-compatible, **must include the `/v1` path segment**), `MISE_MODEL_API_KEY`, `MISE_MODEL_NAME`. Optional: `MISE_MODEL_MAX_TOKENS` (default 16384) and `PORT` (default 8080; changing it changes the in-container listen port, so `-p` must be adjusted to match). No code changes or config files are required for a container run. |
| BR-02 | The published image is **multi-arch**: one manifest covering `linux/amd64` and `linux/arm64`, tagged with the release semver and `latest`, published to GHCR on every `v*` tag push. Anonymous `docker pull` must work (public package). |
| BR-03 | **One volume mount persists everything.** The container runs with the `prod` Spring profile ([`application-prod.properties`](../../src/main/resources/application-prod.properties)), which pins H2 to `jdbc:h2:file:/data/mise`; mounting `/data` preserves plans, pantry, preferences, and conversation history across container restarts. Removing the volume is the documented factory reset. |
| BR-04 | **Seed catalogs ship inside the image** at `/app/demo/data` because they are read from the filesystem at startup — a JAR-only image boots empty. Mounting a host directory over `/app/demo/data` customizes recipes/stores/personas without rebuilding. The persona in `active_persona.txt` materializes on **first run only** (per UC-001); changing it later requires a DB wipe. |
| BR-05 | **Keyless builds must succeed.** vaadin-chart is commercial; without a key the build uses Vaadin's watermarked `commercialWithBanner` mode so `docker build .` works from a clean checkout. The release workflow uses the `VAADIN_OFFLINE_KEY` repo secret when present and emits a warning (not a failure) when absent. |
| BR-06 | **No Vaadin production build under QEMU.** The release workflow builds the architecture-independent JAR once on the native runner; only the JRE runtime stage (`release` Dockerfile target) is built multi-platform. |
| BR-07 | The H2 console at `/h2-console` (`sa`, no password) is reachable through the mapped port (`spring.h2.console.settings.web-allow-others=true` in the prod profile — inside a container "localhost" is the container itself). Demo-grade: the port must not be exposed beyond the user's machine, and the README/manual say so. |
| BR-08 | `.dockerignore` keeps `application-local.properties` and the dev H2 database (`./data/`) out of every image build context — a published image must never embed a developer's endpoint, key, or data. |

### Implemented (direct-JAR flow, 2026-06-11)

| ID | Rule |
|----|------|
| BR-10 | A plain `java -jar` on a stock **Java 21+ JRE** must work — no Maven, no checkout, no Docker. The docs state the Java version requirement and nothing more (no Java-installation instructions). The bundle is stored as a **workflow artifact** on the tag's Release run (`mise-demo-<version>-jar`), not as a GitHub release asset — decided 2026-06-11; the documented trade-off is that artifact downloads require a GitHub login and expire after 90 days. |
| BR-11 | **Resolved as option (b), the bundle zip** (decided 2026-06-11): the artifact contains the JAR plus the `demo/` seed directory; the user extracts and runs from the extracted directory. Chosen over the classpath-fallback option because it needs zero code changes — the three catalog loaders stay untouched and the filesystem-editable-seeds teaching story (UC-098 Alt A, project-context "demo inspectability") holds identically for the JAR flow. The known trade-off stands: the JAR must be launched from the directory containing `demo/` (or with `-Dmise.seed.directory=...`). |
| BR-12 | The JAR flow uses the **default profile, not `prod`** — `application-prod.properties` pins container-absolute paths (`/data/mise`, `/app/demo/data`) that are wrong on a desktop. Data lands at `./data/mise` relative to the working directory; deleting `./data` is the factory reset. Same `MISE_MODEL_*` / `PORT` env vars as BR-01. (No code change was needed — these are the existing defaults.) |
| BR-13 | README has a "Run the JAR (no Docker)" subsection next to "Run with Docker": Java 21 requirement, where the artifact lives, run command, where data lives, factory reset. The user manual (UC-099) setup chapter has the same path ("No Docker? Run the JAR directly"). |
| BR-14 | A keyless release JAR carries the same watermarked-banner caveat as BR-05; README and the manual both mention the banner so users don't mistake it for a bug. |

---

## Acceptance Criteria

### Implemented (Docker flows)

- [x] `docker build .` succeeds from a clean checkout with no Vaadin key secrets.
- [x] `docker run -p 8080:8080 -e MISE_MODEL_BASE_URL=... -e MISE_MODEL_API_KEY=... -e MISE_MODEL_NAME=... <image>` serves the app on `:8080` and the chat works against the configured endpoint.
- [x] H2 data survives a container restart when a volume is mounted at `/data`.
- [x] The `--spring.profiles.active=prod` entrypoint flag points at a real profile (`application-prod.properties` exists and is load-bearing).
- [x] Mounting a host directory over `/app/demo/data` overrides the seed catalogs without an image rebuild.
- [ ] Pushing a `v*` tag publishes `ghcr.io/petrixh/mise-demo:<version>` and `:latest` with a manifest covering **linux/amd64 and linux/arm64** (verify with `docker manifest inspect`). *Pending: workflow merged but no `v*` tag pushed yet — existing `0.0.x` tags predate it and don't match the trigger.*
- [ ] After first publish, the GHCR package is set public and anonymous pull works. *Pending first publish (one-time manual step).*
- [x] README has a "Run with Docker" section: pull command, full `docker run` example with all `MISE_MODEL_*` env vars + `PORT`, volume mount for persistence, seed-customization mount, self-build, and release-flow notes.
- [x] The release workflow does not run the Vaadin production build under QEMU emulation (JAR built once natively; only the runtime stage is multi-arch).

### Direct-JAR flow (implemented 2026-06-11)

- [ ] The Release workflow run for a `v*` tag stores a `mise-demo-<version>-jar` artifact containing the JAR + `demo/` (BR-10/BR-11). *Pending: same first `v*` tag as the GHCR criteria above; the staging + upload steps are in `release.yml` but no tag has exercised them.*
- [x] `java -jar` from a directory containing only the staged bundle (JAR + `demo/`) serves the app on `:8080` in production mode with seeded catalogs (18 recipes / 3 stores / 2 personas) and a working chat (onboarding round-trips against a live endpoint).
- [x] Data persists across re-runs from the same working directory (`./data/mise` created on first run, conversation rows readable after process exit); deleting `./data` factory-resets.
- [x] README and the user manual document the JAR path (BR-13).

---

## UI / Routes

No new UI — this UC is packaging, configuration, and documentation. The running container serves the existing app unchanged.

| Route | Access | Notes |
|-------|--------|-------|
| `/` (all app views) | public | Served on `PORT` (default 8080), mapped via `-p`. |
| `/h2-console` | public on the mapped port | BR-07: demo-grade credentials; never expose beyond localhost. |

Key artifacts: [`Dockerfile`](../../Dockerfile) (targets: `standalone` default, `release` for CI), [`.dockerignore`](../../.dockerignore), [`release.yml`](../../.github/workflows/release.yml) (image publish + JAR-bundle artifact), [`application-prod.properties`](../../src/main/resources/application-prod.properties), README "Run with Docker" and "Run the JAR (no Docker)".

---

## Verification

**Verified by:** ticket #68 implementation run (commit `3fb4739`)
**Date:** 2026-06-10

#### Functional

- [x] Keyless `docker build .` from a clean checkout (watermarked banner build)
- [x] Container serves `:8080` in production mode; onboarding chat round-trips against a live LLM endpoint
- [x] H2 data survives a container restart on the `/data` volume; volume removal factory-resets
- [x] `release` Dockerfile target builds and runs with a host-built JAR
- [ ] Multi-arch GHCR publish + anonymous pull — **pending first `v*` tag** (re-verify with `docker manifest inspect` after the first release)
- [x] Direct-JAR flow (2026-06-11): CI-equivalent keyless build (`clean package -DskipTests -Dvaadin.commercialWithBanner`), JAR + `demo/` staged to a clean directory, `java -jar` boots in production mode, loads 18 recipes / 3 stores / 2 personas from `./demo/data`, serves `:8080`, onboarding chat round-trips against the live LLM endpoint, and `./data/mise` persists across process restarts
- [ ] Artifact upload on a real tag run — **pending first `v*` tag** (verify the `mise-demo-<version>-jar` artifact appears on the Release run and its zip contains JAR + `demo/`)

#### Result

- **Status:** Partial (all locally verifiable criteria pass, both flows; GHCR publish and the artifact upload await the first `v*` tag)
- **Notes:** One-time step after first publish: set GHCR package visibility to public. BR-11 resolved 2026-06-11 as the bundle-zip option, stored as a workflow artifact per the maintainer's call (login + 90-day expiry accepted).
