create table realtime_interaction_events (
    id uuid primary key,
    event_key text unique not null,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete set null,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    feed_item_id uuid references feed_items(id) on delete set null,
    serving_request_id uuid references multi_stage_serving_requests(id) on delete set null,
    session_id uuid,
    event_type text not null,
    source_key text,
    occurred_at timestamptz not null,
    received_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    processing_status text not null default 'RECEIVED',
    processed_at timestamptz,
    constraint realtime_interaction_events_type_check check (
        event_type in ('PROFILE_VIEW', 'LIKE', 'PASS', 'BLOCK', 'REPORT', 'MATCH_CREATED', 'FEED_DISMISS', 'SOURCE_NEGATIVE', 'SOURCE_POSITIVE')
    )
);

create table realtime_interaction_dedupe (
    event_key text primary key,
    event_id uuid not null references realtime_interaction_events(id) on delete cascade,
    first_seen_at timestamptz not null default now(),
    duplicate_count integer not null default 0
);

create table nearline_feature_materialization_runs (
    id uuid primary key,
    profile_id uuid references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table nearline_profile_features (
    id uuid primary key,
    run_id uuid references nearline_feature_materialization_runs(id) on delete set null,
    profile_id uuid not null references profiles(id) on delete cascade,
    feature_key text not null,
    numeric_value numeric(16,6),
    json_value jsonb,
    last_materialized_at timestamptz not null default now(),
    freshness_status text not null,
    unique (profile_id, feature_key)
);

create table nearline_candidate_features (
    id uuid primary key,
    run_id uuid references nearline_feature_materialization_runs(id) on delete set null,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    feature_key text not null,
    numeric_value numeric(16,6),
    json_value jsonb,
    last_materialized_at timestamptz not null default now(),
    freshness_status text not null,
    unique (candidate_profile_id, feature_key)
);

create table nearline_pair_features (
    id uuid primary key,
    run_id uuid references nearline_feature_materialization_runs(id) on delete set null,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    feature_key text not null,
    numeric_value numeric(16,6),
    json_value jsonb,
    last_interaction_at timestamptz,
    last_materialized_at timestamptz not null default now(),
    freshness_status text not null,
    unique (profile_id, candidate_profile_id, feature_key)
);

create table live_session_intent_snapshots (
    id uuid primary key,
    session_id uuid not null,
    profile_id uuid not null references profiles(id) on delete cascade,
    source_weights_json jsonb not null default '{}'::jsonb,
    positive_weights_json jsonb not null default '{}'::jsonb,
    negative_weights_json jsonb not null default '{}'::jsonb,
    confidence_score numeric(16,6) not null default 0,
    decay_factor numeric(16,6) not null default 0.85,
    explanation_json jsonb not null default '{}'::jsonb,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create table live_session_intent_decay_runs (
    id uuid primary key,
    session_id uuid not null,
    profile_id uuid not null references profiles(id) on delete cascade,
    decay_factor numeric(16,6) not null default 0.85,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table realtime_candidate_invalidations (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    event_id uuid references realtime_interaction_events(id) on delete set null,
    reason text not null,
    hard_invalidation boolean not null default false,
    expires_at timestamptz,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint realtime_candidate_invalidations_reason_check check (reason in ('PASSED', 'BLOCKED', 'REPORTED', 'FEED_DISMISSED', 'FATIGUED', 'STALE_FEATURES', 'SOURCE_NEGATIVE'))
);

create table candidate_invalidation_targets (
    id uuid primary key,
    invalidation_id uuid not null references realtime_candidate_invalidations(id) on delete cascade,
    target_key text not null,
    created_at timestamptz not null default now(),
    constraint candidate_invalidation_targets_key_check check (target_key in ('CURRENT_FEED', 'CACHE', 'CANDIDATE_POOL', 'PRE_RANK', 'SLATE', 'DELTA_REFRESH', 'FUTURE_SESSION'))
);

create table source_feedback_signals (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    session_id uuid,
    source_key text not null,
    signal_type text not null,
    signal_value numeric(16,6) not null,
    latency_ms integer,
    quality_score numeric(16,6),
    created_at timestamptz not null default now()
);

create table adaptive_source_budget_snapshots (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    session_id uuid,
    source_key text not null,
    budget_before integer not null,
    budget_after integer not null,
    reason_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table delta_feed_refresh_runs (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    serving_request_id uuid references multi_stage_serving_requests(id) on delete set null,
    session_id uuid,
    trigger_event_id uuid references realtime_interaction_events(id) on delete set null,
    status text not null,
    removed_count integer not null default 0,
    new_count integer not null default 0,
    moved_count integer not null default 0,
    unchanged_count integer not null default 0,
    degraded boolean not null default false,
    trace_id uuid,
    reason_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table delta_feed_refresh_items (
    id uuid primary key,
    run_id uuid not null references delta_feed_refresh_runs(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    item_action text not null,
    old_position integer,
    new_position integer,
    reason_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint delta_feed_refresh_items_action_check check (item_action in ('REMOVED', 'NEW', 'MOVED', 'UNCHANGED'))
);

create table online_feature_freshness_checks (
    id uuid primary key,
    profile_id uuid references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    allow_rebuild boolean not null default true,
    allow_fallback boolean not null default true,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table online_feature_freshness_results (
    id uuid primary key,
    check_id uuid not null references online_feature_freshness_checks(id) on delete cascade,
    feature_key text not null,
    profile_id uuid references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    age_ms bigint,
    max_age_ms bigint not null,
    status text not null,
    required boolean not null,
    fallback_used boolean not null default false,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint online_feature_freshness_results_status_check check (status in ('FRESH', 'STALE', 'MISSING', 'DEGRADED', 'REBUILT'))
);

create table realtime_feedback_loop_traces (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    session_id uuid,
    event_id uuid references realtime_interaction_events(id) on delete set null,
    status text not null,
    degraded boolean not null default false,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table realtime_feedback_loop_trace_steps (
    id uuid primary key,
    trace_id uuid not null references realtime_feedback_loop_traces(id) on delete cascade,
    step_key text not null,
    step_status text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint realtime_feedback_loop_trace_steps_key_check check (step_key in ('EVENT_INTAKE', 'DEDUPE', 'NEARLINE_MATERIALIZATION', 'SESSION_INTENT_UPDATE', 'CANDIDATE_INVALIDATION', 'SOURCE_ADAPTATION', 'FEATURE_FRESHNESS_CHECK', 'DELTA_REFRESH', 'NEXT_RECOMMENDATION'))
);

create table realtime_feedback_loop_demo_runs (
    id uuid primary key,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table realtime_feedback_loop_demo_steps (
    id uuid primary key,
    demo_run_id uuid not null references realtime_feedback_loop_demo_runs(id) on delete cascade,
    scenario_key text not null,
    step_status text not null,
    trace_id uuid references realtime_feedback_loop_traces(id) on delete set null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index realtime_interaction_events_profile_candidate_time_idx on realtime_interaction_events (profile_id, candidate_profile_id, occurred_at desc);
create index nearline_profile_features_lookup_idx on nearline_profile_features (profile_id, feature_key);
create index nearline_candidate_features_lookup_idx on nearline_candidate_features (candidate_profile_id, feature_key);
create index nearline_pair_features_lookup_idx on nearline_pair_features (profile_id, candidate_profile_id, feature_key);
create index realtime_candidate_invalidations_lookup_idx on realtime_candidate_invalidations (profile_id, candidate_profile_id, reason, created_at desc);
create index source_feedback_signals_lookup_idx on source_feedback_signals (profile_id, session_id, source_key, created_at desc);
create index delta_feed_refresh_runs_lookup_idx on delta_feed_refresh_runs (profile_id, feed_snapshot_id, created_at desc);
create index online_feature_freshness_checks_status_time_idx on online_feature_freshness_checks (status, created_at desc);
create index realtime_feedback_loop_traces_lookup_idx on realtime_feedback_loop_traces (profile_id, session_id, created_at desc);
