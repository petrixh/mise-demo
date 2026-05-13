# Mise — Concept

> *Mise is a working name. From "mise en place" — everything in its place before you cook.*

## Overview

Mise is a weekly meal planner that uses AI to personalize a sensible default around the user's life. The user never faces an empty screen; instead they start from a coherent weekly plan and use natural language to make it theirs.

The same plan is viewable in three shapes — a calendar of meals, a consolidated shopping list, and a multi-week report — and the AI can both modify the plan and reshape how it's viewed. A single chat thread spans all three views.

The system exists to demonstrate that a deeply AI-integrated business application doesn't have to be a chat bot bolted onto a form. Chat, grids, charts, and dashboard widgets are coordinated by a single orchestrator that operates over shared state.

## Goals

- Provide a usable weekly meal plan with minimal up-front configuration.
- Let users modify the plan, the shopping list, and the reports through natural language alone.
- Make AI behavior visible — when something changes, the user can see what changed and ask why.
- Coordinate multiple UI surfaces through one shared conversation and one orchestrator.
- Demonstrate that AI can both modify *data* (swap a meal) and modify *presentation* (turn a pie chart into a bar chart, add a column to a table).

## Non-goals

- Replacing a dedicated recipe app or grocery service.
- Producing medically accurate nutrition data.
- Multi-user collaboration or sharing within a household.
- Real-time inventory integration with any specific retailer.

## Core concepts

**Household** — The set of people, dietary constraints, preferences, and budget targets that scope a plan. A household has a single shared conversation, plan history, and pantry.

**Meal** — A single dinner slot for a specific date. Has a recipe, a portion count, and a status (planned, edited, cooked, skipped). May carry a free-text note from the user or the AI.

**Recipe** — A reusable specification: name, ingredients with quantities, prep time, default servings, category tags (vegetarian, fish, kid-friendly, hosting-worthy), and estimated macros and cost. A meal is an instance of a recipe at a particular date for a particular party size.

**Plan** — A week of meals, plus rolled-up stats (cost, prep time, average calories, cuisine variety, dietary mix).

**Shopping list** — A view derived from a plan, the household's pantry, and one or more store catalogs. Items are consolidated across recipes, grouped by aisle, and routed to a recommended store or stores.

**Pantry** — Items the household already has on hand. Items present in the pantry can be subtracted from a shopping list.

**Store** — A place to shop, with a catalog of items, optional sale data, and a "detour cost" expressing how out-of-the-way the store is for this household. Stores are how the system reasons about convenience versus price.

**Report** — A view over multiple weeks of plan history showing cost trends, category breakdowns, and per-meal value rankings. Reports default to standard shapes; users transform them through chat.

**Insight** — A short, AI-generated observation surfaced to the user without being asked, when the system notices a pattern worth their attention. Insights are advisory, sparingly used, and always dismissable.

**Conversation** — A persistent chat thread scoped to the household. The conversation is aware of the current view but does not reset when the user switches between views.

## User stories

### Getting started

- As a new user, I describe my household in a short chat exchange (number of people, dietary constraints, rough budget, hated foods) and immediately see a generated week, without filling out a multi-step form.
- As a returning user, I see my current week populated when I open the app. I never face an empty screen.

### Working with the meal plan

- As a user, I can ask the system to change one meal in natural language ("make Thursday vegetarian, kid is having a friend over") and see the change applied to the meal grid, the weekly stats, and the shopping list at the same time.
- As a user, I can express constraints in natural language ("I'm hosting Saturday, weeknights under 30 minutes, no oven this week") and have the plan adjust to fit.
- As a user, I can ask the system to negotiate a trade-off ("get under €80 without dropping the Thursday curry") and receive a plan that respects the priority I named, with the trade-offs explained.
- As a user, I can see at a glance which meals the AI most recently changed. The visual indicator persists briefly after the change.
- As a user, I can ask "why did you swap that?" and get an answer grounded in my stated preferences and the plan's constraints.
- As a user, I can undo a recent AI change either through an undo affordance or by asking in chat ("put it back").
- As a user, I can pin a meal so the AI won't touch it during subsequent edits.

### Working with the shopping list

