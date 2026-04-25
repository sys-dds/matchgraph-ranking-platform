# Recommendation Backend Anti-patterns

Bad patterns include mixing eligibility with score, serving models without kill switches, running experiments without guardrails, using features without freshness checks, caching without invalidation, and adding microservices before domain boundaries are clear.

These anti-patterns are useful because they show why the project design matters.

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
