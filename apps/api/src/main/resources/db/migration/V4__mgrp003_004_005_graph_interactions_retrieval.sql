create table profile_graph_edges (
    id UUID PRIMARY KEY,
    source_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    target_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    edge_type TEXT NOT NULL,
    status TEXT NOT NULL,
    strength NUMERIC(8,4) NOT NULL DEFAULT 1.0,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_graph_edges_no_self_edge CHECK (source_profile_id <> target_profile_id),
    CONSTRAINT profile_graph_edges_type_check CHECK (edge_type IN ('FOLLOW', 'BLOCK', 'MUTE', 'REPORT')),
    CONSTRAINT profile_graph_edges_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT profile_graph_edges_strength_non_negative CHECK (strength >= 0)
);

create unique index profile_graph_edges_active_unique_idx
    on profile_graph_edges (source_profile_id, target_profile_id, edge_type)
    where status = 'ACTIVE';

create index profile_graph_edges_source_idx
    on profile_graph_edges (source_profile_id, edge_type, status);

create index profile_graph_edges_target_idx
    on profile_graph_edges (target_profile_id, edge_type, status);

create index profile_graph_edges_type_status_idx
    on profile_graph_edges (edge_type, status);

create table profile_graph_edge_events (
    id UUID PRIMARY KEY,
    source_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    target_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    edge_type TEXT NOT NULL,
    action TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_graph_edge_events_no_self_edge CHECK (source_profile_id <> target_profile_id),
    CONSTRAINT profile_graph_edge_events_type_check CHECK (edge_type IN ('FOLLOW', 'BLOCK', 'MUTE', 'REPORT')),
    CONSTRAINT profile_graph_edge_events_action_check CHECK (action IN ('CREATED', 'DEACTIVATED', 'UPDATED'))
);

create index profile_graph_edge_events_source_idx
    on profile_graph_edge_events (source_profile_id, created_at DESC);

create index profile_graph_edge_events_target_idx
    on profile_graph_edge_events (target_profile_id, created_at DESC);

create table candidate_retrieval_runs (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    requested_limit INTEGER NOT NULL,
    final_candidate_count INTEGER NOT NULL DEFAULT 0,
    exclusion_count INTEGER NOT NULL DEFAULT 0,
    source_coverage_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT candidate_retrieval_runs_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT candidate_retrieval_runs_requested_limit_positive CHECK (requested_limit > 0),
    CONSTRAINT candidate_retrieval_runs_final_candidate_count_non_negative CHECK (final_candidate_count >= 0),
    CONSTRAINT candidate_retrieval_runs_exclusion_count_non_negative CHECK (exclusion_count >= 0)
);

create index candidate_retrieval_runs_profile_time_idx
    on candidate_retrieval_runs (profile_id, created_at DESC);

create table candidate_retrieval_items (
    retrieval_run_id UUID NOT NULL REFERENCES candidate_retrieval_runs(id) ON DELETE CASCADE,
    candidate_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    source_type TEXT NOT NULL,
    source_rank INTEGER NOT NULL,
    excluded BOOLEAN NOT NULL DEFAULT false,
    exclusion_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (retrieval_run_id, candidate_profile_id, source_type),
    CONSTRAINT candidate_retrieval_items_source_type_check CHECK (source_type IN ('RECENTLY_ACTIVE', 'SHARED_INTEREST', 'COLD_START')),
    CONSTRAINT candidate_retrieval_items_source_rank_positive CHECK (source_rank > 0),
    CONSTRAINT candidate_retrieval_items_exclusion_reason_check CHECK (
        exclusion_reason IS NULL OR exclusion_reason IN (
            'SELF',
            'INACTIVE_PROFILE',
            'BLOCKED_EITHER_DIRECTION',
            'SUPPRESSED_PROFILE',
            'ALREADY_REPORTED'
        )
    )
);

create index candidate_retrieval_items_run_source_idx
    on candidate_retrieval_items (retrieval_run_id, source_type, source_rank);

create index candidate_retrieval_items_candidate_idx
    on candidate_retrieval_items (candidate_profile_id);

create table interaction_events (
    id UUID PRIMARY KEY,
    client_event_id TEXT NOT NULL,
    actor_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    target_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    request_id TEXT,
    retrieval_run_id UUID REFERENCES candidate_retrieval_runs(id) ON DELETE SET NULL,
    candidate_source TEXT,
    ranking_version TEXT,
    experiment_id TEXT,
    variant TEXT,
    feed_position INTEGER,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT interaction_events_no_self_event CHECK (actor_profile_id <> target_profile_id),
    CONSTRAINT interaction_events_type_check CHECK (event_type IN ('IMPRESSION', 'PROFILE_VIEW', 'SKIP', 'LIKE', 'PASS', 'BLOCK', 'REPORT')),
    CONSTRAINT interaction_events_candidate_source_check CHECK (
        candidate_source IS NULL OR candidate_source IN ('RECENTLY_ACTIVE', 'SHARED_INTEREST', 'COLD_START')
    ),
    CONSTRAINT interaction_events_feed_position_positive CHECK (feed_position IS NULL OR feed_position > 0)
);

create unique index interaction_events_actor_client_event_idx
    on interaction_events (actor_profile_id, client_event_id);

create index interaction_events_actor_time_idx
    on interaction_events (actor_profile_id, occurred_at DESC);

create index interaction_events_target_time_idx
    on interaction_events (target_profile_id, occurred_at DESC);

create index interaction_events_retrieval_run_idx
    on interaction_events (retrieval_run_id);
