CREATE TABLE IF NOT EXISTS profiles (
    id UUID PRIMARY KEY,
    external_ref TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    profile_type TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profiles_display_name_not_blank CHECK (length(trim(display_name)) > 0),
    CONSTRAINT profiles_profile_type_check CHECK (profile_type IN ('USER', 'CREATOR', 'BUSINESS')),
    CONSTRAINT profiles_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS profiles_profile_type_idx ON profiles (profile_type);
CREATE INDEX IF NOT EXISTS profiles_status_idx ON profiles (status);
CREATE INDEX IF NOT EXISTS profiles_profile_type_status_idx ON profiles (profile_type, status);

CREATE TABLE IF NOT EXISTS rankable_items (
    id UUID PRIMARY KEY,
    external_ref TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    item_type TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rankable_items_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT rankable_items_item_type_check CHECK (item_type IN ('POST', 'PROFILE', 'PRODUCT', 'JOB')),
    CONSTRAINT rankable_items_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS rankable_items_item_type_idx ON rankable_items (item_type);
CREATE INDEX IF NOT EXISTS rankable_items_status_idx ON rankable_items (status);
CREATE INDEX IF NOT EXISTS rankable_items_item_type_status_idx ON rankable_items (item_type, status);

CREATE TABLE IF NOT EXISTS item_features (
    item_id UUID NOT NULL REFERENCES rankable_items(id) ON DELETE CASCADE,
    feature_key TEXT NOT NULL,
    feature_value TEXT NOT NULL,
    weight NUMERIC(8,4) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (item_id, feature_key, feature_value),
    CONSTRAINT item_features_key_not_blank CHECK (length(trim(feature_key)) > 0),
    CONSTRAINT item_features_value_not_blank CHECK (length(trim(feature_value)) > 0),
    CONSTRAINT item_features_weight_non_negative CHECK (weight >= 0)
);

CREATE INDEX IF NOT EXISTS item_features_feature_key_idx ON item_features (feature_key);
CREATE INDEX IF NOT EXISTS item_features_feature_key_value_idx ON item_features (feature_key, feature_value);

CREATE TABLE IF NOT EXISTS profile_features (
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    feature_key TEXT NOT NULL,
    feature_value TEXT NOT NULL,
    weight NUMERIC(8,4) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, feature_key, feature_value),
    CONSTRAINT profile_features_key_not_blank CHECK (length(trim(feature_key)) > 0),
    CONSTRAINT profile_features_value_not_blank CHECK (length(trim(feature_value)) > 0),
    CONSTRAINT profile_features_weight_non_negative CHECK (weight >= 0)
);

CREATE INDEX IF NOT EXISTS profile_features_feature_key_idx ON profile_features (feature_key);
CREATE INDEX IF NOT EXISTS profile_features_feature_key_value_idx ON profile_features (feature_key, feature_value);

CREATE TABLE IF NOT EXISTS interactions (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES rankable_items(id) ON DELETE CASCADE,
    interaction_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT interactions_interaction_type_check CHECK (interaction_type IN ('VIEW', 'LIKE', 'DISLIKE', 'SAVE', 'CLICK', 'HIDE'))
);

CREATE INDEX IF NOT EXISTS interactions_profile_occurred_at_idx ON interactions (profile_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS interactions_item_occurred_at_idx ON interactions (item_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS interactions_profile_item_idx ON interactions (profile_id, item_id);
CREATE INDEX IF NOT EXISTS interactions_interaction_type_idx ON interactions (interaction_type);

CREATE TABLE IF NOT EXISTS graph_edges (
    source_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    target_item_id UUID NOT NULL REFERENCES rankable_items(id) ON DELETE CASCADE,
    edge_type TEXT NOT NULL,
    strength NUMERIC(8,4) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_profile_id, target_item_id, edge_type),
    CONSTRAINT graph_edges_edge_type_check CHECK (edge_type IN ('VIEWED', 'LIKED', 'DISLIKED', 'SAVED', 'CLICKED', 'HIDDEN')),
    CONSTRAINT graph_edges_strength_non_negative CHECK (strength >= 0)
);

CREATE INDEX IF NOT EXISTS graph_edges_source_profile_idx ON graph_edges (source_profile_id);
CREATE INDEX IF NOT EXISTS graph_edges_target_item_idx ON graph_edges (target_item_id);
CREATE INDEX IF NOT EXISTS graph_edges_edge_type_idx ON graph_edges (edge_type);
CREATE INDEX IF NOT EXISTS graph_edges_source_strength_idx ON graph_edges (source_profile_id, strength DESC);
