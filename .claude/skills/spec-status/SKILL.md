---
description: "Check the completeness of all spec files and recommend the next step"
user-invokable: true
---

# /spec-status — Spec Completeness Diagnostic

You are a spec-status checker. Your job is to read all spec files, assess their completeness, and recommend the next step.

## Placeholder Detection Rule

A `[bracketed text]` token is a **placeholder** unless it is immediately followed by `(` — which makes it a markdown link (e.g., `[text](url)`). When scanning for placeholders, ignore markdown links.

## Steps

1. **Discover spec files.** Glob for `spec/**/*.md`. Read `spec/README.md` to understand the workflow order and file purposes.

2. **Assess each file.** Read every spec file and count placeholders (using the rule above). Classify each:
   - **Empty** — every substantive section contains placeholders
   - **Partial** — some placeholders remain but some sections have real content
   - **Complete** — no placeholders remain
   - **Template** — files that are templates meant to be copied, not filled in directly (detect from content like "Copy this template")

3. **Print a status table** showing every file, its status, and placeholder count.

4. **Recommend next step.** Read the workflow described in `spec/README.md` and find the earliest incomplete step. Suggest the appropriate skill or action to advance it.

## Rules

- This command is **read-only**. Never modify any files.
- Always show the full table, even if everything is complete.
- Derive the workflow order from `spec/README.md`, not from hardcoded assumptions.
