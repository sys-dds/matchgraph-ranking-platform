create table champion_challenger_configs (
    id uuid primary key,
    config_key text not null,
    name text not null,
    status text not null,
    champion_ranking_version text not null references ranking_versions(version_key),
    challenger_ranking_version text not null references ranking_versions(version_key),
    guardrail_config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint champion_challenger_configs_key_not_blank check (length(trim(config_key)) > 0),
    constraint champion_challenger_configs_name_not_blank check (length(trim(name)) > 0),
    constraint champion_challenger_configs_status_check check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED'))
);

create unique index champion_challenger_configs_key_idx
    on champion_challenger_configs (config_key);

create index champion_challenger_configs_status_time_idx
    on champion_challenger_configs (status, created_at desc);

create table shadow_ranking_runs (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    baseline_decision_log_id uuid not null references ranking_decision_logs(id) on delete cascade,
    champion_ranking_version text not null references ranking_versions(version_key),
    challenger_ranking_version text not null references ranking_versions(version_key),
    feature_snapshot_run_id uuid not null references feature_snapshot_runs(id) on delete cascade,
    ranking_context_json jsonb not null default '{}'::jsonb,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint shadow_ranking_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index shadow_ranking_runs_profile_time_idx
    on shadow_ranking_runs (profile_id, created_at desc);

create index shadow_ranking_runs_baseline_idx
    on shadow_ranking_runs (baseline_decision_log_id, created_at desc);

create index shadow_ranking_runs_versions_idx
    on shadow_ranking_runs (champion_ranking_version, challenger_ranking_version, created_at desc);

create table shadow_ranking_items (
    id uuid primary key,
    shadow_run_id uuid not null references shadow_ranking_runs(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    champion_position integer,
    challenger_position integer,
    champion_score numeric(16,6),
    challenger_score numeric(16,6),
    position_delta integer,
    score_delta numeric(16,6),
    reason_delta_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint shadow_ranking_items_position_check check (
        (champion_position is null or champion_position > 0)
        and (challenger_position is null or challenger_position > 0)
    )
);

create unique index shadow_ranking_items_run_candidate_idx
    on shadow_ranking_items (shadow_run_id, candidate_profile_id);

create index shadow_ranking_items_run_position_idx
    on shadow_ranking_items (shadow_run_id, challenger_position, champion_position);

create table champion_challenger_decisions (
    id uuid primary key,
    config_id uuid not null references champion_challenger_configs(id) on delete cascade,
    shadow_run_id uuid references shadow_ranking_runs(id) on delete set null,
    profile_id uuid references profiles(id) on delete set null,
    baseline_decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    challenger_improved_count integer not null default 0,
    challenger_degraded_count integer not null default 0,
    top_k_overlap numeric(16,6) not null default 0,
    average_position_delta numeric(16,6) not null default 0,
    safety_regression_count integer not null default 0,
    guardrail_status text not null,
    promotion_recommendation text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint champion_challenger_decisions_counts_check check (
        challenger_improved_count >= 0
        and challenger_degraded_count >= 0
        and safety_regression_count >= 0
    ),
    constraint champion_challenger_decisions_guardrail_check check (guardrail_status in ('PASS', 'FAIL')),
    constraint champion_challenger_decisions_recommendation_check check (promotion_recommendation in ('PROMOTE', 'HOLD', 'REJECT'))
);

create index champion_challenger_decisions_config_time_idx
    on champion_challenger_decisions (config_id, created_at desc);

create table ranking_explanation_requests (
    id uuid primary key,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    explanation_type text not null,
    status text not null,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ranking_explanation_requests_type_check check (explanation_type in ('WHY_SHOWN', 'WHY_HIDDEN', 'WHY_DOWNRANKED', 'GENERIC')),
    constraint ranking_explanation_requests_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index ranking_explanation_requests_profile_time_idx
    on ranking_explanation_requests (profile_id, created_at desc);

create index ranking_explanation_requests_candidate_time_idx
    on ranking_explanation_requests (candidate_profile_id, created_at desc);

create table ranking_explanation_results (
    id uuid primary key,
    request_id uuid not null references ranking_explanation_requests(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    explanation_type text not null,
    evidence_status text not null,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ranking_explanation_results_evidence_check check (evidence_status in ('AVAILABLE', 'PARTIAL', 'NOT_AVAILABLE'))
);

create unique index ranking_explanation_results_request_idx
    on ranking_explanation_results (request_id);

create table bandit_policies (
    id uuid primary key,
    policy_key text not null,
    name text not null,
    status text not null,
    algorithm text not null,
    epsilon numeric(8,6),
    reward_config_json jsonb not null default '{}'::jsonb,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint bandit_policies_key_not_blank check (length(trim(policy_key)) > 0),
    constraint bandit_policies_status_check check (status in ('DRAFT', 'ACTIVE', 'PAUSED')),
    constraint bandit_policies_algorithm_check check (algorithm in ('EPSILON_GREEDY', 'UCB1')),
    constraint bandit_policies_epsilon_check check (epsilon is null or (epsilon >= 0 and epsilon <= 1))
);

create unique index bandit_policies_key_idx
    on bandit_policies (policy_key);

create table bandit_arms (
    id uuid primary key,
    policy_id uuid not null references bandit_policies(id) on delete cascade,
    arm_key text not null,
    source_type text not null,
    strategy text not null,
    weight numeric(16,6) not null default 1,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint bandit_arms_key_not_blank check (length(trim(arm_key)) > 0),
    constraint bandit_arms_weight_check check (weight >= 0)
);

create unique index bandit_arms_policy_arm_idx
    on bandit_arms (policy_id, arm_key);

create table bandit_decisions (
    id uuid primary key,
    policy_id uuid not null references bandit_policies(id) on delete cascade,
    arm_id uuid not null references bandit_arms(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid references profiles(id) on delete cascade,
    context_segment text not null,
    decision_context_json jsonb not null default '{}'::jsonb,
    selected_arm_key text not null,
    selection_reason text not null,
    safe boolean not null default true,
    created_at timestamptz not null default now(),
    constraint bandit_decisions_context_not_blank check (length(trim(context_segment)) > 0)
);

create index bandit_decisions_policy_time_idx
    on bandit_decisions (policy_id, created_at desc);

create index bandit_decisions_profile_time_idx
    on bandit_decisions (profile_id, created_at desc);

create table bandit_rewards (
    id uuid primary key,
    policy_id uuid not null references bandit_policies(id) on delete cascade,
    arm_id uuid references bandit_arms(id) on delete set null,
    decision_id uuid references bandit_decisions(id) on delete set null,
    profile_id uuid references profiles(id) on delete set null,
    candidate_profile_id uuid references profiles(id) on delete set null,
    reward_event_type text not null,
    reward_value numeric(16,6) not null,
    interaction_event_id uuid references interaction_events(id) on delete set null,
    created_at timestamptz not null default now(),
    constraint bandit_rewards_event_check check (reward_event_type in ('PROFILE_VIEW', 'LIKE', 'MATCH_CREATED', 'PASS', 'BLOCK', 'REPORT'))
);

create index bandit_rewards_policy_time_idx
    on bandit_rewards (policy_id, created_at desc);

create table bandit_arm_stats (
    id uuid primary key,
    policy_id uuid not null references bandit_policies(id) on delete cascade,
    arm_id uuid not null references bandit_arms(id) on delete cascade,
    context_segment text not null,
    decision_count integer not null default 0,
    reward_count integer not null default 0,
    total_reward numeric(16,6) not null default 0,
    average_reward numeric(16,6) not null default 0,
    updated_at timestamptz not null default now(),
    constraint bandit_arm_stats_counts_check check (decision_count >= 0 and reward_count >= 0)
);

create unique index bandit_arm_stats_policy_arm_context_idx
    on bandit_arm_stats (policy_id, arm_id, context_segment);

create table interleaving_experiments (
    id uuid primary key,
    experiment_key text not null,
    name text not null,
    status text not null,
    ranker_a_version text not null references ranking_versions(version_key),
    ranker_b_version text not null references ranking_versions(version_key),
    method text not null,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint interleaving_experiments_key_not_blank check (length(trim(experiment_key)) > 0),
    constraint interleaving_experiments_status_check check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED')),
    constraint interleaving_experiments_method_check check (method = 'TEAM_DRAFT')
);

create unique index interleaving_experiments_key_idx
    on interleaving_experiments (experiment_key);

create table interleaving_sessions (
    id uuid primary key,
    experiment_id uuid not null references interleaving_experiments(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    feature_snapshot_run_id uuid not null references feature_snapshot_runs(id) on delete cascade,
    ranker_a_version text not null references ranking_versions(version_key),
    ranker_b_version text not null references ranking_versions(version_key),
    method text not null,
    context_json jsonb not null default '{}'::jsonb,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint interleaving_sessions_method_check check (method = 'TEAM_DRAFT'),
    constraint interleaving_sessions_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index interleaving_sessions_profile_time_idx
    on interleaving_sessions (profile_id, created_at desc);

create table interleaving_items (
    id uuid primary key,
    session_id uuid not null references interleaving_sessions(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    position integer not null,
    attributed_ranker text not null,
    ranker_a_position integer,
    ranker_b_position integer,
    score_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint interleaving_items_position_positive check (position > 0),
    constraint interleaving_items_ranker_check check (attributed_ranker in ('A', 'B'))
);

create unique index interleaving_items_session_candidate_idx
    on interleaving_items (session_id, candidate_profile_id);

create unique index interleaving_items_session_position_idx
    on interleaving_items (session_id, position);

create table interleaving_outcomes (
    id uuid primary key,
    session_id uuid not null references interleaving_sessions(id) on delete cascade,
    interleaving_item_id uuid references interleaving_items(id) on delete set null,
    candidate_profile_id uuid references profiles(id) on delete set null,
    interaction_event_id uuid references interaction_events(id) on delete set null,
    outcome_event_type text not null,
    attributed_ranker text,
    reward_value numeric(16,6) not null default 0,
    winner text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint interleaving_outcomes_ranker_check check (attributed_ranker is null or attributed_ranker in ('A', 'B')),
    constraint interleaving_outcomes_winner_check check (winner in ('A', 'B', 'TIE', 'INSUFFICIENT_DATA'))
);

create index interleaving_outcomes_session_time_idx
    on interleaving_outcomes (session_id, created_at desc);

create table exposure_control_policies (
    id uuid primary key,
    policy_key text not null,
    name text not null,
    status text not null,
    daily_cap integer not null,
    rolling_7_day_cap integer not null,
    policy_window_hours integer not null,
    policy_window_cap integer not null,
    long_tail_boost numeric(16,6) not null default 0,
    overexposure_downrank numeric(16,6) not null default 0,
    new_profile_minimum_boost numeric(16,6) not null default 0,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint exposure_control_policies_key_not_blank check (length(trim(policy_key)) > 0),
    constraint exposure_control_policies_status_check check (status in ('DRAFT', 'ACTIVE', 'PAUSED')),
    constraint exposure_control_policies_caps_check check (
        daily_cap >= 0 and rolling_7_day_cap >= 0 and policy_window_hours > 0 and policy_window_cap >= 0
    )
);

create unique index exposure_control_policies_key_idx
    on exposure_control_policies (policy_key);

create table candidate_exposure_events (
    id uuid primary key,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    viewer_profile_id uuid not null references profiles(id) on delete cascade,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    feed_item_id uuid references feed_items(id) on delete set null,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    ranking_version text references ranking_versions(version_key),
    experiment_key text,
    assigned_variant_key text,
    exposure_type text not null,
    position integer not null,
    context_key text not null,
    exposure_timestamp timestamptz not null default now(),
    created_at timestamptz not null default now(),
    constraint candidate_exposure_events_position_positive check (position > 0),
    constraint candidate_exposure_events_type_check check (exposure_type in ('SERVED', 'INTERLEAVED', 'DEMO'))
);

create unique index candidate_exposure_events_context_idx
    on candidate_exposure_events (context_key);

create index candidate_exposure_events_candidate_time_idx
    on candidate_exposure_events (candidate_profile_id, exposure_timestamp desc);

create index candidate_exposure_events_viewer_time_idx
    on candidate_exposure_events (viewer_profile_id, exposure_timestamp desc);

create table candidate_exposure_windows (
    id uuid primary key,
    policy_id uuid references exposure_control_policies(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    window_key text not null,
    window_start timestamptz not null,
    window_end timestamptz not null,
    exposure_count integer not null,
    exposure_cap integer not null,
    summary_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    constraint candidate_exposure_windows_count_check check (exposure_count >= 0 and exposure_cap >= 0),
    constraint candidate_exposure_windows_range_check check (window_start < window_end)
);

create unique index candidate_exposure_windows_candidate_window_idx
    on candidate_exposure_windows (candidate_profile_id, window_key, window_start, policy_id);

create table exposure_adjustments (
    id uuid primary key,
    policy_id uuid references exposure_control_policies(id) on delete set null,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    viewer_profile_id uuid references profiles(id) on delete set null,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    adjustment_reason text not null,
    boost_amount numeric(16,6) not null default 0,
    downrank_amount numeric(16,6) not null default 0,
    bounded boolean not null default true,
    safety_overridden boolean not null default false,
    context_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index exposure_adjustments_candidate_time_idx
    on exposure_adjustments (candidate_profile_id, created_at desc);

create index exposure_adjustments_decision_idx
    on exposure_adjustments (decision_log_id);

create table synthetic_population_runs (
    id uuid primary key,
    random_seed bigint not null,
    profile_count integer not null,
    cluster_count integer not null,
    compatibility_density numeric(8,6) not null,
    status text not null,
    config_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint synthetic_population_runs_counts_check check (profile_count >= 0 and cluster_count > 0),
    constraint synthetic_population_runs_density_check check (compatibility_density >= 0 and compatibility_density <= 1),
    constraint synthetic_population_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index synthetic_population_runs_seed_idx
    on synthetic_population_runs (random_seed, created_at desc);

create table synthetic_profiles (
    id uuid primary key,
    run_id uuid not null references synthetic_population_runs(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    cluster_id text not null,
    location_cluster text not null,
    synthetic_preference_vector_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create unique index synthetic_profiles_run_profile_idx
    on synthetic_profiles (run_id, profile_id);

create index synthetic_profiles_run_cluster_idx
    on synthetic_profiles (run_id, cluster_id);

create table synthetic_ground_truth_labels (
    id uuid primary key,
    run_id uuid not null references synthetic_population_runs(id) on delete cascade,
    actor_profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    compatibility_label text not null,
    expected_relevance numeric(16,6) not null,
    label_reason_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint synthetic_ground_truth_labels_label_check check (compatibility_label in ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    constraint synthetic_ground_truth_labels_relevance_check check (expected_relevance >= 0 and expected_relevance <= 1),
    constraint synthetic_ground_truth_labels_no_self check (actor_profile_id <> candidate_profile_id)
);

create unique index synthetic_ground_truth_labels_pair_idx
    on synthetic_ground_truth_labels (run_id, actor_profile_id, candidate_profile_id);

create index synthetic_ground_truth_labels_actor_idx
    on synthetic_ground_truth_labels (run_id, actor_profile_id, expected_relevance desc);

create table synthetic_evaluation_runs (
    id uuid primary key,
    synthetic_population_run_id uuid not null references synthetic_population_runs(id) on delete cascade,
    ranking_version text references ranking_versions(version_key),
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    k integer not null,
    status text not null,
    request_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint synthetic_evaluation_runs_k_positive check (k > 0),
    constraint synthetic_evaluation_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index synthetic_evaluation_runs_population_time_idx
    on synthetic_evaluation_runs (synthetic_population_run_id, created_at desc);

create table synthetic_evaluation_results (
    id uuid primary key,
    evaluation_run_id uuid not null references synthetic_evaluation_runs(id) on delete cascade,
    precision_at_k numeric(16,6) not null,
    ndcg_at_k numeric(16,6) not null,
    mrr numeric(16,6) not null,
    cluster_coverage numeric(16,6) not null,
    long_tail_coverage numeric(16,6) not null,
    exposure_distribution_json jsonb not null default '{}'::jsonb,
    safety_violation_count integer not null default 0,
    metrics_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint synthetic_evaluation_results_safety_count_check check (safety_violation_count >= 0)
);

create unique index synthetic_evaluation_results_run_idx
    on synthetic_evaluation_results (evaluation_run_id);

create table ranking_science_demo_runs (
    id uuid primary key,
    seed bigint not null,
    config_json jsonb not null default '{}'::jsonb,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ranking_science_demo_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index ranking_science_demo_runs_seed_time_idx
    on ranking_science_demo_runs (seed, created_at desc);

create table ranking_science_demo_steps (
    id uuid primary key,
    demo_run_id uuid not null references ranking_science_demo_runs(id) on delete cascade,
    step_name text not null,
    step_status text not null,
    step_result_json jsonb not null default '{}'::jsonb,
    duration_ms bigint not null default 0,
    created_at timestamptz not null default now(),
    constraint ranking_science_demo_steps_name_not_blank check (length(trim(step_name)) > 0),
    constraint ranking_science_demo_steps_status_check check (step_status in ('RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED')),
    constraint ranking_science_demo_steps_duration_check check (duration_ms >= 0)
);

create index ranking_science_demo_steps_run_idx
    on ranking_science_demo_steps (demo_run_id, created_at);
