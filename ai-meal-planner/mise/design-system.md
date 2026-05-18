# Mise — Design system

This document captures the visual decisions made while sketching the Mise mockups. It is not exhaustive — the mockups themselves are the source of visual truth — but it pins down the rules behind the patterns so new components can be designed consistently without reverse-engineering the screenshots.

The design system is theme-agnostic. It describes intent — what each color means, what each spacing rule expresses — independent of which underlying token set ends up providing the values. The implementation should map these intents to whatever design tokens the chosen theme provides (surface colors, border radii, semantic status colors, typographic scale). Where the mockup uses a specific hex value, treat it as a reference for intent, not a literal hand-off; an underlying theme's defaults will usually be the right answer.

## Color

### Semantic categories

Five categories appear in cost breakdowns, charts, and aisle groupings. Each has a stable identity across the app — the same color always means the same category, whether on a chart, a pill, or a row.

| Category | Use | Fill | Stroke / strong | Text on fill |
|---|---|---|---|---|
| Protein | Highest spend bucket, anchors the donut | `#7F77DD` | `#534AB7` | `#26215C` |
| Produce | Vegetables, fruit, the "veg" tag | `#1D9E75` | `#0F6E56` | `#04342C` |
| Pantry | Dry goods, oils, canned | `#D85A30` | `#993C1D` | `#4A1B0C` |
| Dairy & eggs | Cheese, yoghurt, eggs | `#D4537E` | `#993556` | `#4B1528` |
| Other | Fallback / miscellaneous | `#B4B2A9` | `#5F5E5A` | `#2C2C2A` |

Two rules for category color:

The same category gets the same color everywhere. The Protein purple in the cost donut is the same Protein purple that would tint a "protein-heavy" insight badge or a chart axis.

Categories are encoded by color, not by ordering or by alphabetical accident. Don't cycle through a rainbow as new categories appear — pick from the ramp above. If a new category genuinely needs a new color, pick a ramp that doesn't already carry semantic weight elsewhere in the app (avoid blue, green, amber, red for category use — see below).

### System & status colors

These colors carry meaning beyond aesthetics. Reusing them for categorical or decorative purposes will confuse the visual language.

| Role | Use | Light fill | Strong text |
|---|---|---|---|
| Info / recommendation | "Best store" card border, AI suggestion callout, fish tag | `#E6F1FB` | `#0C447C` |
| Success | Cost-decreased deltas ("−4%"), under-budget indicators | `#EAF3DE` | `#27500A` |
| Edited / attention | "Just changed by AI" highlight, save-elsewhere hints, new-column flags | `#FAEEDA` | `#633806` |

The edited/attention color is the most distinctive of the three because it carries a recurring meaning across the app: **"the AI just did something here."** It appears as:

- A row background tint on a meal whose recipe was just swapped (Plan view, Thursday row)
- A pill labeled "edited" on the same row
- An inline pill labeled "new" on a freshly-added table column header (Reports view, kcal/€ column)
- A column background tint behind the same new column
- A horizontal strip inside a row when a different store has a meaningful saving on that item (Shopping view, salmon row)

The fill and text combination is consistent across all of these uses. The pill version sits at `font-size: 10px, padding: 2px 7px, border-radius: 10px`. The row-tint version uses the same fill at ~30-40% opacity (`rgba(250, 238, 218, 0.3)` to `0.4`).

The edit highlight should fade after a short period — long enough to be noticed (a few seconds), short enough not to clutter on subsequent edits. This isn't decorative; it's diegetic. It's how the user *sees* what the orchestrator did.

### Tags

Small inline pills marking dietary attributes on meals. Same shape as the edited pill, different palette.

| Tag | Fill | Text |
|---|---|---|
| veg | `#EAF3DE` (Produce light) | `#27500A` |
| fish | `#E6F1FB` (Info light) | `#0C447C` |
| edited | `#FAEEDA` (Attention) | `#633806` |

