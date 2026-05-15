---
name: project-vaadin-css-import-cache
description: "Vaadin's dev-mode CSS bundler doesn't re-bundle when an @imported sub-file (e.g. mise-<view>.css) changes — only when the MASTER styles.css is touched. Edits to per-view CSS files appear in standalone curl but not in the served bundle until you touch the master."
metadata: 
  node_type: memory
  type: project
  originSessionId: 465b0e19-fa5f-4bc9-82e1-ea2b6e965dff
---

When editing per-view CSS files in `src/main/resources/META-INF/resources/mise-<view>.css`, Vaadin's dev-mode directory-watcher **does NOT trigger a re-bundle of `styles.css`**, even though `styles.css` `@import`s the changed file. The standalone served file (`curl http://localhost:8080/mise-<view>.css`) IS up to date, but the master bundle browsers actually load (`curl http://localhost:8080/styles.css`) keeps the stale inlined content.

**Symptoms:**
- Edit `mise-main-layout.css` line N.
- `curl http://localhost:8080/mise-main-layout.css | grep <selector>` shows the new rule. ✓
- `curl http://localhost:8080/styles.css | grep <selector>` shows the OLD rule (or no match). ✗
- In the browser DevTools, the computed style doesn't reflect the edit, and the cascade shows the old rule winning.
- Restarting the dev server doesn't fix it (the initial-build cache is keyed to file mtimes, and the master file didn't change).

**The fix:**

```bash
touch src/main/resources/META-INF/resources/styles.css \
      src/main/resources/META-INF/resources/mise-<view>.css
```

Touching the master `styles.css` is what triggers Vaadin's `io.methvin.directory-watcher` to re-process the @import chain. After the touch, the served `styles.css` is much smaller (it serves the raw master with `@import` directives intact instead of inlining everything), and the browser fetches each @imported file separately — picking up your edits.

The browser still needs a **hard refresh** (Cmd-Shift-R) after the server re-bundles, since it's caching the previous `styles.css` content.

**How to apply:** After every edit to a `mise-<view>.css` file in the project's split-CSS layout, immediately `touch` the master `styles.css` so the bundler picks it up. Or just `touch` both as a habit. Verify with `curl http://localhost:8080/styles.css | grep <selector>` before assuming the edit is live.

**Why it's hard to debug:** the standalone file looks correct (and CLAUDE.md's existing sharp edge says to `curl` the served file to verify edits — but `curl`ing `mise-<view>.css` directly bypasses the actual bundle the browser loads). The browser cascade shows the old bundled rule winning over... no new rule, because the new rule never made it into the served bundle.
