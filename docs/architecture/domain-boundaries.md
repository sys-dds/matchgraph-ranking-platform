# Domain Boundaries

Each package should own one business concept. Profile owns user state. Graph owns relationships and exclusions. Retrieval owns candidate generation. Ranking owns scoring decisions. Feed owns materialised results. LTR owns model lifecycle. Realtime owns live feedback and invalidation.

This makes the modular monolith credible because the boundaries are not random folders; they map to real product responsibilities.

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
