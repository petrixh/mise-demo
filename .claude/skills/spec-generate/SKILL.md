---
description: "Generate data model, use cases, and verification checklists from project context"
argument-hint: "[datamodel | use-cases | verification | all]"
user-invokable: true
---

# /spec-generate — Generate Downstream Specs

You are a spec writer. Your job is to generate downstream specification documents based on the filled-in project context and architecture.

## Placeholder Detection Rule

A `[bracketed text]` token is a **placeholder** unless it is immediately followed by `(` — which makes it a markdown link (e.g., `[text](url)`). When scanning for placeholders, ignore markdown links.

## Steps

1. **Discover spec structure.** Glob for `spec/**/*.md`. Read `spec/README.md` to understand the workflow and file purposes. Read all existing spec files.

2. **Check prerequisites.** The high-level context files (project context, architecture) should be mostly filled in before generating downstream specs. If they still contain many placeholders, stop and recommend `/spec-interview` first.

3. **Determine scope** from `$ARGUMENTS`:
   - A specific keyword (e.g., `datamodel`, `use-cases`, `verification`) — only generate that artifact
   - `all` or empty/missing — generate all downstream artifacts in logical order

4. **Identify what to generate.** From the filled-in context, extract:
   - Scope items / capabilities that need use cases
   - Entities implied by the scope and existing use cases
   - Use cases that need verification checklists

5. **Follow existing templates and patterns.** Look for template files (e.g., `use-case-template.md`) and match their structure exactly when creating new files. For non-templated files (data model, verification), follow the patterns already present in the file.

6. **Generate artifacts:**
   - Never overwrite existing files — skip and note them
   - Use today's date (run `date +%Y-%m-%d` via Bash) in generated files
   - Use roles/actors from the project context as use case actors
   - Number new use case files sequentially from the highest existing number

7. **Print a summary** of what was created, what was skipped, and recommend `/spec-validate` as the next step.

## Rules

- Respect any guardrails in `CLAUDE.md` — do not modify protected files.
- Follow templates exactly — do not add or remove sections from templated files.
- Derive structure from the actual spec files, not from hardcoded assumptions.
