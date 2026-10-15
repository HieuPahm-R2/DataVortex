CREATE TABLE tenants (
  id CHAR(36) PRIMARY KEY, name VARCHAR(255) NOT NULL, status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
CREATE TABLE users (
  id CHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
CREATE TABLE memberships (
  tenant_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, role VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, user_id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (user_id) REFERENCES users(id),
  CHECK (role IN ('TENANT_ADMIN', 'ANALYST', 'VIEWER'))
);
CREATE TABLE ad_accounts (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL,
  platform VARCHAR(20) NOT NULL, source_account_id VARCHAR(255) NOT NULL,
  currency CHAR(3) NOT NULL, timezone VARCHAR(64) NOT NULL,
  credential_ref VARCHAR(512), status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, platform, source_account_id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id),
  CHECK (platform IN ('MOCK_REST', 'MOCK_SOAP', 'GOOGLE_ADS'))
);
CREATE TABLE campaigns (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, account_id CHAR(36) NOT NULL,
  source_campaign_id VARCHAR(255) NOT NULL, name VARCHAR(512) NOT NULL,
  source_status VARCHAR(20) NOT NULL, local_note TEXT, local_tags JSON NOT NULL,
  start_date DATE, end_date DATE, search_version BIGINT NOT NULL DEFAULT 1,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE, deleted_at TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, account_id, source_campaign_id),
  INDEX campaign_scan (tenant_id, updated_at, id),
  FOREIGN KEY (tenant_id, account_id) REFERENCES ad_accounts(tenant_id, id),
  CHECK (search_version >= 1), CHECK (end_date IS NULL OR start_date IS NULL OR start_date <= end_date)
);
CREATE TABLE ad_groups (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, campaign_id CHAR(36) NOT NULL,
  source_ad_group_id VARCHAR(255) NOT NULL, name VARCHAR(512) NOT NULL, status VARCHAR(20) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE, deleted_at TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id, id, campaign_id), UNIQUE (tenant_id, campaign_id, source_ad_group_id),
  FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaigns(tenant_id, id)
);
CREATE TABLE keywords (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, ad_group_id CHAR(36) NOT NULL,
  campaign_id CHAR(36) NOT NULL, source_keyword_id VARCHAR(255) NOT NULL,
  text VARCHAR(1024) NOT NULL, match_type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL,
  bid_micros BIGINT NOT NULL, search_version BIGINT NOT NULL DEFAULT 1,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE, deleted_at TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, ad_group_id, source_keyword_id),
  INDEX keyword_scan (tenant_id, campaign_id, id),
  FOREIGN KEY (tenant_id, ad_group_id, campaign_id) REFERENCES ad_groups(tenant_id, id, campaign_id),
  CHECK (bid_micros >= 0), CHECK (search_version >= 1),
  CHECK (match_type IN ('EXACT', 'PHRASE', 'BROAD'))
);
CREATE TABLE outbox_events (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL, aggregate_id CHAR(36) NOT NULL,
  aggregate_version BIGINT NOT NULL, event_type VARCHAR(32) NOT NULL,
  schema_version INT NOT NULL, payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempt INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  lease_until TIMESTAMP(6), lease_owner VARCHAR(100), last_error VARCHAR(1024),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  INDEX outbox_poll (status, next_retry_at, id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id),
  CHECK (aggregate_version >= 1), CHECK (attempt >= 0)
);
CREATE TABLE ingestion_jobs (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, account_id CHAR(36) NOT NULL,
  report_kind VARCHAR(20) NOT NULL, date_from DATE NOT NULL, date_to DATE NOT NULL,
  logical_request_id VARCHAR(100) NOT NULL, request_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', stage VARCHAR(20) NOT NULL DEFAULT 'FETCH',
  attempt INT NOT NULL DEFAULT 0, run_sequence BIGINT NOT NULL,
  raw_manifest_uri VARCHAR(1024), bq_job_ids JSON, transform_id VARCHAR(255),
  lease_until TIMESTAMP(6), lease_owner VARCHAR(100), next_retry_at TIMESTAMP(6),
  started_at TIMESTAMP(6), finished_at TIMESTAMP(6),
  rows_received BIGINT NOT NULL DEFAULT 0, rows_loaded BIGINT NOT NULL DEFAULT 0,
  rows_rejected BIGINT NOT NULL DEFAULT 0, rejected_uri VARCHAR(1024), error_code VARCHAR(100),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id, logical_request_id), UNIQUE (tenant_id, account_id, report_kind, run_sequence),
  INDEX ingestion_poll (status, next_retry_at),
  FOREIGN KEY (tenant_id, account_id) REFERENCES ad_accounts(tenant_id, id),
  CHECK (date_from <= date_to), CHECK (run_sequence >= 1), CHECK (attempt >= 0),
  CHECK (report_kind IN ('CAMPAIGN', 'KEYWORD'))
);
CREATE TABLE ingestion_checkpoints (
  tenant_id CHAR(36) NOT NULL, account_id CHAR(36) NOT NULL, report_kind VARCHAR(20) NOT NULL,
  last_success_date DATE NOT NULL, last_success_run_id CHAR(36) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, account_id, report_kind),
  FOREIGN KEY (tenant_id, account_id) REFERENCES ad_accounts(tenant_id, id)
);
CREATE TABLE publication_watermarks (
  tenant_id CHAR(36) NOT NULL, report_kind VARCHAR(20) NOT NULL,
  published_run_id CHAR(36) NOT NULL, data_as_of TIMESTAMP(6) NOT NULL,
  date_from DATE NOT NULL, date_to DATE NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, report_kind), FOREIGN KEY (tenant_id) REFERENCES tenants(id),
  CHECK (date_from <= date_to)
);
CREATE TABLE audit_logs (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36), actor_id CHAR(36) NOT NULL,
  action VARCHAR(100) NOT NULL, entity_type VARCHAR(32) NOT NULL, entity_id CHAR(36),
  before_data JSON, after_data JSON, correlation_id CHAR(36) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  INDEX audit_scan (tenant_id, created_at, id), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);
