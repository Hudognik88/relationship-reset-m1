CREATE TABLE IF NOT EXISTS rr_client_orders (
    id CHAR(38) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_session_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paid_provider_order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    paid_at DATETIME(6) NULL,
    UNIQUE KEY rr_client_orders_request (owner_session_id, client_request_id),
    UNIQUE KEY rr_client_orders_case_product (owner_session_id, case_id, product),
    UNIQUE KEY rr_client_orders_provider (paid_provider_order_id),
    CONSTRAINT rr_client_orders_owner_fk FOREIGN KEY (owner_session_id) REFERENCES rr_client_sessions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rr_client_order_requests (
    owner_session_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(38) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_session_id, client_request_id),
    CONSTRAINT rr_client_order_requests_owner_fk FOREIGN KEY (owner_session_id) REFERENCES rr_client_sessions (id) ON DELETE RESTRICT,
    CONSTRAINT rr_client_order_requests_order_fk FOREIGN KEY (order_id) REFERENCES rr_client_orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rr_payment_receipts (
    provider_order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    order_id CHAR(38) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_domain VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    demo_mode BOOLEAN NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    first_received_at DATETIME(6) NOT NULL,
    last_received_at DATETIME(6) NOT NULL,
    CONSTRAINT rr_payment_receipts_order_fk FOREIGN KEY (order_id) REFERENCES rr_client_orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rr_client_entitlements (
    order_id CHAR(38) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_session_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT rr_client_entitlements_order_fk FOREIGN KEY (order_id) REFERENCES rr_client_orders (id) ON DELETE CASCADE,
    CONSTRAINT rr_client_entitlements_owner_fk FOREIGN KEY (owner_session_id) REFERENCES rr_client_sessions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
