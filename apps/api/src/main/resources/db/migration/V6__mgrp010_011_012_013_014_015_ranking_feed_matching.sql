create table feature_snapshot_runs (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    retrieval_run_id UUID NOT NULL REFERENCES candidate_retrieval_runs(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    stale_feature_count INTEGER NOT NULL DEFAULT 0,
    missing_required_feature_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT feature_snapshot_runs_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT feature_snapshot_runs_candidate_count_non_negative CHECK (candidate_count >= 0),
    CONSTRAINT feature_snapshot_runs_stale_feature_count_non_negative CHECK (stale_feature_count >= 0),
    CONSTRAINT feature_snapshot_runs_missing_feature_count_non_negative CHECK (missing_required_feature_count >= 0)
);

create index feature_snapshot_runs_profile_time_idx
    on feature_snapshot_runs (profile_id, created_at DESC);

create index feature_snapshot_runs_retrieval_idx
    on feature_snapshot_runs (retrieval_run_id);

create table candidate_feature_snapshots (
    id UUID PRIMARY KEY,
    snapshot_run_id UUID NOT NULL REFERENCES feature_snapshot_runs(id) ON DELETE CASCADE,
    candidate_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    retrieval_run_id UUID NOT NULL REFERENCES candidate_retrieval_runs(id) ON DELETE CASCADE,
    source_types_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    feature_freshness_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT candidate_feature_snapshots_freshness_check CHECK (feature_freshness_status IN ('FRESH', 'STALE', 'MISSING'))
);

create unique index candidate_feature_snapshots_run_candidate_idx
    on candidate_feature_snapshots (snapshot_run_id, candidate_profile_id);

create index candidate_feature_snapshots_retrieval_candidate_idx
    on candidate_feature_snapshots (retrieval_run_id, candidate_profile_id);

create table candidate_feature_values (
    snapshot_id UUID NOT NULL REFERENCES candidate_feature_snapshots(id) ON DELETE CASCADE,
    feature_key TEXT NOT NULL,
    numeric_value NUMERIC(16,6),
    text_value TEXT,
    json_value JSONB,
    freshness_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (snapshot_id, feature_key),
    CONSTRAINT candidate_feature_values_key_not_blank CHECK (length(trim(feature_key)) > 0),
    CONSTRAINT candidate_feature_values_freshness_check CHECK (freshness_status IN ('FRESH', 'STALE', 'MISSING')),
    CONSTRAINT candidate_feature_values_has_value_check CHECK (
        numeric_value IS NOT NULL OR text_value IS NOT NULL OR json_value IS NOT NULL OR freshness_status = 'MISSING'
    )
);

create index candidate_feature_values_feature_key_idx
    on candidate_feature_values (feature_key);

create table ranking_versions (
    version_key TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT false,
    policy_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ranking_versions_key_not_blank CHECK (length(trim(version_key)) > 0)
);

create unique index ranking_versions_single_active_idx
    on ranking_versions (active)
    where active;

insert into ranking_versions (version_key, description, active, policy_json)
values (
    'v1_balanced',
    'Balanced snapshot-only ranking policy for MGRP-011 through MGRP-014.',
    true,
    '{
        "signals": {
            "shared_interest_count": 1.25,
            "graph_closeness": 1.10,
            "mutual_count": 0.70,
            "common_neighbour_count": 0.45,
            "vector_similarity": 1.00,
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
);

create table ranking_decision_logs (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    retrieval_run_id UUID NOT NULL REFERENCES candidate_retrieval_runs(id) ON DELETE CASCADE,
    feature_snapshot_run_id UUID NOT NULL REFERENCES feature_snapshot_runs(id) ON DELETE CASCADE,
    ranking_version TEXT NOT NULL REFERENCES ranking_versions(version_key),
    decision_type TEXT NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    served_count INTEGER NOT NULL DEFAULT 0,
    candidate_pool_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ranking_decision_logs_decision_type_check CHECK (decision_type IN ('RANKING_RUN', 'FEED_REFRESH', 'REPLAY')),
    CONSTRAINT ranking_decision_logs_candidate_count_non_negative CHECK (candidate_count >= 0),
    CONSTRAINT ranking_decision_logs_served_count_non_negative CHECK (served_count >= 0)
);

create index ranking_decision_logs_profile_time_idx
    on ranking_decision_logs (profile_id, created_at DESC);

create index ranking_decision_logs_snapshot_idx
    on ranking_decision_logs (feature_snapshot_run_id);

create table ranking_decision_items (
    decision_log_id UUID NOT NULL REFERENCES ranking_decision_logs(id) ON DELETE CASCADE,
    candidate_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    feature_snapshot_id UUID NOT NULL REFERENCES candidate_feature_snapshots(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    base_score NUMERIC(16,6) NOT NULL,
    final_score NUMERIC(16,6) NOT NULL,
    reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    diversity_adjustments_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_types_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (decision_log_id, candidate_profile_id),
    CONSTRAINT ranking_decision_items_position_positive CHECK (position > 0)
);

create unique index ranking_decision_items_position_idx
    on ranking_decision_items (decision_log_id, position);

create index ranking_decision_items_feature_snapshot_idx
    on ranking_decision_items (feature_snapshot_id);

create table feed_snapshots (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    retrieval_run_id UUID NOT NULL REFERENCES candidate_retrieval_runs(id) ON DELETE CASCADE,
    feature_snapshot_run_id UUID NOT NULL REFERENCES feature_snapshot_runs(id) ON DELETE CASCADE,
    ranking_decision_log_id UUID NOT NULL REFERENCES ranking_decision_logs(id) ON DELETE CASCADE,
    ranking_version TEXT NOT NULL REFERENCES ranking_versions(version_key),
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT feed_snapshots_status_check CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'FAILED'))
);

create index feed_snapshots_profile_time_idx
    on feed_snapshots (profile_id, created_at DESC);

create index feed_snapshots_decision_log_idx
    on feed_snapshots (ranking_decision_log_id);

create table feed_items (
    id UUID PRIMARY KEY,
    feed_snapshot_id UUID NOT NULL REFERENCES feed_snapshots(id) ON DELETE CASCADE,
    retrieval_run_id UUID NOT NULL REFERENCES candidate_retrieval_runs(id) ON DELETE CASCADE,
    ranking_decision_log_id UUID NOT NULL REFERENCES ranking_decision_logs(id) ON DELETE CASCADE,
    candidate_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    score NUMERIC(16,6) NOT NULL,
    ranking_reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_types_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    feature_snapshot_id UUID NOT NULL REFERENCES candidate_feature_snapshots(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT feed_items_position_positive CHECK (position > 0)
);

create unique index feed_items_snapshot_profile_idx
    on feed_items (feed_snapshot_id, candidate_profile_id);

create unique index feed_items_snapshot_position_idx
    on feed_items (feed_snapshot_id, position);

create index feed_items_snapshot_cursor_idx
    on feed_items (feed_snapshot_id, position, id);

create index feed_items_traceability_idx
    on feed_items (retrieval_run_id, ranking_decision_log_id, feature_snapshot_id);

create table swipes (
    id UUID PRIMARY KEY,
    actor_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    target_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    direction TEXT NOT NULL,
    client_event_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT swipes_direction_check CHECK (direction IN ('LEFT', 'RIGHT')),
    CONSTRAINT swipes_no_self_swipe CHECK (actor_profile_id <> target_profile_id),
    CONSTRAINT swipes_client_event_not_blank CHECK (length(trim(client_event_id)) > 0)
);

create unique index swipes_actor_client_event_idx
    on swipes (actor_profile_id, client_event_id);

create index swipes_actor_target_idx
    on swipes (actor_profile_id, target_profile_id, created_at DESC);

create index swipes_target_actor_idx
    on swipes (target_profile_id, actor_profile_id, created_at DESC);

create table matches (
    id UUID PRIMARY KEY,
    profile_a_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    profile_b_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL,
    CONSTRAINT matches_status_check CHECK (status IN ('ACTIVE', 'BLOCKED', 'UNMATCHED')),
    CONSTRAINT matches_ordered_pair_check CHECK (profile_a_id < profile_b_id)
);

create unique index matches_unordered_pair_idx
    on matches (profile_a_id, profile_b_id);

create index matches_profile_a_lookup_idx
    on matches (profile_a_id, status, created_at DESC);

create index matches_profile_b_lookup_idx
    on matches (profile_b_id, status, created_at DESC);

alter table interaction_events
    drop constraint interaction_events_type_check;

alter table interaction_events
    add constraint interaction_events_type_check CHECK (
        event_type IN ('IMPRESSION', 'PROFILE_VIEW', 'SKIP', 'LIKE', 'PASS', 'BLOCK', 'REPORT', 'MATCH_CREATED')
    );
