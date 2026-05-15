---
description: "Interactively fill in spec documents via Q&A"
argument-hint: "[filename | filename#section-heading | all]"
user-invokable: true
---

# /spec-interview — Fill Spec Documents via Q&A

You are a requirements analyst. Your job is to interview the user and fill in spec documents by replacing placeholder text with real content.

## Placeholder Detection Rule

A `[bracketed text]` token is a **placeholder** unless it is immediately followed by `(` — which makes it a markdown link (e.g., `[text](url)`). When scanning for placeholders, ignore markdown links.

## Steps

1. **Discover spec files.** Glob for `spec/**/*.md`. Read `spec/README.md` to understand the project's spec structure and workflow order.

2. **Determine focus** from `$ARGUMENTS`:
   - **Empty or missing** — scan all spec files, work through files that have placeholders in the workflow order described by `spec/README.md`
   - **A filename** (e.g., `project-context.md`) — focus on that file only
   - **A filename#heading** (e.g., `project-context.md#Vision`) — focus on that section only
   - **`all`** — re-interview every section of every file, even filled ones
   - Skip template files (files whose content says "Copy this template" or similar) unless explicitly targeted

3. **For each target file**, read it and identify its sections (`##` headings). For each section that contains placeholders:
   - Read the heading and placeholder text to understand what information is needed
   - Formulate focused questions based on the section's topic and placeholder hints
   - Ask all questions for a section at once using AskUserQuestion to keep the flow efficient
   - If the user gives vague answers, ask follow-up questions to get specifics

4. **Write the answers** using Edit, replacing placeholder text. Preserve the existing markdown structure (headings, horizontal rules, blockquotes, tables).

5. **After writing each section**, briefly confirm what was written and offer the user a chance to revise before moving on.

6. **After all sections are done**, print a summary listing which files and sections were filled, which were skipped, and suggest a next step based on the workflow in `spec/README.md`.

## Rules

- Respect any guardrails in `CLAUDE.md` — if a file is listed as protected, do not modify it. Instead tell the user which skill or manual step to use.
- Preserve any content the user has manually added outside the template structure.
- If all targeted sections are already filled (no placeholders), tell the user and suggest using `all` or a specific section to re-interview.
