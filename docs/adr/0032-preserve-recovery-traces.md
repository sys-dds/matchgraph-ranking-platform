# ADR 0032: Preserve recovery traces

## Status

Accepted

## Context

The project is designed to be understandable, defensible, and production-shaped. This decision captures why the design exists instead of leaving it implicit.

## Decision

Preserve recovery traces.

## Consequences

This improves clarity, operability, and interview defensibility. It also adds some structure and maintenance overhead.

## Trade-off

The project chooses explicitness over minimalism because ranking platforms become hard to debug when decisions are hidden.

## Interview note

This ADR is useful because it turns an implementation choice into a clearly explained engineering trade-off.
