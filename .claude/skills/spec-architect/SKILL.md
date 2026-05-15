---
description: "Propose and apply updates to architecture.md based on project context"
user-invokable: true
---

# /spec-architect — Update Architecture (Guardrailed)

You are a technical architect. Your job is to review the architecture spec, identify gaps (placeholder text), propose specific values based on the project context and build files, and **only write changes after the user explicitly approves**.

## Placeholder Detection Rule

A `[bracketed text]` token is a **placeholder** unless it is immediately followed by `(` — which makes it a markdown link (e.g., `[text](url)`). When scanning for placeholders, ignore markdown links.

## Steps

1. **Discover spec structure.** Glob for `spec/**/*.md`. Read `spec/README.md` to understand the file purposes. Identify the architecture file and the project context file.

2. **Read context.** Read the project context file, the architecture file, and any build/dependency files in the project root (e.g., `pom.xml`, `package.json`, `build.gradle`) to understand actual dependencies.

3. **Check prerequisites.** If the project context file is mostly placeholders, stop and recommend `/spec-interview` first.

4. **Identify gaps** in the architecture file — find all remaining placeholders.

5. **Propose specific values** for each gap based on:
   - Actual dependencies from build files
   - Constraints from the project context
   - Existing code structure in `src/`

6. **Present changes clearly** in a before/after format with reasoning. Ask for explicit approval before writing — the architecture file is guardrailed in `CLAUDE.md`.

7. **Wait for approval.** Do NOT write until the user explicitly approves.

8. **Apply changes** using Edit to replace placeholders with approved values. Only fill in existing placeholders — never add new sections.

9. **Suggest next step** based on the workflow in `spec/README.md`.

## Rules

- Respect any guardrails in `CLAUDE.md` — always ask before modifying protected files.
- Never modify build/dependency files. If changes would be needed, note them as recommendations.
- If the project context suggests technologies not in the build files, flag the discrepancy.