Tags are sentence-case (`veg`, `fish`, `edited`), never uppercase. Section headers and labels use uppercase tracking for visual rhythm — tags don't, because they're inline with body text.

### Neutrals

The mockups use a two-step surface hierarchy: a slightly lighter **panel** surface for the view content (Plan, Shopping, Reports), and a one-step-darker **chrome** surface for the persistent app frame (header, tabs, chat dock). The page background sits behind everything.

- Panel surface: the view's main canvas — slightly lighter, hosts content
- Chrome surface: header, tabs, chat dock — one step darker, frames the app
- Border tertiary: `0.5px` hairlines at low alpha — the default for almost every divider, both around panels and between internal sections
- Border secondary: slightly stronger — used for hover, focus, and the active tab indicator

These two surfaces must be **opaque solid colors**, not translucent overlays. The persistent chat dock is fixed-positioned over the view; if its background is translucent, scrolling content bleeds through and the dock reads as transparent. The implementation declares them as the two app-level custom properties `--mise-chrome-bg` and `--mise-panel-bg` (see "Implementation note" below) instead of relying on `--vaadin-background-container`, which Aura defines as a ~4% translucent overlay.

The hierarchy is what matters: two opaque surface levels (chrome darker, panel lighter), separated by a hairline, with two border weights.

### Color rules summarized

Don't introduce a new accent color without a reason. Five category colors plus three status colors is the entire palette. Anything new should reach for an existing color first.

Never use color alone to encode a state — pair it with a label, an icon, or a pill. The Thursday "edited" row has the amber tint *and* the "edited" pill, and the salmon save-hint has the amber strip *and* the tag icon *and* the explanatory text. This is partly accessibility and partly clarity: if the user can't immediately answer "what changed?" the highlight is wasted.

## Typography

The mockups use the host theme's default sans-serif stack at the sizes below. Two weights only — 400 for body, 500 for emphasis. No 600 or 700. Bold is reserved for headings and headline numbers; never used mid-sentence.

| Role | Size | Weight | Notes |
|---|---|---|---|
| Headline number (KPI value) | 18px | 500 | The big stat on a KPI card |
| Body (default) | 13-14px | 400 | Most UI text. 14px in lists, 13px in dense areas |
| Meal name / list item primary | 14px | 400 | Slightly heavier visual weight despite same weight, because it's the first line |
| Secondary text / meta | 11-12px | 400 | Prep time, calorie counts, item quantities, dates |
| Tiny labels (KPI label, aisle header) | 10-11px | 500 | Often uppercase with `letter-spacing: 0.04em` to `0.08em` |
| Tag / pill | 10-11px | 500 | Sentence case |
| Chat message body | 13px | 400 | Slightly looser line-height (`1.45`-`1.55`) for readability |
| Chat input placeholder | 13px | 400 | Tertiary text color |

Two patterns worth calling out:

**Aisle headers and KPI labels are uppercase with tracking** (`PRODUCE`, `BEST STORE THIS WEEK`, `WEEKLY COST`). Sentence case is preferred elsewhere — these uppercase labels are deliberately distinct because they're structural, not content.

**Numbers are tabular.** Wherever multiple numbers stack vertically (the prices column in the shopping list, the KPI cards, the leaderboard), they use `font-variant-numeric: tabular-nums` so columns align. This is small but matters at a glance.

## Spacing & layout

### Border radii

The mockups use three radii, in roughly increasing order of softness:

| Use | Approximate radius |
|---|---|
| Pills, tags, toggle buttons | full-pill at the heights used (~`10px`) |
| Cards, list rows, chat input | small (~`8px`) |
| Outer container, panel groupings | medium (~`12px`) |
| Chat input pill | large (~`20px`) |

The underlying theme's `medium` and `large` tokens should cover most of these. The pill radius for tags and the larger pill on the chat input are deliberately distinct shapes — they signal "discrete inline marker" and "interactive input" respectively, and that distinction matters more than the exact pixel value.

### Borders

