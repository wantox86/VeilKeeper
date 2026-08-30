-- Sprint 2: vault foundation schema (SPEC-BASE.md Section 31, categories +
-- vault_items), plus CLAUDE.md Resolved Design Decision (Sprint 2) on safe
-- category deletion.
--
-- Same caveat as 002-auth-schema.sql: no migration tool, files here only run
-- on a *fresh* MySQL data volume. If you already have a running
-- `veilkeeper-mysql` volume, `docker compose down -v` first.

CREATE TABLE IF NOT EXISTS categories (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT UNSIGNED NOT NULL,
    name                VARCHAR(100) NOT NULL,

    -- True only for the single lazily-created "Uncategorized" safety-net
    -- category per user (see CLAUDE.md Sprint 2 "Delete category behavior").
    -- It cannot be deleted or renamed away from its purpose; enforced in
    -- application code (internal/httpserver/category_handlers.go), not by a
    -- DB constraint, since MySQL has no portable partial-unique-index here.
    is_uncategorized    BOOLEAN NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    KEY idx_categories_user_id (user_id),
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vault_items (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT UNSIGNED NOT NULL,
    category_id         BIGINT UNSIGNED NOT NULL,

    -- Opaque AES-256-GCM ciphertext (nonce || ciphertext+tag, produced
    -- client-side by VaultCrypto/AesGcm from the *unwrapped* VDK) of the
    -- item's { title, content[] } JSON payload (SPEC-BASE.md Section 13).
    -- The server never sees plaintext title/content -- see CLAUDE.md
    -- "Resolved Design Decisions" #1 and SPEC-BASE.md Section 32.
    encrypted_payload   MEDIUMBLOB NOT NULL,

    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    KEY idx_vault_items_user_id (user_id),
    KEY idx_vault_items_category_id (category_id),
    KEY idx_vault_items_user_updated (user_id, updated_at),
    CONSTRAINT fk_vault_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT (not CASCADE/SET NULL): application code always reassigns a
    -- category's items to another category (or the user's Uncategorized
    -- category) *before* deleting it, inside a transaction
    -- (store.DeleteCategoryAndReassign). RESTRICT is a defense-in-depth
    -- belt-and-suspenders check against ever silently orphaning/deleting
    -- items via a code path that forgets to reassign first.
    CONSTRAINT fk_vault_items_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
