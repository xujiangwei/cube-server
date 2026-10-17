CREATE TABLE harness_store_identity (
singleton INTEGER NOT NULL PRIMARY KEY, store_id VARCHAR(128) NOT NULL, schema_version INTEGER NOT NULL, migration_hash VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_run (
run_id VARCHAR(128) NOT NULL PRIMARY KEY, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL,
profile_version VARCHAR(128) NOT NULL, state VARCHAR(32) NOT NULL, version BIGINT NOT NULL, event_seq BIGINT NOT NULL,
owner_id VARCHAR(128), fence BIGINT NOT NULL, lease_until BIGINT NOT NULL, execution_settlement VARCHAR(32) NOT NULL,
usage_settlement VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_lease (
run_id VARCHAR(128) NOT NULL, fence BIGINT NOT NULL, owner_id VARCHAR(128) NOT NULL, acquired_at BIGINT NOT NULL,
expires_at BIGINT NOT NULL, PRIMARY KEY(run_id,fence), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_request (
request_hash VARCHAR(64) NOT NULL PRIMARY KEY, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL,
operation VARCHAR(32) NOT NULL, target_id VARCHAR(128) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
response_json MEDIUMTEXT NOT NULL, retain_until BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_event (
run_id VARCHAR(128) NOT NULL, event_seq BIGINT NOT NULL, tenant VARCHAR(128) NOT NULL, run_version BIGINT NOT NULL,
event_type VARCHAR(128) NOT NULL, occurred_at BIGINT NOT NULL, schema_version INTEGER NOT NULL, data_json MEDIUMTEXT NOT NULL,
PRIMARY KEY(run_id,event_seq), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_outbox (
message_id VARCHAR(64) NOT NULL PRIMARY KEY, run_id VARCHAR(128) NOT NULL, tenant VARCHAR(128) NOT NULL,
event_seq BIGINT NOT NULL, topic VARCHAR(128) NOT NULL, payload_json MEDIUMTEXT NOT NULL, delivered INTEGER NOT NULL,
available_at BIGINT NOT NULL, claim_owner VARCHAR(128), claim_fence BIGINT NOT NULL, claim_until BIGINT NOT NULL,
attempts INTEGER NOT NULL, UNIQUE(run_id,event_seq), FOREIGN KEY(run_id,event_seq) REFERENCES harness_event(run_id,event_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_inbox (
consumer_id VARCHAR(128) NOT NULL, message_id VARCHAR(64) NOT NULL, tenant VARCHAR(128) NOT NULL,
payload_hash VARCHAR(64) NOT NULL, consumed_at BIGINT NOT NULL, PRIMARY KEY(consumer_id,message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_round (
run_id VARCHAR(128) NOT NULL, intent_id VARCHAR(128) NOT NULL, attempt INTEGER NOT NULL, tenant VARCHAR(128) NOT NULL,
intent_hash VARCHAR(64) NOT NULL, payload_json MEDIUMTEXT NOT NULL, origin_fence BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
result_json MEDIUMTEXT, result_hash VARCHAR(64), PRIMARY KEY(run_id,intent_id,attempt), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_tool_call (
run_id VARCHAR(128) NOT NULL, intent_id VARCHAR(128) NOT NULL, attempt INTEGER NOT NULL, tenant VARCHAR(128) NOT NULL,
intent_hash VARCHAR(64) NOT NULL, payload_json MEDIUMTEXT NOT NULL, origin_fence BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
result_json MEDIUMTEXT, result_hash VARCHAR(64), PRIMARY KEY(run_id,intent_id,attempt), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_result (
run_id VARCHAR(128) NOT NULL PRIMARY KEY, tenant VARCHAR(128) NOT NULL, terminal_state VARCHAR(32) NOT NULL,
result_json MEDIUMTEXT NOT NULL, result_hash VARCHAR(64) NOT NULL, committed_at BIGINT NOT NULL,
FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_usage (
run_id VARCHAR(128) NOT NULL, round_id VARCHAR(128) NOT NULL, attempt INTEGER NOT NULL, tenant VARCHAR(128) NOT NULL,
estimated_input BIGINT NOT NULL, estimated_output BIGINT NOT NULL, actual_input BIGINT, actual_output BIGINT,
revision BIGINT NOT NULL, PRIMARY KEY(run_id,round_id,attempt),
FOREIGN KEY(run_id,round_id,attempt) REFERENCES harness_round(run_id,intent_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_usage_adjustment (
run_id VARCHAR(128) NOT NULL, round_id VARCHAR(128) NOT NULL, attempt INTEGER NOT NULL,
evidence_id VARCHAR(128) NOT NULL, evidence_hash VARCHAR(64) NOT NULL, previous_revision BIGINT NOT NULL,
actual_input BIGINT NOT NULL, actual_output BIGINT NOT NULL, settled_at BIGINT NOT NULL,
PRIMARY KEY(run_id,round_id,attempt,evidence_id), FOREIGN KEY(run_id,round_id,attempt) REFERENCES harness_usage(run_id,round_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_recovery ON harness_run(state,lease_until);

CREATE INDEX harness_outbox_ready ON harness_outbox(delivered,available_at,claim_until);
