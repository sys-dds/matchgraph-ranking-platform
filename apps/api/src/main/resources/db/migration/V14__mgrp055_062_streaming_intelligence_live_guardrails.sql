create table if not exists streaming_feature_window_runs (
    id uuid primary key,
    status text not null,
    window_keys_json jsonb not null,
    approximate boolean not null default true,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists streaming_profile_feature_windows (
    id uuid primary key,
    run_id uuid not null references streaming_feature_window_runs(id) on delete cascade,
    profile_id uuid not null,
    window_key text not null,
    impressions bigint not null default 0,
    views bigint not null default 0,
    likes bigint not null default 0,
    passes bigint not null default 0,
    blocks bigint not null default 0,
    reports bigint not null default 0,
    match_creations bigint not null default 0,
    feed_dismisses bigint not null default 0,
    source_positive bigint not null default 0,
    source_negative bigint not null default 0,
    delta_refreshes bigint not null default 0,
    latency_ms_avg numeric(12,4),
    timeout_count bigint not null default 0,
    fallback_count bigint not null default 0,
    approximate boolean not null default true,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists streaming_candidate_feature_windows (
    id uuid primary key,
    run_id uuid not null references streaming_feature_window_runs(id) on delete cascade,
    candidate_profile_id uuid not null,
    window_key text not null,
    impressions bigint not null default 0,
    views bigint not null default 0,
    likes bigint not null default 0,
    passes bigint not null default 0,
    blocks bigint not null default 0,
    reports bigint not null default 0,
    match_creations bigint not null default 0,
    feed_dismisses bigint not null default 0,
    source_positive bigint not null default 0,
    source_negative bigint not null default 0,
    delta_refreshes bigint not null default 0,
    latency_ms_avg numeric(12,4),
    timeout_count bigint not null default 0,
    fallback_count bigint not null default 0,
    approximate boolean not null default true,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists streaming_source_feature_windows (
    id uuid primary key,
    run_id uuid not null references streaming_feature_window_runs(id) on delete cascade,
    source_key text not null,
    window_key text not null,
    impressions bigint not null default 0,
    views bigint not null default 0,
    likes bigint not null default 0,
    passes bigint not null default 0,
    blocks bigint not null default 0,
    reports bigint not null default 0,
    match_creations bigint not null default 0,
    feed_dismisses bigint not null default 0,
    source_positive bigint not null default 0,
    source_negative bigint not null default 0,
    delta_refreshes bigint not null default 0,
    returned_candidates bigint not null default 0,
    empty_result_count bigint not null default 0,
    safety_filtered_count bigint not null default 0,
    latency_ms_avg numeric(12,4),
    timeout_count bigint not null default 0,
    fallback_count bigint not null default 0,
    approximate boolean not null default true,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists streaming_surface_feature_windows (
    id uuid primary key,
    run_id uuid not null references streaming_feature_window_runs(id) on delete cascade,
    surface_key text not null,
    window_key text not null,
    requests bigint not null default 0,
    degraded_responses bigint not null default 0,
    partial_responses bigint not null default 0,
    served_count_avg numeric(12,4),
    fallback_count bigint not null default 0,
    timeout_count bigint not null default 0,
    latency_ms_avg numeric(12,4),
    approximate boolean not null default true,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists candidate_trend_runs (
    id uuid primary key,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists candidate_trend_scores (
    id uuid primary key,
    run_id uuid not null references candidate_trend_runs(id) on delete cascade,
    candidate_profile_id uuid not null,
    surface_key text,
    location_bucket text,
    source_key text,
    hotness_score numeric(12,6) not null,
    trend_direction text not null,
    velocity_score numeric(12,6) not null,
    safety_negative_score numeric(12,6) not null,
    bounded_boost numeric(12,6) not null,
    boost_allowed boolean not null,
    boost_blocked_reason text,
    explanation_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists candidate_trend_events (
    id uuid primary key,
    candidate_profile_id uuid not null,
    event_type text not null,
    trend_score_id uuid references candidate_trend_scores(id) on delete set null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists source_health_snapshots (
    id uuid primary key,
    source_key text not null,
    surface_key text,
    latency_p50_ms numeric(12,4),
    latency_p95_ms numeric(12,4),
    timeout_rate numeric(12,6) not null default 0,
    empty_result_rate numeric(12,6) not null default 0,
    duplicate_rate numeric(12,6) not null default 0,
    safety_filtered_rate numeric(12,6) not null default 0,
    quality_score numeric(12,6) not null default 1,
    health_status text not null,
    evidence_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists source_backpressure_actions (
    id uuid primary key,
    source_key text not null,
    surface_key text,
    backpressure_action text not null,
    budget_before integer not null,
    budget_after integer not null,
    expires_at timestamptz,
    reason_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists live_quality_anomaly_runs (
    id uuid primary key,
    status text not null,
    approximate boolean not null default true,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists live_quality_anomalies (
    id uuid primary key,
    run_id uuid not null references live_quality_anomaly_runs(id) on delete cascade,
    anomaly_type text not null,
    severity text not null,
    affected_surface text,
    affected_source text,
    affected_model_key text,
    affected_version_key text,
    observed_value numeric(12,6),
    baseline_value numeric(12,6),
    threshold_value numeric(12,6),
    recommended_action text not null,
    evidence_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists experiment_guardrail_runs (
    id uuid primary key,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists experiment_guardrail_decisions (
    id uuid primary key,
    run_id uuid not null references experiment_guardrail_runs(id) on delete cascade,
    experiment_key text not null,
    variant_key text,
    surface_key text,
    trigger_anomaly_id uuid references live_quality_anomalies(id) on delete set null,
    guardrail_status text not null,
    decision_action text not null,
    reason_json jsonb not null default '{}'::jsonb,
    paused_at timestamptz,
    resumed_at timestamptz,
    created_at timestamptz not null default now()
);

create table if not exists model_kill_switch_events (
    id uuid primary key,
    model_key text not null,
    version_key text not null,
    event_type text not null,
    trigger_anomaly_id uuid references live_quality_anomalies(id) on delete set null,
    trigger_gate_run_id uuid,
    kill_reason text,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists model_kill_switch_states (
    id uuid primary key,
    model_key text not null,
    version_key text not null,
    status text not null,
    trigger_anomaly_id uuid references live_quality_anomalies(id) on delete set null,
    trigger_gate_run_id uuid,
    kill_reason text,
    killed_at timestamptz,
    restored_at timestamptz,
    require_rollout_gate_reapproval boolean not null default true,
    detail_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    unique (model_key, version_key)
);

create table if not exists cache_invalidation_nodes (
    id uuid primary key,
    node_type text not null,
    node_ref text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (node_type, node_ref)
);

create table if not exists cache_invalidation_edges (
    id uuid primary key,
    from_node_id uuid not null references cache_invalidation_nodes(id) on delete cascade,
    to_node_id uuid not null references cache_invalidation_nodes(id) on delete cascade,
    edge_type text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (from_node_id, to_node_id, edge_type)
);

create table if not exists cache_invalidation_runs (
    id uuid primary key,
    trigger_node_type text not null,
    trigger_node_ref text not null,
    global_invalidation boolean not null default false,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists cache_invalidation_actions (
    id uuid primary key,
    run_id uuid not null references cache_invalidation_runs(id) on delete cascade,
    action_type text not null,
    target_node_type text not null,
    target_node_ref text not null,
    execution_status text not null,
    reason_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists realtime_operations_demo_runs (
    id uuid primary key,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists realtime_operations_demo_steps (
    id uuid primary key,
    demo_run_id uuid not null references realtime_operations_demo_runs(id) on delete cascade,
    scenario_key text not null,
    status text not null,
    optional boolean not null default false,
    trace_id uuid,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists realtime_recovery_traces (
    id uuid primary key,
    scenario_key text not null,
    status text not null,
    degraded boolean not null default false,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists realtime_recovery_trace_steps (
    id uuid primary key,
    trace_id uuid not null references realtime_recovery_traces(id) on delete cascade,
    step_key text not null,
    status text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_streaming_profile_windows_lookup on streaming_profile_feature_windows(profile_id, window_key, created_at desc);
create index if not exists idx_streaming_candidate_windows_lookup on streaming_candidate_feature_windows(candidate_profile_id, window_key, created_at desc);
create index if not exists idx_streaming_source_windows_lookup on streaming_source_feature_windows(source_key, window_key, created_at desc);
create index if not exists idx_streaming_surface_windows_lookup on streaming_surface_feature_windows(surface_key, window_key, created_at desc);
create index if not exists idx_candidate_trend_scores_candidate_time on candidate_trend_scores(candidate_profile_id, created_at desc);
create index if not exists idx_source_health_status_time on source_health_snapshots(source_key, health_status, created_at desc);
create index if not exists idx_live_anomalies_severity_type_time on live_quality_anomalies(severity, anomaly_type, created_at desc);
create index if not exists idx_experiment_guardrails_lookup on experiment_guardrail_decisions(experiment_key, guardrail_status, created_at desc);
create index if not exists idx_model_kill_states_lookup on model_kill_switch_states(model_key, version_key, status);
create index if not exists idx_cache_invalidation_nodes_lookup on cache_invalidation_nodes(node_type, node_ref);
create index if not exists idx_recovery_trace_scenario_time on realtime_recovery_traces(scenario_key, created_at desc);
