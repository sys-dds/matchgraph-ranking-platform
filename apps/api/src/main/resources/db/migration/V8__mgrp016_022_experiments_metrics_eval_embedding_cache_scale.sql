alter table ranking_decision_logs
    add column ranking_context_json jsonb not null default '{}'::jsonb;

insert into ranking_versions (version_key, description, active, policy_json)
values
    (
        'v1_graph_affinity',
        'Experiment variant that gives graph proximity a stronger ranking weight.',
        false,
        '{
            "signals": {
                "shared_interest_count": 1.00,
                "graph_closeness": 1.80,
                "mutual_count": 1.10,
                "common_neighbour_count": 0.80,
                "vector_similarity": 0.65,
                "location_distance": 0.45,
                "recent_activity": 0.40,
                "profile_completeness_score": 0.35,
                "safety_penalty": -1000.0,
                "source_diversity_bonus": 0.30
            },
            "diversity": {
                "max_location_cluster_per_page": 3,
                "max_interest_cluster_per_page": 3,
                "cold_start_slot": true,
                "vector_diverse_slot": true,
                "exploration_slot": true,
                "recently_seen_penalty": -4.0
            }
        }'::jsonb
    ),
    (
        'v1_vector_affinity',
        'Experiment variant that gives vector similarity a stronger ranking weight.',
        false,
        '{
            "signals": {
                "shared_interest_count": 1.05,
                "graph_closeness": 0.80,
                "mutual_count": 0.50,
                "common_neighbour_count": 0.35,
                "vector_similarity": 1.75,
                "location_distance": 0.55,
                "recent_activity": 0.45,
                "profile_completeness_score": 0.35,
                "safety_penalty": -1000.0,
                "source_diversity_bonus": 0.30
            },
            "diversity": {
                "max_location_cluster_per_page": 3,
                "max_interest_cluster_per_page": 3,
                "cold_start_slot": true,
                "vector_diverse_slot": true,
                "exploration_slot": true,
                "recently_seen_penalty": -4.0
            }
        }'::jsonb
    )
on conflict (version_key) do nothing;

create table ranking_experiments (
    id uuid primary key,
    experiment_key text not null,
    name text not null,
    status text not null,
    traffic_percentage numeric(5,2) not null,
    holdout_percentage numeric(5,2) not null,
    guardrail_config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ranking_experiments_key_not_blank check (length(trim(experiment_key)) > 0),
    constraint ranking_experiments_name_not_blank check (length(trim(name)) > 0),
    constraint ranking_experiments_status_check check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED')),
    constraint ranking_experiments_traffic_check check (traffic_percentage >= 0 and traffic_percentage <= 100),
    constraint ranking_experiments_holdout_check check (holdout_percentage >= 0 and holdout_percentage <= 100)
);

create unique index ranking_experiments_key_idx
    on ranking_experiments (experiment_key);

create index ranking_experiments_status_time_idx
    on ranking_experiments (status, created_at desc);

create table ranking_experiment_variants (
    id uuid primary key,
    experiment_id uuid not null references ranking_experiments(id) on delete cascade,
    variant_key text not null,
    ranking_version text not null references ranking_versions(version_key),
    allocation_percentage numeric(5,2) not null,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ranking_experiment_variants_key_not_blank check (length(trim(variant_key)) > 0),
    constraint ranking_experiment_variants_allocation_check check (allocation_percentage >= 0 and allocation_percentage <= 100)
);

create unique index ranking_experiment_variants_experiment_variant_idx
    on ranking_experiment_variants (experiment_id, variant_key);

create index ranking_experiment_variants_version_idx
    on ranking_experiment_variants (ranking_version);

