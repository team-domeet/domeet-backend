CREATE TABLE signup_identity_verification_attempts (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES signup_progress (user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    birth_date_available BOOLEAN,
    ci_available BOOLEAN,
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '15 minutes'),
    CHECK (
        (confirmed_at IS NULL AND birth_date_available IS NULL AND ci_available IS NULL)
        OR (confirmed_at >= created_at AND confirmed_at < expires_at
            AND confirmed_at IS NOT NULL AND birth_date_available IS NOT NULL AND ci_available IS NOT NULL)
    )
);

CREATE INDEX signup_identity_verification_attempts_user_id_idx
    ON signup_identity_verification_attempts (user_id);
