CREATE TABLE budget_plans (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, campaign_id CHAR(36) NOT NULL,
  period_start DATE NOT NULL, period_end DATE NOT NULL,
  budget_amount DECIMAL(20,6) NOT NULL, currency CHAR(3) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1, active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id,id), UNIQUE (tenant_id,campaign_id,period_start,period_end),
  INDEX budget_overlap (tenant_id,campaign_id,active,period_start,period_end),
  FOREIGN KEY (tenant_id,campaign_id) REFERENCES campaigns(tenant_id,id),
  CHECK (period_start<=period_end), CHECK (budget_amount>0), CHECK (revision>=1)
);
CREATE TABLE kpi_targets (
  id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, budget_plan_id CHAR(36) NOT NULL,
  metric VARCHAR(20) NOT NULL, operator VARCHAR(3) NOT NULL,
  target_value DECIMAL(20,6) NOT NULL, min_samples BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  FOREIGN KEY (tenant_id,budget_plan_id) REFERENCES budget_plans(tenant_id,id),
  CHECK (metric IN ('SPEND','CONVERSIONS','CPA','ROAS')),
  CHECK (operator IN ('LTE','GTE')), CHECK (target_value>0), CHECK (min_samples>=0)
);
