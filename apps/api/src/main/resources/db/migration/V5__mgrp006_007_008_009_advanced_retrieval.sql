alter table candidate_retrieval_items
    drop constraint if exists candidate_retrieval_items_source_type_check;

alter table candidate_retrieval_items
    add constraint candidate_retrieval_items_source_type_check check (
        source_type in (
            'RECENTLY_ACTIVE',
            'SHARED_INTEREST',
            'COLD_START',
            'GRAPH_TWO_HOP',
            'GRAPH_MUTUALS',
            'WEAK_TIE_EXPLORATION',
            'VECTOR_SIMILARITY',
            'LOCATION_NEARBY'
        )
    );

alter table interaction_events
    drop constraint if exists interaction_events_candidate_source_check;

alter table interaction_events
    add constraint interaction_events_candidate_source_check check (
        candidate_source is null or candidate_source in (
            'RECENTLY_ACTIVE',
            'SHARED_INTEREST',
            'COLD_START',
            'GRAPH_TWO_HOP',
            'GRAPH_MUTUALS',
            'WEAK_TIE_EXPLORATION',
            'VECTOR_SIMILARITY',
            'LOCATION_NEARBY'
        )
    );

alter table candidate_retrieval_items
    add column source_score NUMERIC(12,6),
    add column source_reason_json JSONB NOT NULL DEFAULT '{}'::jsonb;

alter table candidate_retrieval_runs
    add column raw_candidate_count INTEGER NOT NULL DEFAULT 0,
    add column deduped_candidate_count INTEGER NOT NULL DEFAULT 0,
    add column exclusion_counts_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    add column source_budgets_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    add column retrieval_quality_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    add constraint candidate_retrieval_runs_raw_candidate_count_non_negative check (raw_candidate_count >= 0),
    add constraint candidate_retrieval_runs_deduped_candidate_count_non_negative check (deduped_candidate_count >= 0);

create index if not exists profile_embeddings_embedding_hnsw_idx
    on profile_embeddings using hnsw (embedding vector_cosine_ops);

create index if not exists profile_locations_approximate_point_gix
    on profile_locations using gist (approximate_point);

create index if not exists profile_graph_edges_follow_active_source_target_idx
    on profile_graph_edges (source_profile_id, target_profile_id)
    where edge_type = 'FOLLOW' and status = 'ACTIVE';

create index if not exists profile_graph_edges_follow_active_target_source_idx
    on profile_graph_edges (target_profile_id, source_profile_id)
    where edge_type = 'FOLLOW' and status = 'ACTIVE';

create index if not exists profile_graph_edges_safety_active_source_target_idx
    on profile_graph_edges (source_profile_id, target_profile_id, edge_type)
    where edge_type in ('BLOCK', 'MUTE', 'REPORT') and status = 'ACTIVE';
