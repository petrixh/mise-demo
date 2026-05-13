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

The mockups use a standard surface hierarchy: primary surface for cards and the main canvas, a secondary surface for grouped containers like the chat dock and KPI cards, and a tertiary surface for the page background that sits behind cards.

- Primary surface: cards, main canvas — the brightest surface, where content lives
- Secondary surface: chat dock, KPI cards, toggle tracks — subtle one-step-darker grouping
- Tertiary surface: page background behind cards — the furthest-back layer
- Border tertiary: `0.5px` hairlines at low alpha — the default for almost every divider
- Border secondary: slightly stronger — used for hover, focus, and the active tab indicator

The exact values come from the underlying theme. The hierarchy is what matters: three surface levels, two border weights.

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

Desktop uses a `1fr 220px` split for the Plan view (meal list + side panel), and `1fr 1fr` for the Reports view's chart row.

Mobile uses single column for everything except the KPI strip, which becomes `1fr 1fr` (2x2 grid). This is the only place where mobile makes a layout decision that's specifically a *responsive* choice rather than just stacking what desktop had.

## Recurring component patterns

These are the patterns that show up across views and should be reused rather than reinvented.

### KPI card

Background-secondary surface, rounded corners, no border. Small uppercase label on top, headline number below. On desktop they're in a horizontal strip with internal dividers; on mobile they're a 2x2 grid with gaps. The number can carry an inline delta (`−4%`) in a status color when the trend is meaningful.

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

A pale info-blue panel with a bulb icon and a short observation: *"Your cheaper weeks all had three vegetarian dinners. Worth locking in?"* Uses the info color family but is visually quieter than the recommendation card — no thick border, no headline. The intent is "noticing", not "instructing."

Insights should be dismissable (assume an `×` even if not drawn) and should never stack — show one at a time.

### Chat dock

Sits at the bottom of every view. Two parts: a recent AI message above (single line, secondary color, sparkle icon prefix) and an input pill below. The input pill has, left to right: plus icon (attach), placeholder text, microphone, send arrow. Placeholder text is view-specific ("Ask Mise…", "Add a column, change a chart…", "Ask about prices, alternatives…") and is a quiet hint about what the AI can do here.

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

**Use the theme's tokens where they correspond.** Surface colors, border colors and weights, border radii, type scale, and the four standard status colors (info, success, warning, danger) almost certainly already exist in the theme. Use them. Don't redefine them with literal hex values.

**Define category colors as application-level custom properties.** The five category colors (Protein, Produce, Pantry, Dairy, Other) are a Mise-specific palette and don't correspond to anything a generic theme provides. They should be declared once as custom properties — `--mise-category-protein`, `--mise-category-produce`, etc. — and referenced from charts, pills, badges, and any other place a category appears. Each category needs the same three values used in the table above: a fill, a strong stroke/accent, and a text-on-fill.

**Where mockup hex values diverge from theme defaults, prefer the theme.** Visual consistency with the surrounding ecosystem matters more than pixel-matching these mockups. The mockups exist to communicate intent; the theme provides the polish.

**The semantic rules survive any theme.** "The Thursday edited row uses the attention color" is true whether the attention color is amber, peach, or pale rose. "The recommendation card uses a 2px border in the info color" is true regardless of what hex info is. The visual language is about *which color goes where*, not *what color it is*.
