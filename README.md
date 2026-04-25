# MatchGraph Ranking Platform

MatchGraph is a production-shaped Java recommendation and ranking backend.

It models the backend systems behind a discovery product: profiles, graph signals, interactions, candidate retrieval, ranking, feed materialisation, experiments, learning-to-rank, causal evaluation, multi-stage online serving, realtime feedback, safety guardrails, source backpressure, model kill switches, and final proof tests.

## Why this project exists

Most backend portfolio projects stop at CRUD. MatchGraph goes further by showing how backend systems support ranking quality, model lifecycle, experimentation, realtime behaviour, explainability, and operational recovery.

## What it proves

| Area | What it demonstrates |
| --- | --- |
| Backend architecture | Modular Spring Boot backend with visible domain boundaries |
| Ranking systems | Retrieval, features, ranking versions, feed snapshots, explanations |
| ML platform thinking | Datasets, labels, LTR registry, training runs, rollout gates |
| Experimentation | Stable assignments, metrics, shadow ranking, bandits, interleaving |
| Realtime systems | Live feedback, invalidation, delta refresh, freshness checks |
| Reliability | Guardrails, source backpressure, kill switches, fallback, final proof tests |
| Data systems | PostgreSQL, PostGIS, pgvector, Redis, Kafka, ClickHouse, Flyway |

## System map

~~~mermaid
flowchart LR
  Client --> API[Spring Boot API]
  API --> Profile[Profiles + Graph]
  Profile --> Retrieval[Candidate Retrieval]
  Retrieval --> Features[Feature Snapshots]
  Features --> Ranking[Ranking Engine]
  Ranking --> Feed[Feed Snapshots]
  Feed --> Client
  Client --> Events[Interaction Events]
  Events --> Metrics[Metrics + Evaluation]
  Metrics --> LTR[LTR Model Lifecycle]
  LTR --> Ranking
  Events --> Realtime[Realtime Feedback]
  Realtime --> Guardrails[Guardrails + Recovery]
  Guardrails --> Ranking
~~~

## Tech stack

- Java 21
- Spring Boot 3.5.x
- Maven Wrapper
- PostgreSQL 16
- PostGIS
- pgvector
- Redis
- Kafka
- ClickHouse
- Flyway
- Docker Compose
- Testcontainers
- JUnit 5

## Documentation

- `docs/START_HERE.md`
- `docs/architecture/system-overview.md`
- `docs/architecture/recommendation-pipeline.md`
- `docs/architecture/realtime-feedback-loop.md`
- `docs/api/api-tour.md`
- `docs/operations/runbook.md`
- `docs/testing/final-proof-tests.md`
- `docs/adr/`

## Local development

Copy `.env.example` to `.env`, then run Docker Compose from `infra/docker-compose/docker-compose.yml`.

Health check: `GET /actuator/health`

## Best project summary

MatchGraph is not just a recommendation algorithm. It is the backend platform around recommendations: evidence, experimentation, model governance, realtime adaptation, and operational safety.
