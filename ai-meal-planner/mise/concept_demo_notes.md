# Mise — Demo notes

## Purpose of this document

This file captures everything in the demo codebase that exists *only because it's a demo* — the shortcuts, the stubs, the fixed data, the corners cut, and the scenarios designed for the stage.

Everything documented here would be replaced or expanded in a production version. The companion file `concept.md` describes the system as if it were real. If you remove this file and re-read `concept.md`, you have a clean concept document for a production app.

A developer who clones this repository should be able to:

1. Read `concept.md` and understand what the system is trying to be.
2. Read this file and understand what's faked, why, and how to replace it.
3. Edit a YAML file or two and see the AI's behavior change in response — that's the central teaching pattern of the demo.

## What's stubbed, and why

### Recipe library

**In demo.** A seeded library of recipes lives as individual YAML files under `demo/data/recipes/`. Each recipe is one file. The orchestrator's `RecipeCatalog` capability is backed by a `FileSystemRecipeCatalog` implementation that reads from this directory.

**Why stubbed.** Real recipe sourcing requires either a third-party API with licensing implications, or a curated database. A fixed seed library lets developers see and edit the data directly, and lets them add their own recipes simply by adding a file.

**Production path.** Replace `FileSystemRecipeCatalog` with an implementation backed by a real recipe service. The orchestrator and the UI need no changes.

### Pricing data

**In demo.** Three stores (Prima as the default supermarket, Lidl as a discounter, a local market as a third option). Each is a YAML file under `demo/data/stores/`. Prices are static. Sale flags and sale-until dates are static. Each store also declares a `detour_minutes_from_route` figure that lets the AI reason about convenience.

**Why stubbed.** Real grocery pricing is regional, fragile, and ages quickly. Static data is reproducible, inspectable, and gives developers a clear way to see the AI's reasoning by editing files.

**Demo benefit.** This is a feature, not a limitation. Editing a store file and re-running the demo produces visibly different AI reasoning. This is a deliberate teaching moment — the demo is more convincing because the data is inspectable, not less.

**Production path.** Replace `StubbedPriceCatalog` with a real implementation (scraper, partner API, manual upload, user-supplied receipts). Same interface, no changes elsewhere.

### Nutrition data

**In demo.** Per-recipe macros are pre-computed and stored in each recipe's YAML. No real-time estimation occurs.

**Why stubbed.** Estimating nutrition from arbitrary recipe text is a research-grade problem and not the demo's point.

**Future direction worth highlighting.** The `NutritionEstimator` interface exists as a stub returning canned values for known ingredients and "unknown" otherwise. The "AI estimates the macros for your own recipe" story is a natural next-step feature where the orchestrator would call this service over the LLM. Worth mentioning in talks as the obvious next chapter; not built.

### Household, preferences, and authentication

**In demo.** Single household, single user, in-memory. No login. The household is initialized from a default persona file on startup.

**Production path.** Authentication, per-user persistence, multi-household support, sharing within a household.

### Conversation history

**In demo.** In-memory. Resets on app restart.

**Production path.** Persistent conversation store per household, with the ability to scroll back through prior weeks' exchanges.

### Plan history for reports

**In demo.** Four weeks of past plans are seeded at startup so the Reports view has something to chart. These weeks are referenced consistently across the seed data (the same beef ragu shows up in both the Plan view's current week and in the Reports view's leaderboard).

**Production path.** Real plan history accumulates naturally as the user uses the app.

## Sample personas

Personas ship with the demo as JSON files under `demo/data/personas/`. They exist so demo recordings can showcase different household types without rebuilding seed data each time.

1. **Two adults, omnivore, midweek cooking** *(default).* Around €90/week, no kids at home, occasional weekend hosting.
2. **Single cook, time-poor, batch friendly.** Around €55/week. Prefers Sunday cook-once-eat-twice patterns. Lower weeknight time budget.
3. **Vegetarian couple, food-curious.** Around €80/week. Variety-seeking, willing to try new cuisines.