Almost every divider is `0.5px solid` at low alpha. This sounds invisible but renders as a clean hairline on high-DPI screens, which is the whole point — visual structure without visual noise.

The single exception is the "Best store this week" card on the Shopping view, which uses `2px solid` in the info color. This 2px stroke is reserved exclusively for marking something as the AI's recommended choice — using it elsewhere will dilute the meaning.

### Padding & gaps

These aren't strict rules, but they're the values used across the mockups and worth being consistent with:

- Card padding: `12-14px` on horizontal, `10-14px` on vertical
- List row padding: `10-12px` vertical, `14px` horizontal
- Gap between KPI cards in a grid: `8px`
- Gap between sections in a panel: `12-14px`
- Gap inside an inline element (e.g. between an icon and its label): `6-8px`

### Grids

Desktop uses a `1fr 220px` split for the Plan view (meal list + side panel), and `1fr 1fr` for the Reports view's chart row. The split lives **inside** the view panel — the two columns share the panel's background and are separated only by a `border-right` hairline on the left column, not by a gap.

Mobile uses single column for everything except the KPI strip, which becomes `1fr 1fr` (2x2 grid). This is the only place where mobile makes a layout decision that's specifically a *responsive* choice rather than just stacking what desktop had. On mobile the right-side sidebar from desktop stacks below the main list — still inside the same panel, separated by a hairline rather than a gap.

## Recurring component patterns

These are the patterns that show up across views and should be reused rather than reinvented.

### View panel

Every primary view (Plan, Shopping, Reports) is rendered as **one filled panel** on the panel surface, with internal sections separated only by 0.5px hairlines and no gaps. The panel is the only rounded/bordered container; the sections inside it (KPI strip, list, sidebar) have transparent backgrounds. The persistent chat dock sits below the panel on the chrome surface, visually distinct.

This is a deliberate departure from a "stack of cards" layout. Three separate cards with gaps between them implies three loosely-related things; one panel with internal dividers implies one coherent dataset (the week) viewed through several lenses. The mockups consistently use the single-panel approach — see `mise_meal_planner_plan_view.html` — and it's what gives each view its "single object" feel.

On desktop the panel hosts a header strip (KPI / summary row), a body grid (`1fr 220px` for Plan, `1fr 1fr` for Reports chart row), and content sections divided by hairlines. On mobile the panel takes full width, the body collapses to one column, the KPI strip becomes a 2×2 internal grid, and side content stacks below the main list — all inside the same panel.

### KPI card

Inside the view panel, the KPI strip is a four-column grid (desktop) or 2×2 grid (mobile). Each cell has no background of its own — it sits on the panel surface — and is separated from its neighbours by a 0.5px hairline (right border on desktop, internal grid hairlines on mobile). Small uppercase label on top, headline number below. The number can carry an inline delta (`−4%`) in a status color when the trend is meaningful.

### Meal row (Plan view)

Three columns: day chip (`MON`), meal info (name + meta), and tag/status. Day chip is uppercase, 11px, weight 500, secondary color. Meta line is "prep · cost · kcal" in secondary text at 11-12px. Tags align right. Rows are separated by `0.5px` dividers.

When edited by the AI, the row gets a subtle amber tint and an "edited" pill. This is the canonical "AI just did this" pattern.

### List row with checkbox (Shopping view)

Three columns: checkbox, item info (name + meta), and price-with-store-chip. Checkbox is `18px` (mobile) or `14px` (desktop), unfilled. Store chip is a small pill in the info color family. The item meta line carries context like the quantity *and* the reason ("Mon + Thu broth" — telling the user which meal needs it).

### Save-elsewhere hint

A horizontal strip beneath the affected item: amber fill, dark amber text, a small tag icon, and a one-line message ("Save €3 at Lidl — on sale through Sun"). It deliberately sits inside the same logical row as the item it refers to, indented to align with the item name rather than the checkbox column. This is how the AI surfaces cross-store savings without splitting the row across two stores.

