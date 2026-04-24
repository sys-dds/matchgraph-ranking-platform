ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS bio TEXT,
    ADD COLUMN IF NOT EXISTS city TEXT,
    ADD COLUMN IF NOT EXISTS region TEXT,
    ADD COLUMN IF NOT EXISTS country TEXT,
    ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS profile_completeness_score NUMERIC(5,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS embedding_status TEXT NOT NULL DEFAULT 'NEVER_EMBEDDED';

ALTER TABLE profiles
    ADD CONSTRAINT profiles_completeness_score_range CHECK (
        profile_completeness_score >= 0
        AND profile_completeness_score <= 1
    ),
    ADD CONSTRAINT profiles_embedding_status_check CHECK (
        embedding_status IN ('NEVER_EMBEDDED', 'STALE', 'CURRENT', 'FAILED')
    );

CREATE INDEX IF NOT EXISTS profiles_city_region_country_idx ON profiles (city, region, country);
CREATE INDEX IF NOT EXISTS profiles_last_active_at_idx ON profiles (last_active_at DESC);
CREATE INDEX IF NOT EXISTS profiles_embedding_status_idx ON profiles (embedding_status);

CREATE TABLE IF NOT EXISTS profile_interests (
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    interest_key TEXT NOT NULL,
    interest_value TEXT NOT NULL,
    weight NUMERIC(8,4) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, interest_key, interest_value),
    CONSTRAINT profile_interests_key_not_blank CHECK (length(trim(interest_key)) > 0),
    CONSTRAINT profile_interests_value_not_blank CHECK (length(trim(interest_value)) > 0),
    CONSTRAINT profile_interests_weight_non_negative CHECK (weight >= 0)
);

CREATE INDEX IF NOT EXISTS profile_interests_key_value_idx ON profile_interests (interest_key, interest_value);
CREATE INDEX IF NOT EXISTS profile_interests_profile_updated_idx ON profile_interests (profile_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS profile_locations (
    profile_id UUID PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    approximate_point GEOGRAPHY(Point, 4326) NOT NULL,
    precision_km NUMERIC(8,3) NOT NULL DEFAULT 25.0,
    city TEXT,
    region TEXT,
    country TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_locations_precision_positive CHECK (precision_km > 0)
);

CREATE INDEX IF NOT EXISTS profile_locations_approximate_point_idx
    ON profile_locations USING GIST (approximate_point);
CREATE INDEX IF NOT EXISTS profile_locations_country_region_city_idx
    ON profile_locations (country, region, city);

CREATE TABLE IF NOT EXISTS profile_safety_states (
    profile_id UUID PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    safety_state TEXT NOT NULL DEFAULT 'UNREVIEWED',
    reason TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_safety_states_state_check CHECK (
        safety_state IN ('UNREVIEWED', 'APPROVED', 'LIMITED', 'BLOCKED')
    )
);

CREATE INDEX IF NOT EXISTS profile_safety_states_state_idx ON profile_safety_states (safety_state);

CREATE TABLE IF NOT EXISTS profile_safety_events (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    safety_state TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_safety_events_state_check CHECK (
        safety_state IN ('UNREVIEWED', 'APPROVED', 'LIMITED', 'BLOCKED')
    )
);

CREATE INDEX IF NOT EXISTS profile_safety_events_profile_created_idx
    ON profile_safety_events (profile_id, created_at DESC);

CREATE TABLE IF NOT EXISTS profile_embedding_versions (
    id UUID PRIMARY KEY,
    version_name TEXT NOT NULL UNIQUE,
    model_name TEXT NOT NULL,
    dimensions INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_embedding_versions_name_not_blank CHECK (length(trim(version_name)) > 0),
    CONSTRAINT profile_embedding_versions_model_not_blank CHECK (length(trim(model_name)) > 0),
    CONSTRAINT profile_embedding_versions_dimensions_check CHECK (dimensions = 384)
);

CREATE TABLE IF NOT EXISTS profile_embeddings (
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    embedding_version_id UUID NOT NULL REFERENCES profile_embedding_versions(id),
    embedding VECTOR(384) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, embedding_version_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS profile_embeddings_one_active_idx
    ON profile_embeddings (profile_id)
    WHERE is_active;
CREATE INDEX IF NOT EXISTS profile_embeddings_active_version_idx
    ON profile_embeddings (embedding_version_id)
    WHERE is_active;
