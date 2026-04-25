# Realtime Feedback Loop

Realtime feedback lets likes, passes, blocks, and reports affect future serving quickly. The safe pattern is bounded reaction: invalidate a candidate, adjust source budgets, update session intent, run delta refresh, or trigger guardrail evidence.

The unsafe pattern is allowing a single event to radically rewrite global ranking state.

## Practical takeaway

This part of the design exists because recommendation systems must be explainable, safe, measurable, and recoverable.

## Diagram

~~~mermaid
flowchart LR
  A[Input / Signal] --> B[Decision Stage]
  B --> C[Persist Evidence]
  C --> D[Serve / React]
  D --> E[Observe / Improve]
~~~