- As a user, I see a consolidated shopping list grouped by aisle, with quantities combined across recipes that use the same ingredient.
- As a user, I can mark items as "already have" (pantry) so they drop from the list and the system avoids re-purchasing.
- As a user, I receive a recommended store that minimizes the number of stops, not just the total cost.
- As a user, I see notes about specific items where a different store would offer a meaningful saving ("salmon is €3 cheaper at Lidl this week").
- As a user, I can toggle between "one store" and "cheapest mix" modes when I want to optimize for cost over convenience.
- As a user, I can ask whether a specific detour is worth it ("should I bother with Lidl this week?") and receive a reasoned answer that weighs savings against effort.
- As a user, I can ask the system to propose an alternative that avoids a detour ("swap salmon for something Prima carries cheaply").
- As a user standing in a store, I can open the shopping list on my phone and check off items as I find them, with the list reflowing to keep remaining items visible.

### Working with reports

- As a user, I see standard cost and nutrition reports over multiple weeks without configuring anything.
- As a user, I can ask the system to add a derived column to a table in natural language ("add a calories-per-euro column") and see it appear immediately.
- As a user, I can ask the system to change a chart's shape ("show the category breakdown as a horizontal bar instead of a donut") and see it transform.
- As a user, I can ask "why was last week cheaper than usual?" and get a grounded answer that points to specific meals or ingredients.
- As a user, I receive occasional unprompted insights ("your cheaper weeks all had three vegetarian dinners — worth locking in?") presented as observations, not alerts.

### Cross-cutting

- As a user, I have one chat thread that follows me across the Plan, Shopping, and Reports views. Saying "go to reports and add a column" works from any view.
- As a user, I can mute insights or adjust their frequency in chat.
- As a user, my preferences (allergies, hated foods, time constraints, recurring hosting events) persist across weeks. I never have to repeat them.
- As a user, when the AI changes something, I see *what* changed in the UI and can ask *why* in chat.

## Quality requirements

**Latency.** AI-driven changes that affect the visible UI should reflect in under 2 seconds for typical edits. Larger reasoning tasks (multi-constraint negotiation, multi-store comparison) may take up to 5 seconds, with progressive feedback in the chat.

**Plausibility.** Default plans and AI-generated suggestions must be coherent meals a real person would cook. No invented dishes, no chaotic ingredient combinations.

**Inspectability.** For any AI decision shown in the UI, the user can ask "why?" and receive a grounded explanation. The system never refuses to explain itself.

**Reversibility.** Every AI-driven change to a plan or a view can be undone within the same session.

**Numerical honesty.** The system never invents prices, macros, or quantities. When it doesn't have data, it says so.

**Tone.** Warm, pragmatic, brief. The AI talks like a competent friend, not a chatbot or an enthusiastic assistant. It does not narrate what it's about to do; it does it and reports concisely.

**Responsive.** The app is designed mobile-first and scales up cleanly to tablet and desktop. Weekly planning happens on the couch and at the kitchen counter; the shopping list gets used standing in an aisle. The chat, the meal grid, the charts, and the shopping list all need to work at narrow widths. The orchestrator, the conversation, and the AI's capabilities are identical across form factors — only the layout adapts.

## Architectural principles

**The orchestrator mediates; it does not store.** The AI orchestrator coordinates between services and the UI. It does not own the household, the plan, the catalog, or the conversation log — those live in their own services.

**Services sit behind interfaces.** Recipes, prices, and nutrition data are consumed through well-defined interfaces. Concrete implementations are pluggable.

**One conversation, many views.** All three views share the same chat thread. The AI knows which view the user is currently in but does not lose context when the user switches.

**View shape is part of state.** When the user asks for a bar chart instead of a pie, that change is part of their session preferences, not a one-off rendering. It persists until the user (or the AI) changes it again.

**AI output is data, not just text.** When the AI changes a plan or adds a column, it produces structured updates the UI applies. Free-text explanation accompanies but does not replace the structured change.

**The orchestrator's capabilities are bounded.** The AI cannot do anything the application hasn't given it a capability for. Adding capabilities is a deliberate development act, not an emergent behavior.

## Out-of-scope capabilities documented for future work

- AI-estimated nutrition for user-submitted recipes (text-in, structured macros out).
- AI-suggested pantry restocking based on usage history.
- Calendar integration (hosting events, travel days picked up automatically).
- Recipe-to-plan suggestions ("I want to cook this — fit it into my week").
- Multi-language support.
