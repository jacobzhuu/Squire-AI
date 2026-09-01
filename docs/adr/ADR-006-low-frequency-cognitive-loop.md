# ADR-006: Low-Frequency Cognitive Loop

## Status
Accepted (spec §4/ADR-006, §17)

## Context
LLMs cannot and must not drive per-tick behavior.

## Decision
The Cognitive Loop (LLM) runs only on: new natural-language intent, clarification needs,
task failure requiring replan, user follow-up questions, deliberate high-level decisions.
The Runtime Loop (every tick / divided ticks) handles navigation, combat, threats,
behavior trees, task progress and stuck detection, and never waits for the LLM.

## Consequences
- Combat/movement code has zero LLM dependencies.
- LLM calls are budgeted (spec §66); proactive reminders use local templates (spec §68).
