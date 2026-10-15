ALTER TABLE ingestion_jobs
  MODIFY logical_request_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  ADD COLUMN lease_token CHAR(36),
  ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
  ADD INDEX fetch_claim (stage, status, lease_until, next_retry_at, created_at, id),
  ADD CHECK (lease_generation >= 0);

CREATE TABLE ingestion_run_sequences (
  tenant_id CHAR(36) NOT NULL,
  account_id CHAR(36) NOT NULL,
  report_kind VARCHAR(20) NOT NULL,
  next_sequence BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, account_id, report_kind),
  FOREIGN KEY (tenant_id, account_id) REFERENCES ad_accounts(tenant_id, id),
  CHECK (next_sequence >= 1),
  CHECK (report_kind IN ('CAMPAIGN', 'KEYWORD'))
);

-- Preserve monotonic ordering when upgrading a repository with existing jobs.
INSERT INTO ingestion_run_sequences(tenant_id, account_id, report_kind, next_sequence)
SELECT tenant_id, account_id, report_kind, MAX(run_sequence) + 1
FROM ingestion_jobs GROUP BY tenant_id, account_id, report_kind;
