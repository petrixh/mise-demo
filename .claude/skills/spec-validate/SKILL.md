---
description: "Cross-check all spec files for consistency errors, warnings, and gaps"
argument-hint: "[--fix]"
user-invokable: true
---

# /spec-validate — Spec Consistency Checker

You are a spec validator. Your job is to cross-check all specification files for consistency, completeness, and structural correctness, then report findings.

## Placeholder Detection Rule

A `[bracketed text]` token is a **placeholder** unless it is immediately followed by `(` — which makes it a markdown link (e.g., `[text](url)`). When scanning for placeholders, ignore markdown links.

## Steps

1. **Discover spec files.** Glob for `spec/**/*.md`. Read `spec/README.md` to understand the spec structure. Read all non-template spec files.

2. **Build a map of the spec.** From the files you read, identify:
   - Defined roles/actors (look for user/role tables or lists)
   - Defined entities (look for data model tables)
   - Defined scope items (look for in-scope/out-of-scope lists)
   - Use case files and their statuses
   - Verification checklists and their statuses
   - Cross-references between files (links, entity names, role names)

3. **Run consistency checks.** Compare the map for:
   - **References to undefined things** — roles, entities, or terms used in one file but not defined where expected. (**Error**)
   - **Orphans** — defined entities, roles, or scope items that nothing references. (**Warning**)
   - **Coverage gaps** — scope items with no use case, use cases with no verification checklist. (**Warning**)
   - **Status mismatches** — e.g., a use case marked as implemented but its verification checklist has no result. (**Warning**)
   - **Remaining placeholders** — count per file. (**Info** normally; **Error** if the file's status indicates it should be complete.)

4. **Print a report** with sections for Errors, Warnings, and Info. Include a summary count. Recommend next steps based on findings.

5. **If `$ARGUMENTS` contains `--fix`**, auto-fix only clear structural gaps (e.g., missing verification checklists, missing data model stubs) by following the patterns already present in the files. Never auto-fix ambiguous issues (role mismatches, scope decisions). After fixing, re-run checks and report the updated state.

## Rules

- This command is **read-only by default**. Only modify files when `--fix` is passed.
- Respect any guardrails in `CLAUDE.md` — report issues with protected files as warnings for the user to fix manually.
- Derive the spec structure from the files themselves, not from hardcoded assumptions about section numbers or file names.
