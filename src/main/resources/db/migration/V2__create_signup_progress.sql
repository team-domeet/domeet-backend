CREATE TABLE signup_progress (
    user_id BIGINT PRIMARY KEY REFERENCES users (id),
    step VARCHAR(30) NOT NULL,
    draft_data JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
