# Verification

> Visual verification process using Playwright MCP, plus a per-use-case checklist.
> Copy the checklist (section 2) for each implemented use case.

---

## 1. Visual Verification Process

Use the Playwright MCP server to visually verify each use case after implementation.

### Default Browser Resolution

Unless the use case specifies a particular resolution or size, use **1920x1080** as the default browser resolution for all visual verification.

### Steps

1. **Ensure the application is running**
2. **Navigate to the route** — open the page defined in the use case's UI/Routes section
3. **Walk through the main flow** — perform each step from the use case's Main Flow
4. **Take screenshots** — capture the page state at key interaction points
5. **Check visual appearance:**
   - Layout matches expectations (spacing, alignment, sizing)
   - Typography is readable and consistent
   - Interactive elements are clearly identifiable
   - Responsive behaviour works at common breakpoints (mobile, tablet, desktop)
6. **Record results** — note any visual issues in the per-use-case checklist below

---

## 2. Per-Use-Case Verification Checklist

> Copy this section for each use case. Name it: **UC-[NNN]: [Feature Title]**

### UC-[NNN]: [Feature Title]

**Use case spec:** [`use-case-NNN-name.md`](use-cases/use-case-NNN-name.md)
**Verified by:** [Name/Agent]
**Date:** [YYYY-MM-DD]

#### Functional

- [ ] Main flow works end-to-end as described in the spec
- [ ] All business rules are enforced (list BR-IDs: [BR-01, BR-02, ...])
- [ ] All acceptance criteria pass
- [ ] Error/edge cases handled appropriately

#### Visual

- [ ] Page layout matches expectations
- [ ] Interactive elements respond correctly (hover, focus, click)
- [ ] Loading states and transitions are smooth
- [ ] Responsive at mobile and desktop widths

#### Result

- **Status:** [Pass / Fail / Partial]
- **Notes:** [Any issues found or follow-up items]
