# Data Flow

Profile and graph data feed retrieval. Interactions and nearline features feed ranking. Ranking produces feed snapshots. Feed/serving produce interaction events. Events drive metrics, evaluation, models, realtime feedback, invalidation, and guardrails.

The loop matters more than any single endpoint.

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