Switching personas is done by editing `demo/data/active_persona.txt` and restarting the app. (In production this would be account-based.)

## Seed dataset shape

- ~80 recipes in the library, spread across cuisines and difficulty levels, with enough overlap that consolidation actually consolidates in the shopping list.
- 3 stores with overlapping inventories. At least one item per store is meaningfully cheaper than at the others, so the AI has something to recommend on.
- 4 weeks of historical plans for the default persona. Other personas have abbreviated history.
- A pantry file declaring "default staples" (olive oil, salt, pepper, garlic) so the shopping list doesn't ask the user to buy salt every week.

Numbers above are targets, not constraints. The dataset can grow as long as it stays readable.

## Demo scenarios

These are the moments worth recording, walking through on stage, or scripting for video. They're listed roughly in narrative order for a single ~2-minute walkthrough.

### Scenario 1: Cold open

Open app, see populated current week with all four KPIs, the meal grid, the cost-by-category chart, and the chat. The AI's job has *already* happened — this is the default state, not a blank screen.

### Scenario 2: Single-meal swap

Chat: *"Make Thursday vegetarian, kid is having a friend over."* The Thursday row updates, receives an "edited" pill, weekly cost stat drops slightly, the cost-by-category bar for Protein shrinks, the shopping list reflows. Multiple components moving from one sentence is the point.

### Scenario 3: Constraint negotiation

Chat: *"Get this week under €80 without dropping the Thursday curry."* The AI proposes a plan that respects the constraint, narrates the trade-offs in chat ("swapped salmon for mackerel, cut the Saturday cheese plate"), and the UI updates accordingly.

### Scenario 4: Cross-view chat

From the Plan view, chat: *"Go to reports and add a kcal-per-euro column to the leaderboard."* The view switches; the new column appears, highlighted as freshly added. Demonstrates that the conversation is not scoped per-view.

### Scenario 5: Chart transform

In Reports, chat: *"Show the category breakdown as a horizontal bar instead of a donut."* The chart transforms in place. Demonstrates that view shape is part of state and that the AI can change presentation, not just data.

### Scenario 6: The stubbed-price reveal *(headline moment)*

In Shopping, chat: *"Should I bother with Lidl this week?"* AI responds that no, €3 isn't worth a separate trip.

Switch to the IDE. Open `demo/data/stores/lidl.yaml`. Change salmon price from `2.70` to `1.50`. Save. Return to the app and re-ask the same question. The AI now recommends the Lidl stop because the savings have crossed the threshold.

This is the single most important demo moment for the "AI reasons over real data, it isn't a script" pitch. Worth practicing.

### Scenario 7: AI offers an alternative

Chat: *"I don't want a second stop, but I want the savings."* The AI suggests swapping the Friday salmon for trout from Prima at a similar price point. The plan updates; the shopping list reflows; no Lidl stop needed.

## Out-of-scope for the demo

- Authentication and user management.
- Persistence beyond the seed files and in-memory session state.
- Real-time price feeds.
- AI-estimated nutrition for user-submitted recipes.
- Image generation or photography for meals (static or no images).
- Internationalization (English only, euro currency only).
- A "lite" Shopping view without pricing was considered as a fallback. The stubbed-service approach made it unnecessary.

## Path to production

If a developer wanted to turn this demo into a real product, the steps would be roughly:

1. Replace `FileSystemRecipeCatalog` with a real recipe source.
2. Replace `StubbedPriceCatalog` with a real pricing implementation, per-region.
3. Add authentication and per-user persistence.
4. Move conversation history to a persistent store.
5. Build out the `NutritionEstimator` for user-submitted recipes.
6. Resolve grocery-store partnerships, scraping policy, or manual price-entry workflows.

The orchestrator, the chat component, the views, the data model, and the user-facing behavior described in `concept.md` would not need to change for any of those steps. That's the demo's central argument made concrete.
