-- Sprint 5: attachments schema (SPEC-BASE.md Section 31/17, Phase 5).
--
-- Same caveat as 002/003: no migration tool, files here only run on a
-- *fresh* MySQL data volume. If you already have a running
-- `veilkeeper-mysql` volume, `docker compose down -v` first.

CREATE TABLE IF NOT EXISTS attachments (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT UNSIGNED NOT NULL,
    vault_item_id       BIGINT UNSIGNED NOT NULL,

    -- Opaque AES-256-GCM ciphertext (nonce || ciphertext+tag) of the
    -- original filename, produced client-side with the same VDK used for
    -- vault_items.encrypted_payload. Filenames can leak metadata (e.g.
    -- "chase_bank_backup_codes.png"), so -- like every other piece of vault
    -- content -- the server never sees it in plaintext.
    encrypted_filename  VARBINARY(1024) NOT NULL,

    -- MIME type as reported by the client. Non-sensitive metadata only
    -- (SPEC-BASE.md Section 17 explicitly lists it); NOT trusted for any
    -- security decision, since the bytes on disk are already encrypted and
    -- opaque to the server regardless of what this field says.
    mime_type           VARCHAR(255) NOT NULL,

    -- Size in bytes of the encrypted blob actually written to disk.
    size                BIGINT UNSIGNED NOT NULL,

    -- Path *relative* to the server's attachments root (see
    -- internal/config.Config.AttachmentsDir / ATTACHMENTS_DIR env var),
    -- e.g. "42/3f9a1c2b....bin". Always server-generated
    -- (internal/httpserver/attachment_handlers.go) from a random ID plus
    -- the owning user_id -- NEVER derived from the client-supplied
    -- (encrypted, opaque) filename, so there is no path-traversal surface
    -- from client input.
    storage_path        VARCHAR(512) NOT NULL,

    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_attachments_user_id (user_id),
    KEY idx_attachments_vault_item_id (vault_item_id),
    CONSTRAINT fk_attachments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- CASCADE (unlike vault_items->categories' RESTRICT): a vault item's
    -- attachments have no independent meaning once the item is gone, and
    -- application code (httpserver.handleDeleteVaultItem) always deletes
    -- the on-disk encrypted files *before* deleting the item row, so there
    -- is nothing left to reassign or protect here.
    CONSTRAINT fk_attachments_vault_item FOREIGN KEY (vault_item_id) REFERENCES vault_items (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
