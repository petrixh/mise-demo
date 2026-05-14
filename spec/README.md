# Specification Folder

> Specs are written first, then used as input for AI-driven implementation and verification.
> They are the single source of truth. Keep them up to date as the project evolves.

Status lifecycle: **Draft** → **Approved** → **Implemented**

## File Overview

| File | Purpose | When to Read |
|------|---------|--------------|
| `project-context.md` | Vision, problem, users, scope, risks | First — before anything else |
| `architecture.md` | Technology stack and application structure | After project context is filled in |
| `datamodel/datamodel.md` | Entity definitions and relationships | When adding data-driven features |
| `use-cases/use-case-template.md` | Template for individual feature specs | Copy per feature as `use-case-NNN-short-name.md` |
| `verification.md` | Visual verification checklists (Playwright MCP) | During and after implementation |

## Workflow

1. **Define context** — Fill in `project-context.md` with problem, vision, scope, and constraints.
2. **Outline architecture** — Fill in `architecture.md` with tech stack and application structure.
3. **Specify features** — Copy `use-cases/use-case-template.md` once per feature.
4. **Implement** — Build each use case, referencing its spec for acceptance criteria.
5. **Verify** — Follow `verification.md` checklists for each implemented use case.
