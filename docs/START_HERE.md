# Start Here

MatchGraph answers one product question: who should this user see next?

The backend answer involves many systems concerns:

- candidate retrieval
- hard exclusions
- feature snapshots
- ranking versions
- feed materialisation
- experiments
- metrics
- model lifecycle
- realtime feedback
- guardrails
- recovery traces

## One-line architecture

profile -> graph -> retrieval -> features -> ranking -> feed -> serving -> feedback -> evaluation -> model lifecycle -> guardrails

## Main idea

Recommendation quality is a systems problem, not only an algorithm problem.
