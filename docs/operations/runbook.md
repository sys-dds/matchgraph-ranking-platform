# Operations Runbook

First checks during an issue: affected surface, request id, feed snapshot id, ranking version, experiment assignment, source health, feature freshness, model state, degraded flag, fallback reason, and recovery trace.

## Recovery mindset

Prefer bounded recovery over broad resets. Preserve evidence before cleanup.
