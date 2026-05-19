# Cost-by-category panel: Vaadin Chart issue

## Status

The "Cost by category" panel on the Plan view was migrated to a Vaadin Chart
(BAR) during the UI sweep, then reverted to plain DOM bars because the chart's
axis labels never render visible text in this Vaadin/Highcharts version. This
document captures what was tried and what failed so we can revisit later.

## Stack

- Vaadin Flow **25.2.0-alpha5**
- `vaadin-charts-flow` 25.2.0-alpha5 (bundled Highcharts, exact version not
  printed in the build output)
- App runs in production mode by default via `./mvnw spring-boot:run`
  (`vaadin:build-frontend` is bound to the lifecycle, so CSS / JS changes
  require either a frontend rebuild or a server restart)

## Goal

Match `ai-meal-planner/mise/Plan-desktop.png`: each row stacks the category
**name** + **€value** on the top line and a thin proportional **bar** below.
See `Design.png` next to this file.

```
Protein                €32.10
████████████████████████████
Produce                €22.80
██████████████████
…
```

## What works in the chart

- `ChartType.BAR` with one `DataSeries` of four `DataSeriesItem` (one per
  active category) renders the **bars correctly**: width is proportional to
  the value, fill colour is per-point via `setColor(new SolidColor(hex))`,
  bar thickness is controlled by `PlotOptionsBar.setPointWidth(...)`.
- `MiseChart.applyTheme()` (transparent canvas, hairline axes, panel-tone
  text colour) works as expected for the chart frame itself.
- The phantom-axis bug documented in `MiseChart` Javadoc (constructor must
  not call `applyTheme()`) is already worked around and not the problem
  here.

## What does NOT work

The categorical x-axis labels render as **empty `<text>` elements**, no
matter how they are configured:

| Configuration                                                       | Result                                                  |
|---------------------------------------------------------------------|---------------------------------------------------------|
| `xLabels.setFormat("HELLO")`                                        | 4 empty `<text>` elements at the correct y positions    |
| `xLabels.setFormat("{value}")`                                      | 4 empty `<text>` (just a `​` zero-width space tspan)   |
| `xLabels.setFormatter("function(){ return 'DBG-' + this.value; }")` | Same — empty elements. Formatter never propagates       |
| Setting `useHTML: true` via the same path                           | Labels group has **zero children** — no DOM at all       |
| `xAxis[0].update({ labels: { format, formatter, useHTML, ... }})` from a post-`chart-load` `executeJs` call | Highcharts confirms `xa.options.labels.formatter` is a function and `xa.options.labels.format === 'STATIC'`, but the rendered SVG `<text>` is still empty |

The bars and series otherwise render normally, so the chart instance is
healthy — only the axis-label text path is broken.

### Confirmed diagnostics

Inside the shadow DOM of `vaadin-chart`, immediately after first render:

```js
const hc = chart.configuration;                       // Highcharts chart instance
hc.xAxis[0].categories;                               // ["Protein","Produce","Pantry","Dairy"] ✓
hc.xAxis[0].options.labels.format;                    // "HELLO" — propagated ✓
hc.xAxis[0].options.labels.formatter;                 // null — Vaadin's setFormatter() never reaches Highcharts
hc.xAxis[0].options.labels.formatter.call({value:'Protein',pos:0,chart:hc});
                                                      // → "Protein €47.93" — formatter is callable when injected via executeJs,
                                                      //   but the rendered <text> is still empty.
```

```js
chart.options.xAxis;                                  // {}  ← the JSON sent client-side is empty
chart.options.series;                                  // []  ← also empty; the chart is driven by `configuration`, not `options`
```

So two distinct things go wrong:

1. **`Labels.setFormatter(String)` is silently dropped.** The Java `Labels`
   object has the formatter string (`userOptions.labels.formatter` shows
   `typeof === "function"`), but it does **not** end up in the Highcharts
   axis's `options.labels.formatter`. Only `setFormat(String)` makes it
   through.
2. **`xAxis.labels.format` and `xAxis.labels.formatter` both produce empty
   text on rendering**, even when injected directly via `xAxis[0].update()`
   from a post-`chart-load` `executeJs` callback. The `<text>` elements are
   created at the correct y coordinates but contain no characters.

This appears specific to `ChartType.BAR`. The same overall pattern is used
successfully in `ReportsView` for LINE and COLUMN charts, which render their
axis labels correctly.

## Things tried (chronological)

1. **Default labels** — bars render, labels render as the category name in
   the left gutter (default Highcharts behaviour).  Bar gets squeezed.
2. **3-column row layout**: name in left gutter (default axis label), value
   as `dataLabels.inside=false` past the bar's right end, thin
   `pointWidth=8`. Worked and was the last green committed state
   (`69096eb`). Doesn't match the new design (single-line per row).
3. **In-bar combined label**: `dataLabels.setFormatter(...)` returning
   `category + €value` inside the bar. Worked, but crowded the bar fill and
   forced `minPointLength=80` which destroyed proportionality.
4. **HTML axis labels above the bar** (the target design):
   `useHTML=true` + `reserveSpace=false` + `align=LEFT` + `y=-12` + JS
   formatter spanning the full plot width. Labels group ends up empty.
5. **`executeJs` post-render patch** to `xAxis[0].update({ labels: {...}})`
   from a `chart-load` event handler. The Highcharts internals confirm the
   formatter is now a real function and `useHTML: true` is set, but the
   rendered `<text>` elements remain empty.
6. **Static `format: 'STATIC'` via the same `executeJs` patch** — also
   renders empty `<text>` elements. This rules out the formatter itself as
   the cause; it is the BAR axis-label text-emission path that is broken.

## What we reverted to

Plain DOM, two-line stacked rows: one `Div.mise-category-row` per category
holding `Span.mise-category-label` + `Span.mise-category-amount` on the
first grid line and `Div.mise-category-bar-track` > `Div.mise-category-bar-fill`
on the second. Inline `width` (percentage of `maxCost`) and inline
`background` (from `CategoryColors.HEX`) are the only runtime-computed
styles. See `CostByCategoryPanel.buildRow(...)` and the
`.mise-category-row` / `.mise-category-bar-*` CSS in `mise-plan.css`.

`MiseChart` and `CategoryColors` remain in `ui.shared` — Reports still
uses `MiseChart` successfully (line + column charts), and the colour
palette is shared across Plan + Reports.

## Possible next steps

- File an upstream issue against `vaadin-charts-flow` 25.2.x with a minimal
  BAR + categorical-xAxis + `Labels.setFormat`/`setFormatter` reproducer.
- Once upstream confirms / patches, retry the original target: HTML axis
  labels above the bar (no `pointWidth` gutter on right). The current DOM
  implementation is small and easy to swap back to a chart.
- Alternative path: use the Highcharts `renderer.text()` / `renderer.label()`
  API from a `chart-load` callback to draw the label nodes directly,
  bypassing the broken axis-label pipeline. Not attempted yet.