create table ranking_experiment_assignments (
    id uuid primary key,
    experiment_id uuid not null references ranking_experiments(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    assigned_variant_key text,
    assigned_ranking_version text not null references ranking_versions(version_key),
    holdout boolean not null,
    assignment_reason text not null,
    assignment_hash text not null,
    created_at timestamptz not null default now(),
    constraint ranking_experiment_assignments_reason_not_blank check (length(trim(assignment_reason)) > 0),
    constraint ranking_experiment_assignments_hash_not_blank check (length(trim(assignment_hash)) > 0),
    constraint ranking_experiment_assignments_holdout_variant_check check (
        (holdout and assigned_variant_key is null)
        or (not holdout and assigned_variant_key is not null)
    )
);

create unique index ranking_experiment_assignments_experiment_profile_idx
    on ranking_experiment_assignments (experiment_id, profile_id);

create index ranking_experiment_assignments_profile_idx
    on ranking_experiment_assignments (profile_id, created_at desc);

create index ranking_experiment_assignments_variant_idx
    on ranking_experiment_assignments (experiment_id, assigned_variant_key);

create table offline_evaluation_runs (
    id uuid primary key,
    ranking_version text references ranking_versions(version_key),
    experiment_key text,
    from_time timestamptz,
    to_time timestamptz,
    k integer not null,
    status text not null,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    request_json jsonb not null default '{}'::jsonb,
    constraint offline_evaluation_runs_k_positive check (k > 0),
    constraint offline_evaluation_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    constraint offline_evaluation_runs_time_range_check check (from_time is null or to_time is null or from_time <= to_time)
);

create index offline_evaluation_runs_lookup_idx
    on offline_evaluation_runs (created_at desc, ranking_version, experiment_key);

create table offline_evaluation_results (
    id uuid primary key,
    run_id uuid not null references offline_evaluation_runs(id) on delete cascade,
    precision_at_k numeric(16,6) not null,
    recall_at_k numeric(16,6),
    mrr numeric(16,6) not null,
    ndcg_at_k numeric(16,6) not null,
    coverage numeric(16,6) not null,
    diversity numeric(16,6) not null,
    negative_signal_penalty numeric(16,6) not null,
    evaluated_decision_count integer not null,
    labelled_decision_count integer not null,
    unlabelled_decision_count integer not null,
    stale_embedding_count integer not null default 0,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint offline_evaluation_results_counts_check check (
        evaluated_decision_count >= 0
        and labelled_decision_count >= 0
        and unlabelled_decision_count >= 0
        and stale_embedding_count >= 0
    )
);

create unique index offline_evaluation_results_run_idx
    on offline_evaluation_results (run_id);

create table counterfactual_evaluation_runs (
    id uuid primary key,
    baseline_decision_log_id uuid not null references ranking_decision_logs(id) on delete cascade,
    candidate_ranking_version text not null references ranking_versions(version_key),
    k integer not null,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint counterfactual_evaluation_runs_k_positive check (k > 0),
    constraint counterfactual_evaluation_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index counterfactual_evaluation_runs_baseline_idx
    on counterfactual_evaluation_runs (baseline_decision_log_id, created_at desc);

create index counterfactual_evaluation_runs_version_time_idx
    on counterfactual_evaluation_runs (candidate_ranking_version, created_at desc);

create table counterfactual_evaluation_items (
    id uuid primary key,
    run_id uuid not null references counterfactual_evaluation_runs(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    original_position integer,
    counterfactual_position integer,
    original_score numeric(16,6),
    counterfactual_score numeric(16,6),
    position_delta integer,
    top_k_change text not null,
    label_event_type text,
    metric_delta_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint counterfactual_evaluation_items_top_k_change_check check (
        top_k_change in ('ENTERED_TOP_K', 'DROPPED_FROM_TOP_K', 'UNCHANGED_TOP_K', 'UNCHANGED_OUTSIDE_TOP_K')
    )
);

create unique index counterfactual_evaluation_items_run_candidate_idx
    on counterfactual_evaluation_items (run_id, candidate_profile_id);

create index counterfactual_evaluation_items_position_idx
    on counterfactual_evaluation_items (run_id, counterfactual_position);

create table embedding_refresh_requests (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    status text not null,
    reason text not null,
    requested_by text,
    current_embedding_status text,
    current_embedding_version text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint embedding_refresh_requests_status_check check (status in ('REQUESTED', 'PLANNED', 'COMPLETED', 'CANCELLED')),
    constraint embedding_refresh_requests_reason_not_blank check (length(trim(reason)) > 0)
);

create index embedding_refresh_requests_status_profile_idx
    on embedding_refresh_requests (status, profile_id, created_at desc);

create index embedding_refresh_requests_profile_time_idx
    on embedding_refresh_requests (profile_id, created_at desc);

create table embedding_refresh_batches (
    id uuid primary key,
    status text not null,
    max_items integer not null,
    selection_reason text not null,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint embedding_refresh_batches_status_check check (status in ('CREATED', 'COMPLETED', 'FAILED')),
    constraint embedding_refresh_batches_max_items_positive check (max_items > 0),
    constraint embedding_refresh_batches_selection_reason_not_blank check (length(trim(selection_reason)) > 0)
);

create index embedding_refresh_batches_status_time_idx
    on embedding_refresh_batches (status, created_at desc);

create table embedding_refresh_batch_items (
    id uuid primary key,
    batch_id uuid not null references embedding_refresh_batches(id) on delete cascade,
    request_id uuid references embedding_refresh_requests(id) on delete set null,
    profile_id uuid not null references profiles(id) on delete cascade,
    status text not null,
    current_embedding_status text,
    current_embedding_version text,
    requested_reason text not null,
    completed_embedding_version text,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint embedding_refresh_batch_items_status_check check (status in ('PENDING', 'COMPLETED', 'FAILED')),
    constraint embedding_refresh_batch_items_reason_not_blank check (length(trim(requested_reason)) > 0)
);

create unique index embedding_refresh_batch_items_batch_profile_idx
    on embedding_refresh_batch_items (batch_id, profile_id);

create index embedding_refresh_batch_items_status_profile_idx
    on embedding_refresh_batch_items (status, profile_id, created_at desc);

create table online_serving_cache_events (
    id uuid primary key,
    profile_id uuid references profiles(id) on delete cascade,
    cache_category text not null,
    cache_key_hash text not null,
    cache_event_type text not null,
    reason text,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint online_serving_cache_events_category_not_blank check (length(trim(cache_category)) > 0),
    constraint online_serving_cache_events_key_hash_not_blank check (length(trim(cache_key_hash)) > 0),
    constraint online_serving_cache_events_type_check check (cache_event_type in ('HIT', 'MISS', 'WRITE', 'INVALIDATE'))
);

create index online_serving_cache_events_profile_time_idx
    on online_serving_cache_events (profile_id, created_at desc);

create index online_serving_cache_events_category_time_idx
    on online_serving_cache_events (cache_category, created_at desc);

create table scale_seed_runs (
    id uuid primary key,
    random_seed bigint not null,
    profile_count integer not null,
    edge_count integer not null,
    interaction_count integer not null,
    embedding_enabled boolean not null,
    location_enabled boolean not null,
    interest_cluster_count integer not null,
    allow_large boolean not null default false,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint scale_seed_runs_counts_check check (
        profile_count >= 0
        and edge_count >= 0
        and interaction_count >= 0
        and interest_cluster_count >= 0
    ),
    constraint scale_seed_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index scale_seed_runs_time_idx
    on scale_seed_runs (created_at desc);

create index scale_seed_runs_seed_idx
    on scale_seed_runs (random_seed, created_at desc);

create table ranking_benchmark_runs (
    id uuid primary key,
    seed_run_id uuid references scale_seed_runs(id) on delete set null,
    sample_profile_count integer not null,
    include_offline_evaluation boolean not null default false,
    cache_enabled boolean not null default false,
    status text not null,
    request_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ranking_benchmark_runs_sample_positive check (sample_profile_count > 0),
    constraint ranking_benchmark_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index ranking_benchmark_runs_time_idx
    on ranking_benchmark_runs (created_at desc);

create index ranking_benchmark_runs_seed_idx
    on ranking_benchmark_runs (seed_run_id, created_at desc);

create table ranking_benchmark_results (
    id uuid primary key,
    benchmark_run_id uuid not null references ranking_benchmark_runs(id) on delete cascade,
    profile_id uuid references profiles(id) on delete set null,
    retrieval_latency_ms bigint not null,
    snapshot_latency_ms bigint not null,
    ranking_latency_ms bigint not null,
    feed_latency_ms bigint not null,
    evaluation_latency_ms bigint,
    candidate_count integer not null,
    cache_hit_count integer not null default 0,
    cache_miss_count integer not null default 0,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ranking_benchmark_results_latency_check check (
        retrieval_latency_ms >= 0
        and snapshot_latency_ms >= 0
        and ranking_latency_ms >= 0
        and feed_latency_ms >= 0
        and (evaluation_latency_ms is null or evaluation_latency_ms >= 0)
    ),
    constraint ranking_benchmark_results_counts_check check (
        candidate_count >= 0
        and cache_hit_count >= 0
        and cache_miss_count >= 0
    )
);

create index ranking_benchmark_results_run_idx
    on ranking_benchmark_results (benchmark_run_id, created_at desc);

create index ranking_benchmark_results_profile_idx
    on ranking_benchmark_results (profile_id, created_at desc);
