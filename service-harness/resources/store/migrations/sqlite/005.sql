CREATE TABLE harness_runner_identity (
worker_id VARCHAR(128) NOT NULL PRIMARY KEY, runtime_id VARCHAR(128) NOT NULL, capacity INTEGER NOT NULL
);
CREATE TABLE harness_tool_job (
job_id VARCHAR(64) NOT NULL PRIMARY KEY, run_id VARCHAR(128) NOT NULL, tenant VARCHAR(128) NOT NULL,
subject_id VARCHAR(128) NOT NULL, call_id VARCHAR(128) NOT NULL, attempt INTEGER NOT NULL, origin_fence BIGINT NOT NULL,
intent_hash VARCHAR(64) NOT NULL, release_digest VARCHAR(64) NOT NULL, tool_name VARCHAR(128) NOT NULL,
definition_json TEXT NOT NULL, arguments_json TEXT NOT NULL, arguments_hash VARCHAR(64) NOT NULL,
worker_id VARCHAR(128), runtime_id VARCHAR(128), instance_id VARCHAR(128), job_hash VARCHAR(64), wire_json TEXT,
status VARCHAR(32) NOT NULL, actual_finished INTEGER NOT NULL, cancel_requested INTEGER NOT NULL,
effect_status VARCHAR(32) NOT NULL, result_json TEXT, report_hash VARCHAR(64), created_at BIGINT NOT NULL,
UNIQUE(run_id,call_id,attempt), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
);
CREATE INDEX harness_tool_queue ON harness_tool_job(status,created_at);
CREATE INDEX harness_tool_occupied ON harness_tool_job(worker_id,actual_finished,status);
CREATE TABLE harness_tool_revocation (
run_id VARCHAR(128) NOT NULL PRIMARY KEY, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL,
reason VARCHAR(128) NOT NULL, FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
);
CREATE TABLE harness_broker_exchange (
job_id VARCHAR(64) NOT NULL PRIMARY KEY, request_hash VARCHAR(64) NOT NULL, owner_id VARCHAR(128) NOT NULL,
status VARCHAR(32) NOT NULL, response_json TEXT, FOREIGN KEY(job_id) REFERENCES harness_tool_job(job_id)
);
