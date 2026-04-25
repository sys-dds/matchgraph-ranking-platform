# Future Service Extraction

The project should not become microservices by default. Extract only when there is a clear reason: independent scaling, separate ownership, deployment risk, or workload isolation.

Likely future services: serving API, event processing, analytics ingestion, model training/evaluation, operator control plane.

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
