alter table feed_items
    add column diversity_adjustments_json jsonb not null default '[]'::jsonb;

