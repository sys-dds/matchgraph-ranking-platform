# Recommendation Pipeline

The core pipeline is retrieve -> filter -> feature snapshot -> rank -> optimise slate -> serve -> observe -> evaluate -> improve.

Retrieval is recall-focused. Ranking is precision-focused. Slate optimisation is presentation/product-constraint focused. Guardrails protect the user and product.

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
