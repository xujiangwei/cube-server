ALTER TABLE harness_model_job ADD COLUMN execution_protocol INTEGER NOT NULL DEFAULT 2;
ALTER TABLE harness_model_backend ADD COLUMN routing_hash VARCHAR(64);
CREATE TABLE harness_inference (
inference_id VARCHAR(64) NOT NULL PRIMARY KEY, job_id VARCHAR(64) NOT NULL, wire_attempt INTEGER NOT NULL,
backend_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL, request_json MEDIUMTEXT NOT NULL,
status VARCHAR(32) NOT NULL, actual_finished INTEGER NOT NULL, cancel_requested INTEGER NOT NULL,
response_json MEDIUMTEXT, error_code VARCHAR(128), unit_id VARCHAR(256), node_id VARCHAR(128), owner_id VARCHAR(128), created_at BIGINT NOT NULL,
UNIQUE(job_id,wire_attempt), FOREIGN KEY(job_id) REFERENCES harness_model_job(job_id),
FOREIGN KEY(backend_id) REFERENCES harness_model_backend(backend_id)
);
CREATE TABLE harness_inference_event (
inference_id VARCHAR(64) NOT NULL, event_seq BIGINT NOT NULL, event_json MEDIUMTEXT NOT NULL,
PRIMARY KEY(inference_id,event_seq), FOREIGN KEY(inference_id) REFERENCES harness_inference(inference_id)
);
CREATE INDEX harness_inference_queue ON harness_inference(status,created_at);
CREATE INDEX harness_inference_occupied ON harness_inference(backend_id,actual_finished,status);
