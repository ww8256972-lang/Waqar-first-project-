-- Separate Zero-Trust security database schema (security_db)
-- Existing Firestore/Institute databases remain completely untouched.

CREATE TABLE IF NOT EXISTS devices (
    id              UUID PRIMARY KEY,
    user_id         TEXT NOT NULL,
    pubkey_jkt      TEXT NOT NULL UNIQUE,   -- RFC 7638 JWK thumbprint (DPoP binding)
    pubkey_jwk      JSONB NOT NULL,
    attestation_lvl TEXT NOT NULL,          -- SOFTWARE | TRUSTED_ENVIRONMENT | STRONGBOX
    os_patch_level  TEXT,
    enrolled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen       TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ             -- Lost/compromised device revocation timestamp
);

CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices (user_id);

CREATE TABLE IF NOT EXISTS audit_log (
    id        BIGSERIAL PRIMARY KEY,
    ts        TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id   TEXT,
    device_id UUID,
    action    TEXT NOT NULL,
    object    TEXT,
    decision  TEXT NOT NULL,                -- ALLOW | DENY | WOULD_DENY
    tier      TEXT,
    ip        INET,
    detail    JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_log_ts ON audit_log (ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_user_decision ON audit_log (user_id, decision);

CREATE TABLE IF NOT EXISTS face_templates (
    user_id    TEXT PRIMARY KEY,
    kid        TEXT NOT NULL,
    sealed     JSONB NOT NULL,              -- Layer 3/4 sealed ML-KEM-768 + X25519 envelope
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS replay_nonces (
    nonce   TEXT PRIMARY KEY,
    seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_replay_nonces_seen_at ON replay_nonces (seen_at);
-- Scheduled cleanup job: DELETE FROM replay_nonces WHERE seen_at < now() - interval '10 minutes';
