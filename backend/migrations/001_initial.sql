CREATE TABLE IF NOT EXISTS rr_cases (
    id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    synthetic TINYINT(1) NOT NULL DEFAULT 1,
    questionnaire LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY rr_cases_request_unique (client_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rr_drafts (
    id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    case_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_text LONGTEXT NOT NULL,
    reviewed TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY rr_drafts_request_unique (client_request_id),
    CONSTRAINT rr_drafts_case_fk FOREIGN KEY (case_id) REFERENCES rr_cases (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
