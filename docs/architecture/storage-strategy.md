# Storage Strategy

PostgreSQL is the system of record. PostGIS supports location search. pgvector supports vector similarity. Redis supports online serving cache. Kafka is the event backbone foundation. ClickHouse is the analytical metrics foundation.

The key principle is choosing storage by workload, not fashion.

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
