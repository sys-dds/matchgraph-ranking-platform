create table recommendation_surfaces (
    id uuid primary key,
    surface_key text unique not null,
    status text not null,
    created_at timestamptz not null default now(),
    constraint recommendation_surfaces_status_check check (status in ('ENABLED', 'DISABLED'))
);

create table recommendation_surface_configs (
    id uuid primary key,
    surface_key text not null references recommendation_surfaces(surface_key) on delete cascade,
    status text not null,
    ranking_version text not null,
    allowed_sources_json jsonb not null default '[]'::jsonb,
    result_size integer not null,
    latency_budget_ms integer not null,
    freshness_config_json jsonb not null default '{}'::jsonb,
    diversity_config_json jsonb not null default '{}'::jsonb,
    fallback_config_json jsonb not null default '{}'::jsonb,
    safety_config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint recommendation_surface_configs_status_check check (status in ('ACTIVE', 'INACTIVE')),
    constraint recommendation_surface_configs_result_size_check check (result_size > 0),
    constraint recommendation_surface_configs_latency_check check (latency_budget_ms > 0)
);

create index recommendation_surface_configs_key_status_idx
    on recommendation_surface_configs (surface_key, status, created_at desc);

create table candidate_source_configs (
    id uuid primary key,
    source_key text unique not null,
    priority integer not null,
    max_candidates integer not null,
    timeout_budget_ms integer not null,
    cost_weight numeric(16,6) not null default 1,
    fallback_source text,
    health_status text not null default 'HEALTHY',
    quality_score numeric(16,6) not null default 1,
    created_at timestamptz not null default now()
);

