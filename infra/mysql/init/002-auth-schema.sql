-- Sprint 1: authentication schema (SPEC-BASE.md Section 31, refined per
-- CLAUDE.md Resolved Design Decision #1 -- password-derived key with a
-- wrapped Data Key).
--
-- NOTE: this repo has no migration tool (per SPEC-BASE.md Section 56 Rule 7,
-- "no unnecessary dependencies" -- a single-developer homelab project of
-- this size doesn't need one yet). Files under infra/mysql/init/ only run
-- once, on a *fresh* MySQL data volume (docker-entrypoint-initdb.d
-- semantics). If you already have a running `veilkeeper-mysql` volume from
-- Sprint 0, you must recreate it (`docker compose down -v`) for this file to
-- take effect.

CREATE TABLE IF NOT EXISTS users (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    username        VARCHAR(255) NULL,

    -- Server never sees the raw password or MasterKey. auth_key_hash is an
    -- Argon2id hash (internal/auth.HashAuthKey) of the client-derived
    -- AuthKey = HKDF(MasterKey, "veilkeeper:auth:v1"). Never plaintext.
    auth_key_hash   VARCHAR(255) NOT NULL,

    -- Client-side Argon2id password KDF bookkeeping (not secret -- a salt
    -- and public parameters). kdf_version lets params be upgraded for new
    -- accounts without breaking existing ones.
    kdf_salt        VARBINARY(32) NOT NULL,
    kdf_params      JSON NOT NULL,
    kdf_version     INT UNSIGNED NOT NULL DEFAULT 1,

    -- Opaque AES-256-GCM ciphertext of the VaultDataKey, wrapped client-side
    -- with WrapKey = HKDF(MasterKey, "veilkeeper:wrap:v1"). The server
    -- never decrypts this.
    wrapped_vdk     VARBINARY(512) NOT NULL,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS devices (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT UNSIGNED NOT NULL,
    device_identifier   VARCHAR(255) NOT NULL,
    device_name         VARCHAR(255) NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at          TIMESTAMP NULL DEFAULT NULL,

    UNIQUE KEY uq_devices_user_identifier (user_id, device_identifier),
    KEY idx_devices_user_id (user_id),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sessions (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    device_id       BIGINT UNSIGNED NOT NULL,

    -- SHA-256 hex digest of the opaque bearer token handed to the client.
    -- The raw token is never stored.
    token_hash      CHAR(64) NOT NULL,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP NOT NULL,
    revoked_at      TIMESTAMP NULL DEFAULT NULL,

    UNIQUE KEY uq_sessions_token_hash (token_hash),
    KEY idx_sessions_user_id (user_id),
    KEY idx_sessions_expires_at (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_sessions_device FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
