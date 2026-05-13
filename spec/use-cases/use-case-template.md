# UC-[NNN]: [Feature Title]

> Copy this template for each feature as `use-case-NNN-short-name.md`.
> Replace all `[bracketed text]` with your content.

---

**As a** [role/actor], **I want to** [capability] **so that** [business value/benefit].

**Status:** [Draft | Approved | Implemented]
**Date:** [YYYY-MM-DD]

---

## Main Flow

[Describe the happy path from the user's perspective. Write in first person as if you are the user.]

- [I open / I navigate to...]
- [I see...]
- [I do X...]
- [The system responds with Y...]
- [Continue until completion]

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | [Business rule — e.g., "All fields are mandatory"] |
| BR-02 | [Business rule — e.g., "Sold-out items are visible but cannot be selected"] |
| BR-03 | [Business rule — e.g., "Maximum 6 items per transaction"] |

---

## Acceptance Criteria

- [ ] [Criterion 1 — testable statement of expected behaviour]
- [ ] [Criterion 2 — edge case or validation check]
- [ ] [Criterion 3]

---

## UI / Routes

[Describe layout or interaction requirements. Reference a mockup if available.]

- [Layout or component description]
- [Key interaction or state]

| Route | Access | Notes |
|-------|--------|-------|
| `[/path]` | [public/authenticated] | [Vaadin @Route] |

---

## Verification

> Per-UC checklist. Methodology lives in `../verification.md` §§1–2a; this section records what *this* UC needs. Drop sub-sections that don't apply (e.g. no `#### Visual comparison` if no mockup, no `#### AI` for non-AI UCs).

**Verified by:** [Name/Agent]
**Date:** [YYYY-MM-DD]

#### Functional

- [ ] Main flow works end-to-end as described above
- [ ] All business rules enforced (list BR-IDs: [BR-01, BR-02, ...])
- [ ] All acceptance criteria pass
- [ ] Error/edge cases handled appropriately

#### Visual

- [ ] Page layout matches expectations
- [ ] Interactive elements respond correctly (hover, focus, click)
- [ ] Loading states and transitions are smooth
- [ ] Responsive at mobile (390) and desktop (1920) widths

#### Visual comparison (where a mockup exists)

- [ ] Component placement matches the mockup
- [ ] Color usage honors the design system
- [ ] Typography hierarchy matches
- [ ] Spacing and density feel close
- [ ] Recurring patterns are reused, not reinvented

#### AI (where applicable)

- [ ] AI responses grounded in real data (no fabricated prices / quantities / macros)
- [ ] Tool calls produce the expected DB writes
- [ ] Conversation persists across restart
- [ ] Latency within budget

#### Result

- **Status:** [Pass / Fail / Partial]
- **Notes:** [Any issues found or follow-up items]
