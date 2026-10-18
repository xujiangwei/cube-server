CREATE TABLE harness_model_backend (
backend_id VARCHAR(128) NOT NULL PRIMARY KEY, descriptor_hash VARCHAR(64) NOT NULL, capacity INTEGER NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE harness_model_job (
job_id VARCHAR(64) NOT NULL PRIMARY KEY, run_id VARCHAR(128) NOT NULL, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL,
round_id VARCHAR(128) NOT NULL, attempt INTEGER NOT NULL, origin_fence BIGINT NOT NULL, intent_hash VARCHAR(64) NOT NULL,
model_id VARCHAR(128) NOT NULL, profile_digest VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL, request_json MEDIUMTEXT NOT NULL,
backend_id VARCHAR(128), worker_id VARCHAR(128), instance_id VARCHAR(128), session_id VARCHAR(128), status VARCHAR(32) NOT NULL,
actual_finished INTEGER NOT NULL, cancel_requested INTEGER NOT NULL, outcome_json MEDIUMTEXT, created_at BIGINT NOT NULL,
UNIQUE(run_id,round_id,attempt), FOREIGN KEY(run_id) REFERENCES harness_run(run_id), FOREIGN KEY(backend_id) REFERENCES harness_model_backend(backend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX harness_model_queue ON harness_model_job(status,created_at);
CREATE INDEX harness_model_occupied ON harness_model_job(backend_id,actual_finished);