create table source_routing_plans (
    id uuid primary key,
    request_id uuid not null,
    surface_key text not null,
    session_id uuid,
    plan_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table source_routing_plan_items (
    id uuid primary key,
    plan_id uuid not null references source_routing_plans(id) on delete cascade,
    source_key text not null,
    priority integer not null,
    max_candidates integer not null,
    timeout_budget_ms integer not null,
    cost_weight numeric(16,6) not null default 1,
    fallback_source text,
    health_status text not null,
    quality_score numeric(16,6) not null default 1,
    created_at timestamptz not null default now()
);

create table source_call_results (
    id uuid primary key,
    request_id uuid not null,
    source_key text not null,
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    duration_ms integer not null default 0,
    returned_count integer not null default 0,
    timeout boolean not null default false,
    degraded boolean not null default false,
    fallback_used boolean not null default false,
    fallback_source text,
    degraded_reason text,
    candidates_json jsonb not null default '[]'::jsonb
);

create table pre_rank_runs (
    id uuid primary key,
    request_id uuid not null,
    source_candidate_count integer not null,
    survivor_count integer not null,
    limit_count integer not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table pre_rank_items (
    id uuid primary key,
    run_id uuid not null references pre_rank_runs(id) on delete cascade,
    candidate_profile_id uuid not null,
    source_key text,
    pre_rank_score numeric(16,6) not null default 0,
    pre_rank_reasons_json jsonb not null default '[]'::jsonb,
    survived boolean not null,
    filtered_reason text,
    created_at timestamptz not null default now()
);

create table heavy_rank_runs (
    id uuid primary key,
    request_id uuid not null,
    ranking_version text not null,
    model_backed boolean not null default false,
    model_key text,
    version_key text,
    model_version_id uuid,
    timeout_budget_ms integer not null,
    fallback_used boolean not null default false,
    fallback_reason text,
    duration_ms integer not null default 0,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table heavy_rank_items (
    id uuid primary key,
    run_id uuid not null references heavy_rank_runs(id) on delete cascade,
    candidate_profile_id uuid not null,
    candidate_score numeric(16,6) not null default 0,
    model_score numeric(16,6),
    rule_score numeric(16,6),
    score_reasons_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now()
);

create table slate_optimization_runs (
    id uuid primary key,
    request_id uuid not null,
    constraints_json jsonb not null default '{}'::jsonb,
    partial_result boolean not null default false,
    warning text,
    created_at timestamptz not null default now()
);

create table slate_optimization_items (
    id uuid primary key,
    run_id uuid not null references slate_optimization_runs(id) on delete cascade,
    candidate_profile_id uuid not null,
    source_key text,
    original_position integer,
    optimized_position integer,
    selected boolean not null,
    constraint_reasons_json jsonb not null default '[]'::jsonb,
    dropped_reason text,
    created_at timestamptz not null default now()
);

create table recommendation_sessions (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    status text not null default 'ACTIVE',
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create table session_intent_events (
    id uuid primary key,
    session_id uuid not null references recommendation_sessions(id) on delete cascade,
    event_type text not null,
    source_key text,
    candidate_profile_id uuid,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table session_intent_states (
    id uuid primary key,
    session_id uuid not null references recommendation_sessions(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    source_preference_weights_json jsonb not null default '{}'::jsonb,
    recent_positive_source_signals_json jsonb not null default '{}'::jsonb,
    recent_negative_source_signals_json jsonb not null default '{}'::jsonb,
    current_intent_json jsonb not null default '{}'::jsonb,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create table feed_fatigue_events (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid,
    source_type text,
    cluster_key text,
    fatigue_score numeric(16,6) not null default 0,
    repetition_count integer not null default 0,
    suppression_reason text,
    created_at timestamptz not null default now()
);

create table fatigue_suppression_windows (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid,
    source_type text,
    cluster_key text,
    suppression_reason text not null,
    suppress_until timestamptz not null,
    fatigue_score numeric(16,6) not null default 0,
    repetition_count integer not null default 0,
    created_at timestamptz not null default now()
);

create table serving_quality_runs (
    id uuid primary key,
    request_id uuid not null,
    status text not null,
    degraded boolean not null default false,
    fallback_count integer not null default 0,
    timeout_count integer not null default 0,
    partial_result_count integer not null default 0,
    quality_warning text,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table serving_quality_stage_metrics (
    id uuid primary key,
    run_id uuid not null references serving_quality_runs(id) on delete cascade,
    stage_name text not null,
    duration_ms integer not null,
    budget_ms integer not null,
    status text not null,
    degraded boolean not null default false,
    fallback_used boolean not null default false,
    partial_result boolean not null default false,
    quality_warning text,
    created_at timestamptz not null default now()
);

create table serving_degradation_events (
    id uuid primary key,
    request_id uuid not null,
    stage_name text not null,
    degraded_reason text not null,
    fallback_used boolean not null default false,
    partial_result boolean not null default false,
    created_at timestamptz not null default now()
);

create table multi_stage_serving_requests (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    surface_key text not null,
    session_id uuid,
    status text not null,
    degraded boolean not null default false,
    served_count integer not null default 0,
    trace_json jsonb not null default '{}'::jsonb,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table multi_stage_serving_trace_steps (
    id uuid primary key,
    request_id uuid not null references multi_stage_serving_requests(id) on delete cascade,
    step_name text not null,
    status text not null,
    duration_ms integer not null default 0,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table multi_stage_serving_results (
    id uuid primary key,
    request_id uuid not null references multi_stage_serving_requests(id) on delete cascade,
    candidate_profile_id uuid not null,
    position integer not null,
    score numeric(16,6) not null default 0,
    source_types_json jsonb not null default '[]'::jsonb,
    reasons_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now()
);

create table multi_stage_serving_demo_runs (
    id uuid primary key,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table multi_stage_serving_demo_steps (
    id uuid primary key,
    demo_run_id uuid not null references multi_stage_serving_demo_runs(id) on delete cascade,
    step_key text not null,
    step_status text not null,
    trace_id uuid,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index source_routing_plans_request_idx on source_routing_plans (request_id);
create index source_call_results_request_source_idx on source_call_results (request_id, source_key);
create index pre_rank_runs_request_idx on pre_rank_runs (request_id);
create index heavy_rank_runs_request_idx on heavy_rank_runs (request_id);
create index slate_optimization_runs_request_idx on slate_optimization_runs (request_id);
create index session_intent_profile_session_idx on session_intent_states (profile_id, session_id, expires_at);
create index fatigue_profile_candidate_idx on fatigue_suppression_windows (profile_id, candidate_profile_id, suppress_until);
create index serving_requests_profile_surface_time_idx on multi_stage_serving_requests (profile_id, surface_key, created_at desc);
create index serving_quality_runs_status_time_idx on serving_quality_runs (status, created_at desc);
