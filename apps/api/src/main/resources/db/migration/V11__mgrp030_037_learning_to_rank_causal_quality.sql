alter table ranking_science_demo_steps
    drop constraint if exists ranking_science_demo_steps_status_check;

alter table ranking_science_demo_steps
    add constraint ranking_science_demo_steps_status_check
        check (step_status in ('RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED', 'SKIPPED_OPTIONAL', 'FAILED_CRITICAL'));

create table training_dataset_runs (
    id uuid primary key,
    dataset_key text unique not null,
    source_window_start timestamptz,
    source_window_end timestamptz,
    label_window_hours integer not null,
    status text not null,
    config_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint training_dataset_runs_key_not_blank check (length(trim(dataset_key)) > 0),
    constraint training_dataset_runs_label_window_check check (label_window_hours > 0),
    constraint training_dataset_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create table training_examples (
    id uuid primary key,
    dataset_run_id uuid not null references training_dataset_runs(id) on delete cascade,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    feed_item_id uuid references feed_items(id) on delete set null,
    feature_snapshot_run_id uuid references feature_snapshot_runs(id) on delete set null,
    feature_snapshot_id uuid references candidate_feature_snapshots(id) on delete set null,
    ranking_version text,
    position integer,
    shown_at timestamptz,
    source_types_json jsonb not null default '[]'::jsonb,
    serving_features_json jsonb not null default '{}'::jsonb,
    offline_features_json jsonb not null default '{}'::jsonb,
    label_json jsonb not null default '{}'::jsonb,
    label_value numeric(16,6) not null default 0,
    label_positive boolean not null default false,
    label_negative boolean not null default false,
    label_neutral boolean not null default false,
    label_window_hours integer not null,
    propensity numeric(16,6),
    propensity_source text,
    created_at timestamptz not null default now(),
    constraint training_examples_label_window_check check (label_window_hours > 0),
    constraint training_examples_position_check check (position is null or position > 0),
    constraint training_examples_propensity_check check (propensity is null or (propensity >= 0 and propensity <= 1))
);

create index training_examples_dataset_idx
    on training_examples (dataset_run_id, profile_id, candidate_profile_id);

create index training_examples_decision_idx
    on training_examples (decision_log_id, candidate_profile_id);

create index training_examples_feed_idx
    on training_examples (feed_snapshot_id, feed_item_id);

create table training_labels (
    id uuid primary key,
    training_example_id uuid not null references training_examples(id) on delete cascade,
    label_type text not null,
    label_value numeric(16,6) not null,
    event_id uuid,
    event_time timestamptz,
    label_window_hours integer not null,
    source text not null,
    created_at timestamptz not null default now(),
    constraint training_labels_type_not_blank check (length(trim(label_type)) > 0),
    constraint training_labels_window_check check (label_window_hours > 0),
    constraint training_labels_source_check check (source in ('INTERACTION', 'MATCH', 'SYNTHETIC', 'LONG_TERM_REWARD'))
);

create index training_labels_example_idx
    on training_labels (training_example_id, created_at);

create table training_dataset_quality_reports (
    id uuid primary key,
    dataset_run_id uuid not null unique references training_dataset_runs(id) on delete cascade,
    example_count integer not null,
    labelled_count integer not null,
    positive_count integer not null,
    negative_count integer not null,
    neutral_count integer not null,
    missing_feature_count integer not null,
    stale_embedding_count integer not null,
    propensity_coverage numeric(16,6) not null,
    position_distribution_json jsonb not null default '{}'::jsonb,
    source_distribution_json jsonb not null default '{}'::jsonb,
    label_distribution_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint training_dataset_quality_counts_check check (
        example_count >= 0
        and labelled_count >= 0
        and positive_count >= 0
        and negative_count >= 0
        and neutral_count >= 0
        and missing_feature_count >= 0
        and stale_embedding_count >= 0
        and propensity_coverage >= 0
        and propensity_coverage <= 1
    )
);

create table feature_parity_runs (
    id uuid primary key,
    dataset_run_id uuid references training_dataset_runs(id) on delete set null,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    status text not null,
    compared_count integer not null default 0,
    matched_count integer not null default 0,
    skewed_count integer not null default 0,
    missing_online_count integer not null default 0,
    missing_offline_count integer not null default 0,
    not_comparable_count integer not null default 0,
    tolerance_config_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint feature_parity_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    constraint feature_parity_runs_counts_check check (
        compared_count >= 0
        and matched_count >= 0
        and skewed_count >= 0
        and missing_online_count >= 0
        and missing_offline_count >= 0
        and not_comparable_count >= 0
    )
);

create index feature_parity_runs_dataset_idx
    on feature_parity_runs (dataset_run_id, created_at desc);

create index feature_parity_runs_decision_idx
    on feature_parity_runs (decision_log_id, created_at desc);

create table feature_parity_results (
    id uuid primary key,
    run_id uuid not null references feature_parity_runs(id) on delete cascade,
    training_example_id uuid references training_examples(id) on delete set null,
    feature_name text not null,
    online_value_json jsonb,
    offline_value_json jsonb,
    numeric_delta numeric(16,6),
    status text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint feature_parity_results_status_check check (status in ('MATCH', 'SKEWED', 'MISSING_ONLINE', 'MISSING_OFFLINE', 'NOT_COMPARABLE'))
);

create index feature_parity_results_run_feature_idx
    on feature_parity_results (run_id, feature_name);

create table ltr_models (
    id uuid primary key,
    model_key text unique not null,
    name text not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ltr_models_key_not_blank check (length(trim(model_key)) > 0),
    constraint ltr_models_status_check check (status in ('DRAFT', 'ACTIVE', 'RETIRED'))
);

create table ltr_training_runs (
    id uuid primary key,
    model_key text not null,
    version_key text not null,
    dataset_run_id uuid not null references training_dataset_runs(id) on delete cascade,
    algorithm text not null,
    status text not null,
    feature_names_json jsonb not null default '[]'::jsonb,
    config_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ltr_training_runs_algorithm_check check (algorithm in ('LOCAL_LINEAR_WEIGHTED', 'LOCAL_LOGISTIC', 'PAIRWISE_PREFERENCE')),
    constraint ltr_training_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create index ltr_training_runs_model_dataset_idx
    on ltr_training_runs (model_key, version_key, dataset_run_id, created_at desc);

create table ltr_model_versions (
    id uuid primary key,
    model_id uuid not null references ltr_models(id) on delete cascade,
    model_key text not null,
    version_key text not null,
    model_type text not null,
    status text not null,
    feature_schema_version text not null,
    training_dataset_run_id uuid references training_dataset_runs(id) on delete set null,
    training_run_id uuid references ltr_training_runs(id) on delete set null,
    metrics_json jsonb not null default '{}'::jsonb,
    eligibility_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    activated_at timestamptz,
    constraint ltr_model_versions_status_check check (status in ('DRAFT', 'TRAINED', 'CANDIDATE', 'SHADOW', 'APPROVED', 'ACTIVE', 'REJECTED', 'RETIRED')),
    constraint ltr_model_versions_unique_key unique (model_key, version_key)
);

create index ltr_model_versions_model_status_idx
    on ltr_model_versions (model_key, status, created_at desc);

create unique index ltr_model_versions_one_active_idx
    on ltr_model_versions (model_key)
    where status = 'ACTIVE';

create table ltr_model_feature_schemas (
    id uuid primary key,
    model_id uuid not null references ltr_models(id) on delete cascade,
    model_key text not null,
    feature_schema_version text not null,
    feature_names_json jsonb not null default '[]'::jsonb,
    schema_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ltr_model_feature_schemas_unique unique (model_key, feature_schema_version)
);

create table ltr_model_artifacts (
    id uuid primary key,
    model_version_id uuid not null unique references ltr_model_versions(id) on delete cascade,
    weights_json jsonb not null default '{}'::jsonb,
    feature_names_json jsonb not null default '[]'::jsonb,
    normalization_json jsonb not null default '{}'::jsonb,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table ltr_model_state_transitions (
    id uuid primary key,
    model_version_id uuid not null references ltr_model_versions(id) on delete cascade,
    from_status text,
    to_status text not null,
    reason text,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index ltr_model_state_transitions_version_idx
    on ltr_model_state_transitions (model_version_id, created_at);

create table ltr_training_metrics (
    id uuid primary key,
    training_run_id uuid not null unique references ltr_training_runs(id) on delete cascade,
    training_example_count integer not null,
    validation_example_count integer not null,
    positive_count integer not null,
    negative_count integer not null,
    validation_precision_at_k numeric(16,6) not null default 0,
    validation_average_reward numeric(16,6) not null default 0,
    feature_coverage numeric(16,6) not null default 0,
    metrics_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ltr_training_metrics_counts_check check (
        training_example_count >= 0
        and validation_example_count >= 0
        and positive_count >= 0
        and negative_count >= 0
        and feature_coverage >= 0
        and feature_coverage <= 1
    )
);

create table ltr_training_examples_snapshot (
    id uuid primary key,
    training_run_id uuid not null references ltr_training_runs(id) on delete cascade,
    training_example_id uuid not null references training_examples(id) on delete cascade,
    split text not null,
    feature_values_json jsonb not null default '{}'::jsonb,
    label_value numeric(16,6) not null,
    score numeric(16,6),
    created_at timestamptz not null default now(),
    constraint ltr_training_examples_snapshot_split_check check (split in ('TRAIN', 'VALIDATION'))
);

create index ltr_training_examples_snapshot_run_split_idx
    on ltr_training_examples_snapshot (training_run_id, split);

create table model_calibration_runs (
    id uuid primary key,
    model_key text not null,
    version_key text not null,
    dataset_run_id uuid not null references training_dataset_runs(id) on delete cascade,
    status text not null,
    bucket_count integer not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint model_calibration_runs_bucket_check check (bucket_count > 0),
    constraint model_calibration_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create table model_calibration_buckets (
    id uuid primary key,
    run_id uuid not null references model_calibration_runs(id) on delete cascade,
    bucket_index integer not null,
    bucket_start numeric(16,6) not null,
    bucket_end numeric(16,6) not null,
    example_count integer not null,
    predicted_average numeric(16,6) not null,
    observed_reward_average numeric(16,6) not null,
    observed_positive_rate numeric(16,6) not null,
    calibration_error numeric(16,6) not null,
    confidence_status text not null,
    created_at timestamptz not null default now(),
    constraint model_calibration_buckets_status_check check (confidence_status in ('OVER_CONFIDENT', 'UNDER_CONFIDENT', 'CALIBRATED'))
);

create unique index model_calibration_buckets_run_bucket_idx
    on model_calibration_buckets (run_id, bucket_index);

create table model_drift_runs (
    id uuid primary key,
    baseline_dataset_run_id uuid not null references training_dataset_runs(id) on delete cascade,
    candidate_dataset_run_id uuid not null references training_dataset_runs(id) on delete cascade,
    baseline_model_version text,
    candidate_model_version text,
    segment_key text,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint model_drift_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create table model_drift_results (
    id uuid primary key,
    run_id uuid not null references model_drift_runs(id) on delete cascade,
    result_key text not null,
    metric_type text not null,
    psi_approx numeric(16,6),
    js_approx numeric(16,6),
    status text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint model_drift_results_status_check check (status in ('LOW', 'MODERATE', 'HIGH', 'NOT_COMPARABLE'))
);

create index model_drift_results_run_key_idx
    on model_drift_results (run_id, result_key);

create table propensity_logs (
    id uuid primary key,
    training_example_id uuid references training_examples(id) on delete set null,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    feed_snapshot_id uuid references feed_snapshots(id) on delete set null,
    feed_item_id uuid references feed_items(id) on delete set null,
    profile_id uuid references profiles(id) on delete set null,
    candidate_profile_id uuid references profiles(id) on delete set null,
    propensity numeric(16,6),
    propensity_source text not null,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint propensity_logs_propensity_check check (propensity is null or (propensity >= 0 and propensity <= 1)),
    constraint propensity_logs_source_check check (propensity_source in ('LOGGED', 'POSITION_APPROX', 'EXPERIMENT_APPROX', 'BANDIT_APPROX', 'INTERLEAVING_APPROX', 'UNKNOWN'))
);

create index propensity_logs_feed_item_idx
    on propensity_logs (feed_item_id, decision_log_id, candidate_profile_id);

create index propensity_logs_example_idx
    on propensity_logs (training_example_id);

create table causal_evaluation_runs (
    id uuid primary key,
    dataset_run_id uuid not null references training_dataset_runs(id) on delete cascade,
    k integer not null,
    use_ips_weights boolean not null,
    max_weight numeric(16,6) not null,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint causal_evaluation_runs_k_check check (k > 0),
    constraint causal_evaluation_runs_weight_check check (max_weight >= 1),
    constraint causal_evaluation_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create table causal_evaluation_results (
    id uuid primary key,
    run_id uuid not null unique references causal_evaluation_runs(id) on delete cascade,
    ips_precision_at_k numeric(16,6) not null default 0,
    ips_ndcg_at_k numeric(16,6) not null default 0,
    weighted_average_reward numeric(16,6) not null default 0,
    effective_sample_size numeric(16,6) not null default 0,
    propensity_coverage numeric(16,6) not null default 0,
    excluded_due_to_missing_propensity integer not null default 0,
    missing_propensity_warning boolean not null default false,
    high_variance_warning boolean not null default false,
    metrics_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table long_term_reward_runs (
    id uuid primary key,
    dataset_run_id uuid references training_dataset_runs(id) on delete set null,
    decision_log_id uuid references ranking_decision_logs(id) on delete set null,
    delayed_window_hours integer not null,
    include_neutral boolean not null,
    update_training_labels boolean not null,
    status text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint long_term_reward_runs_window_check check (delayed_window_hours > 0),
    constraint long_term_reward_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create table long_term_reward_labels (
    id uuid primary key,
    run_id uuid not null references long_term_reward_runs(id) on delete cascade,
    training_example_id uuid references training_examples(id) on delete set null,
    profile_id uuid not null references profiles(id) on delete cascade,
    candidate_profile_id uuid not null references profiles(id) on delete cascade,
    short_term_reward numeric(16,6) not null default 0,
    long_term_reward numeric(16,6) not null default 0,
    final_reward_value numeric(16,6) not null default 0,
    reward_components_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index long_term_reward_labels_pair_idx
    on long_term_reward_labels (profile_id, candidate_profile_id, created_at desc);

create table long_term_reward_results (
    id uuid primary key,
    run_id uuid not null unique references long_term_reward_runs(id) on delete cascade,
    example_count integer not null,
    labelled_count integer not null,
    average_short_term_reward numeric(16,6) not null default 0,
    average_long_term_reward numeric(16,6) not null default 0,
    average_final_reward numeric(16,6) not null default 0,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table model_rollout_gate_runs (
    id uuid primary key,
    candidate_model_key text not null,
    candidate_version_key text not null,
    baseline_model_key text,
    baseline_version_key text,
    status text not null,
    recommendation text not null,
    config_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint model_rollout_gate_runs_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    constraint model_rollout_gate_runs_recommendation_check check (recommendation in ('APPROVE', 'HOLD', 'REJECT'))
);

create index model_rollout_gate_runs_candidate_idx
    on model_rollout_gate_runs (candidate_model_key, candidate_version_key, created_at desc);

create table model_rollout_gate_checks (
    id uuid primary key,
    gate_run_id uuid not null references model_rollout_gate_runs(id) on delete cascade,
    check_key text not null,
    status text not null,
    required boolean not null,
    observed_value text,
    threshold_value text,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint model_rollout_gate_checks_status_check check (status in ('PASS', 'WARN', 'FAIL', 'NOT_AVAILABLE'))
);

create unique index model_rollout_gate_checks_run_key_idx
    on model_rollout_gate_checks (gate_run_id, check_key);

create table model_acceptance_reports (
    id uuid primary key,
    gate_run_id uuid not null unique references model_rollout_gate_runs(id) on delete cascade,
    model_key text not null,
    version_key text not null,
    recommendation text not null,
    report_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint model_acceptance_reports_recommendation_check check (recommendation in ('APPROVE', 'HOLD', 'REJECT'))
);
