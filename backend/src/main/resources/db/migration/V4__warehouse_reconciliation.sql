ALTER TABLE ingestion_jobs ADD UNIQUE KEY ingestion_tenant_identity(tenant_id,id);

CREATE TABLE warehouse_execution (
  job_id CHAR(36) PRIMARY KEY,
  tenant_id CHAR(36) NOT NULL,
  step VARCHAR(20) NOT NULL DEFAULT 'LOAD',
  stage_attempt INT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  plan_json JSON,
  command_json JSON,
  next_poll_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  FOREIGN KEY(tenant_id,job_id) REFERENCES ingestion_jobs(tenant_id,id) ON DELETE CASCADE,
  CHECK(step IN ('LOAD','MANIFEST','TRANSFORM','PUBLISH','CHECKPOINT')),
  CHECK(stage_attempt >= 0), CHECK(retry_count BETWEEN 0 AND 5)
);

-- An expired owner never makes an occupied slot available to a different job.
CREATE TABLE warehouse_publisher (
  singleton_id INT PRIMARY KEY,
  job_id CHAR(36),
  lease_token CHAR(36), lease_owner VARCHAR(100),
  lease_generation BIGINT NOT NULL DEFAULT 0,
  lease_until TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  FOREIGN KEY(job_id) REFERENCES ingestion_jobs(id) ON DELETE RESTRICT,
  CHECK(singleton_id = 1), CHECK(lease_generation >= 0)
);
INSERT INTO warehouse_publisher(singleton_id) VALUES (1);
