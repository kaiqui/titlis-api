-- Queue Scoring — Fase 1: schema base em titlis_oltp

ALTER TABLE titlis_oltp.tenant_ai_configs
    ADD COLUMN IF NOT EXISTS queue_monitoring_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS titlis_oltp.queues (
    queue_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    provider            VARCHAR(50)  NOT NULL DEFAULT 'gcp_pubsub',
    external_id         VARCHAR(500) NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    project_id          VARCHAR(255),
    topic_id            VARCHAR(255),
    is_dlq              BOOLEAN      NOT NULL DEFAULT false,
    parent_queue_id     BIGINT       REFERENCES titlis_oltp.queues(queue_id),
    lifecycle_state     VARCHAR(20)  NOT NULL DEFAULT 'DISCOVERING',
    observation_count   INT          NOT NULL DEFAULT 0,
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    first_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, provider, external_id)
);

CREATE TABLE IF NOT EXISTS titlis_oltp.queue_observations (
    queue_observation_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_id                    BIGINT NOT NULL REFERENCES titlis_oltp.queues(queue_id),
    tenant_id                   BIGINT NOT NULL,
    num_undelivered_messages    BIGINT,
    oldest_unacked_age_seconds  BIGINT,
    pull_message_count_rate     DOUBLE PRECISION,
    send_message_count_rate     DOUBLE PRECISION,
    ack_message_count_rate      DOUBLE PRECISION,
    observed_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS titlis_oltp.queue_thresholds (
    queue_threshold_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_id            BIGINT NOT NULL REFERENCES titlis_oltp.queues(queue_id),
    tenant_id           BIGINT NOT NULL,
    p50_backlog         BIGINT,
    p75_backlog         BIGINT,
    p95_backlog         BIGINT,
    p50_age_sec         BIGINT,
    p75_age_sec         BIGINT,
    p95_age_sec         BIGINT,
    backlog_warning     BIGINT NOT NULL,
    backlog_critical    BIGINT NOT NULL,
    age_warning_sec     BIGINT NOT NULL,
    age_critical_sec    BIGINT NOT NULL,
    observation_count   INT    NOT NULL,
    calculated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (queue_id)
);

CREATE TABLE IF NOT EXISTS titlis_oltp.queue_scorecards (
    queue_scorecard_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_id            BIGINT  NOT NULL REFERENCES titlis_oltp.queues(queue_id),
    tenant_id           BIGINT  NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    version             INT     NOT NULL DEFAULT 1,
    overall_score       NUMERIC(5,2),
    compliance_status   VARCHAR(20),
    total_rules         INT,
    passed_rules        INT,
    failed_rules        INT,
    critical_failures   INT,
    error_count         INT,
    warning_count       INT,
    evaluated_at        TIMESTAMPTZ,
    raw_metadata        JSONB,
    UNIQUE (queue_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS titlis_oltp.queue_pillar_scores (
    queue_pillar_score_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_scorecard_id    BIGINT      NOT NULL REFERENCES titlis_oltp.queue_scorecards(queue_scorecard_id),
    pillar                VARCHAR(50) NOT NULL,
    pillar_score          NUMERIC(5,2),
    passed_checks         INT,
    failed_checks         INT,
    weighted_score        NUMERIC(8,4),
    UNIQUE (queue_scorecard_id, pillar)
);

CREATE TABLE IF NOT EXISTS titlis_oltp.queue_validation_results (
    queue_validation_result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_scorecard_id         BIGINT       NOT NULL REFERENCES titlis_oltp.queue_scorecards(queue_scorecard_id),
    rule_id                    VARCHAR(20)  NOT NULL,
    rule_name                  VARCHAR(255),
    pillar                     VARCHAR(50),
    severity                   VARCHAR(20),
    rule_passed                BOOLEAN      NOT NULL,
    result_message             TEXT,
    actual_value               TEXT,
    evaluated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (queue_scorecard_id, rule_id)
);

CREATE TABLE IF NOT EXISTS titlis_oltp.tenant_label_registry (
    label_registry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    label_key         VARCHAR(100) NOT NULL,
    label_value       VARCHAR(255) NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, label_key, label_value)
);

CREATE INDEX IF NOT EXISTS idx_queues_tenant_provider   ON titlis_oltp.queues (tenant_id, provider);
CREATE INDEX IF NOT EXISTS idx_queues_lifecycle         ON titlis_oltp.queues (tenant_id, lifecycle_state);
CREATE INDEX IF NOT EXISTS idx_queue_obs_queue          ON titlis_oltp.queue_observations (queue_id, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_queue_scorecards_tenant  ON titlis_oltp.queue_scorecards (tenant_id);
CREATE INDEX IF NOT EXISTS idx_label_registry_tenant    ON titlis_oltp.tenant_label_registry (tenant_id, label_key);