### Recommendation card

Reserved for one specific use: the AI's recommended choice on the Shopping view. White surface, `2px` info-color border, info-color label uppercase at top, store name as the headline, supporting context as body text, and a `One store / Cheapest mix` toggle below. This is the only `2px` border in the app — guard it carefully.

### AI insight callout

There are two variants of the AI insight, each with a different shape and a different scope:

1. **Banner variant** — top of the view outlet, between tabs and the panel. A pale info-blue surface with a bulb icon, a short observation, an "Act on it" button, and a dismiss `×`. Used for cross-view, user-actionable insights ("Your cheaper weeks all had three vegetarian dinners. Worth locking in?"). One banner at a time, dismissable.
2. **Inline paragraph variant** — non-dismissable, hairline-separated note inside an existing panel section. No filled background, no buttons — just an `info-circle` icon and one sentence in secondary text, with a `border-top` separator above it. Used to surface a contextual observation about whatever the section already shows ("Salmon Friday accounts for 35% of protein cost. Swap to mackerel saves €4.80." beneath the cost-by-category bars on Plan; the same shape appears below the Reports cost trend chart).

The two variants share the same icon family, the same info-blue accent, and the same "noticing, not instructing" tone — but they differ in scope. The banner is the AI starting a conversation; the inline paragraph is the AI annotating a chart. Don't use a filled callout for an inline annotation, and don't use a hairline-only note for a top-level recommendation.

Insights should never stack within the same scope: one banner at a time, one inline paragraph per panel section.

### Chat dock

Sits at the bottom of every view, on the **chrome surface** (one step darker than the panel above it) with a top hairline and a soft top-shadow that lifts it visually. The dock is `position: fixed` so it never scrolls with the view — the view itself reserves bottom padding to keep its last row reachable. The dock must be **fully opaque**; if its background is translucent, the meal grid bleeds through and the conversation becomes unreadable.

**Collapsed state** (default): a single line of "last AI reply" preview with the sparkle icon, plus the input pill below. The input pill has, left to right: plus icon (attach), placeholder text, microphone, send arrow. Placeholder text is view-specific ("Ask Mise…", "Add a column, change a chart…", "Ask about prices, alternatives…") and is a quiet hint about what the AI can do here.

**Expanded state** (input focused): the dock grows upward to reveal a scrollable message history above the input. The history shares the dock's chrome background — same color, fully opaque — so it visually merges into one expanded surface.

**Message alignment.** User turns are right-aligned (avatar and username on the right, bubble flush to the right edge, max-width ~85%). Mise turns are left-aligned with the same max-width. Right-alignment is real positioning, not just avatar reordering: the bubble has `margin-left: auto` so its right edge meets the dock's right padding. This makes the conversation legible at a glance — *which side spoke* is encoded by position, the same way every other chat UI does it.

**AI working / error indicator.** While a turn is in flight the dock carries an `.ai-working` state: the sparkle icon shimmers and the most recent avatar gets a slow blue glow ring. If the LLM endpoint cannot be reached (Spring AI returns a null/blank response), the dock switches to an `.ai-error` state — the same shimmer and glow, but in the system **error** color instead of info-blue — and a one-line error Notification appears at the bottom. The error state clears automatically on the next successful submit. Use the error color *only* here and in the Notification; never elsewhere as a decoration.

The chat dock is identical between desktop and mobile. This is deliberate — it's the most consistent, most important interaction in the app, and varying it by viewport would undercut the "one chat, many views" pitch.

### Toggle track

A segmented control on a secondary-surface track. Active segment gets a white fill, `0.5px` border, weight 500; inactive segments are secondary-color text only. Used for the `One store / Cheapest mix` decision on Shopping; could appear elsewhere when there's a binary view choice that's part of state (e.g. `Bar / Donut` for the category chart in Reports).

### Tabs

Top of every view: three equal-width segments (Plan, Shopping, Reports). Active tab has a `2px` bottom border in primary text color and weight 500; inactive tabs are secondary color and no border. Tabs are top-anchored on both mobile and desktop — explicitly *not* a bottom tab bar — because the chat dock owns the bottom of the screen.

## Iconography

All icons in the mockups are from the Tabler icon set (outline style only, no filled variants). Sizes:

- Inline with text or controls: `14-16px`
- Section markers (chart-type indicators in panel labels): `14px`
- AI sparkle icon (the recurring "this is from the AI" marker): `13-14px` inline, in secondary color
- Navigation icons (top bar, chevrons): `16-20px`

The sparkle icon (`ti-sparkles`) is the AI marker. It appears on the chat dock above the input, in the recommendation card label, in the AI insight callout, and on the "Mise added..." inline note in Reports. Reserve it for genuinely AI-generated content. Don't sprinkle it decoratively.

## Responsive

The app is designed mobile-first and scales up. A few rules:

The chat dock never moves. It is bottom-anchored at every viewport size. Its content is identical.

The KPI strip is the only layout that explicitly reshapes between viewports: horizontal on desktop, 2x2 on mobile.

Tables become cards on mobile. The Reports leaderboard, which is a true table on desktop with sortable columns, becomes a stack of cards on mobile — each row's primary info (meal name, cost) on top, secondary info (date, calories, kcal/€) on the bottom. AI-added columns become amber-tinted pills on the bottom row.

Side panels stack below. The Plan view's right-hand cost-by-category panel becomes a section below the meal list on mobile. Same content, same colors.

Tabs stay at the top. See above.

Save-hint strips and edited highlights work the same at both sizes. They are designed to be horizontal strips and scale naturally.

## Implementation note

The colors, sizes, and rules above describe intent. They are not literal values to copy into stylesheets — the underlying theme should provide most of them, and where it doesn't, application-level CSS custom properties should fill the gap.

A few principles for mapping:

**Use the theme's tokens where they correspond.** Border weights, border radii, type scale, and the four standard status colors (info, success, warning, danger) almost certainly already exist in the theme. Use them. Don't redefine them with literal hex values.

**Define application-level tokens where the theme can't be relied on.** Three categories of token currently need to be declared by Mise itself in `styles.css`:

- `--mise-chrome-bg` and `--mise-panel-bg` — the two opaque surface levels (chrome darker, panel lighter). Vaadin's Aura theme defines `--vaadin-background-container` as a translucent overlay rather than a solid color, which makes the fixed chat dock appear transparent when stacked over a view panel. Declaring opaque chrome/panel tokens at the app level avoids that. Use `light-dark()` so they switch with the page color scheme.
- `--mise-panel-border` — the hairline separator used inside view panels and around the chat dock. Centralising it keeps every internal divider consistent.
- `--mise-category-protein`, `--mise-category-produce`, `--mise-category-pantry`, `--mise-category-dairy`, `--mise-category-other` — the five Mise-specific category colors. Each declared once at the app level and referenced from charts, pills, badges, and any other place a category appears. Each category needs the same three values used in the color table above: a fill, a strong stroke/accent, and a text-on-fill.
- `--mise-info` and `--mise-error` — the AI working / error indicator colors, used by the chat dock animations and matching error Notifications. Kept as app tokens so the indicator can move in lockstep with the rest of the visual language.

**Where mockup hex values diverge from theme defaults, prefer the theme.** Visual consistency with the surrounding ecosystem matters more than pixel-matching these mockups. The mockups exist to communicate intent; the theme provides the polish. The exception is when a theme's default is functionally wrong for the pattern — a translucent surface where the design calls for an opaque one, for example. In that case, declare an app-level token rather than fighting the theme inline on every selector.

**The semantic rules survive any theme.** "The Thursday edited row uses the attention color" is true whether the attention color is amber, peach, or pale rose. "The recommendation card uses a 2px border in the info color" is true regardless of what hex info is. The visual language is about *which color goes where*, not *what color it is*.
