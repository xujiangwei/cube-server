CREATE TABLE harness_store_identity (
    singleton INTEGER NOT NULL PRIMARY KEY,
    store_id VARCHAR(128) NOT NULL,
    schema_hash VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_run (
    run_id VARCHAR(128) NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    profile_version VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    event_seq BIGINT NOT NULL,
    owner_id VARCHAR(128),
    fence BIGINT NOT NULL,
    lease_until BIGINT NOT NULL,
    execution_settlement VARCHAR(32) NOT NULL,
    usage_settlement VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_lease (
    run_id VARCHAR(128) NOT NULL,
    fence BIGINT NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    acquired_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY(run_id,fence),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_request (
    request_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    response_json MEDIUMTEXT NOT NULL,
    retain_until BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_event (
    run_id VARCHAR(128) NOT NULL,
    event_seq BIGINT NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    run_version BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    occurred_at BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    data_json MEDIUMTEXT NOT NULL,
    projection_json MEDIUMTEXT,
    PRIMARY KEY(run_id,event_seq),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_outbox (
    message_id VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    event_seq BIGINT NOT NULL,
    topic VARCHAR(128) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    delivered INTEGER NOT NULL,
    available_at BIGINT NOT NULL,
    claim_owner VARCHAR(128),
    claim_fence BIGINT NOT NULL,
    claim_until BIGINT NOT NULL,
    attempts INTEGER NOT NULL,
    UNIQUE(run_id,event_seq),
    FOREIGN KEY(run_id,event_seq) REFERENCES harness_event(run_id,event_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_inbox (
    consumer_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    consumed_at BIGINT NOT NULL,
    PRIMARY KEY(consumer_id,message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_round (
    run_id VARCHAR(128) NOT NULL,
    intent_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    intent_hash VARCHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    origin_fence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json MEDIUMTEXT,
    result_hash VARCHAR(64),
    PRIMARY KEY(run_id,intent_id,attempt),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_tool_call (
    run_id VARCHAR(128) NOT NULL,
    intent_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    intent_hash VARCHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    origin_fence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json MEDIUMTEXT,
    result_hash VARCHAR(64),
    PRIMARY KEY(run_id,intent_id,attempt),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_result (
    run_id VARCHAR(128) NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    terminal_state VARCHAR(32) NOT NULL,
    result_json MEDIUMTEXT NOT NULL,
    result_hash VARCHAR(64) NOT NULL,
    committed_at BIGINT NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_usage (
    run_id VARCHAR(128) NOT NULL,
    round_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    estimated_input BIGINT NOT NULL,
    estimated_output BIGINT NOT NULL,
    actual_input BIGINT,
    actual_output BIGINT,
    revision BIGINT NOT NULL,
    PRIMARY KEY(run_id,round_id,attempt),
    FOREIGN KEY(run_id,round_id,attempt) REFERENCES harness_round(run_id,intent_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_usage_adjustment (
    run_id VARCHAR(128) NOT NULL,
    round_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    evidence_id VARCHAR(128) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    previous_revision BIGINT NOT NULL,
    actual_input BIGINT NOT NULL,
    actual_output BIGINT NOT NULL,
    settled_at BIGINT NOT NULL,
    PRIMARY KEY(run_id,round_id,attempt,evidence_id),
    FOREIGN KEY(run_id,round_id,attempt) REFERENCES harness_usage(run_id,round_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_recovery ON harness_run(state,lease_until);

CREATE INDEX harness_outbox_ready ON harness_outbox(delivered,available_at,claim_until);

CREATE TABLE harness_release (
    tenant VARCHAR(128) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    skill_name VARCHAR(128) NOT NULL,
    manifest_json MEDIUMTEXT NOT NULL,
    provenance_json MEDIUMTEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY(tenant,digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_binding (
    tenant VARCHAR(128) NOT NULL,
    binding_kind VARCHAR(16) NOT NULL,
    binding_name VARCHAR(128) NOT NULL,
    target_ref VARCHAR(256) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY(tenant,binding_kind,binding_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_profile (
    tenant VARCHAR(128) NOT NULL,
    profile_kind VARCHAR(16) NOT NULL,
    profile_id VARCHAR(128) NOT NULL,
    profile_version VARCHAR(128) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    definition_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY(tenant,profile_kind,profile_id,profile_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_run_config (
    run_id VARCHAR(128) NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    config_json MEDIUMTEXT NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_release_ref (
    tenant VARCHAR(128) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant,digest,run_id),
    FOREIGN KEY(tenant,digest) REFERENCES harness_release(tenant,digest),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_run_skill (
    run_id VARCHAR(128) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    PRIMARY KEY(run_id,digest),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_workspace (
    run_id VARCHAR(128) NOT NULL PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL UNIQUE,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    fence BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    max_bytes BIGINT NOT NULL,
    max_files INTEGER NOT NULL,
    released INTEGER NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_context (
    digest VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    run_version BIGINT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_context_run ON harness_context(run_id,run_version);

CREATE TABLE harness_model_backend (
    backend_id VARCHAR(128) NOT NULL PRIMARY KEY,
    descriptor_hash VARCHAR(64) NOT NULL,
    capacity INTEGER NOT NULL,
    routing_hash VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_model_job (
    job_id VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    round_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    origin_fence BIGINT NOT NULL,
    intent_hash VARCHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    profile_digest VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_json MEDIUMTEXT NOT NULL,
    backend_id VARCHAR(128),
    worker_id VARCHAR(128),
    instance_id VARCHAR(128),
    session_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    actual_finished INTEGER NOT NULL,
    cancel_requested INTEGER NOT NULL,
    outcome_json MEDIUMTEXT,
    created_at BIGINT NOT NULL,
    execution_protocol INTEGER NOT NULL DEFAULT 2,
    dispatched_at BIGINT,
    finished_at BIGINT,
    cancelled_at BIGINT,
    UNIQUE(run_id,round_id,attempt),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id),
    FOREIGN KEY(backend_id) REFERENCES harness_model_backend(backend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_model_queue ON harness_model_job(status,created_at);

CREATE INDEX harness_model_occupied ON harness_model_job(backend_id,actual_finished);

CREATE TABLE harness_inference (
    inference_id VARCHAR(64) NOT NULL PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    wire_attempt INTEGER NOT NULL,
    backend_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_json MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    actual_finished INTEGER NOT NULL,
    cancel_requested INTEGER NOT NULL,
    response_json MEDIUMTEXT,
    error_code VARCHAR(128),
    unit_id VARCHAR(256),
    node_id VARCHAR(128),
    owner_id VARCHAR(128),
    created_at BIGINT NOT NULL,
    dispatched_at BIGINT,
    finished_at BIGINT,
    cancelled_at BIGINT,
    UNIQUE(job_id,wire_attempt),
    FOREIGN KEY(job_id) REFERENCES harness_model_job(job_id),
    FOREIGN KEY(backend_id) REFERENCES harness_model_backend(backend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_inference_event (
    inference_id VARCHAR(64) NOT NULL,
    event_seq BIGINT NOT NULL,
    event_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY(inference_id,event_seq),
    FOREIGN KEY(inference_id) REFERENCES harness_inference(inference_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_inference_queue ON harness_inference(status,created_at);

CREATE INDEX harness_inference_occupied ON harness_inference(backend_id,actual_finished,status);

CREATE TABLE harness_runner_identity (
    worker_id VARCHAR(128) NOT NULL PRIMARY KEY,
    runtime_id VARCHAR(128) NOT NULL,
    capacity INTEGER NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_tool_job (
    job_id VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    call_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    origin_fence BIGINT NOT NULL,
    intent_hash VARCHAR(64) NOT NULL,
    release_digest VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    definition_json MEDIUMTEXT NOT NULL,
    arguments_json TEXT NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    worker_id VARCHAR(128),
    runtime_id VARCHAR(128),
    instance_id VARCHAR(128),
    job_hash VARCHAR(64),
    wire_json MEDIUMTEXT,
    status VARCHAR(32) NOT NULL,
    actual_finished INTEGER NOT NULL,
    cancel_requested INTEGER NOT NULL,
    effect_status VARCHAR(32) NOT NULL,
    result_json MEDIUMTEXT,
    report_hash VARCHAR(64),
    created_at BIGINT NOT NULL,
    cleanup_after BIGINT NOT NULL DEFAULT 0,
    cleanup_done INTEGER NOT NULL DEFAULT 0,
    broker_protocol INTEGER NOT NULL DEFAULT 4,
    runner_finished INTEGER NOT NULL DEFAULT 0,
    dispatched_at BIGINT,
    finished_at BIGINT,
    cancelled_at BIGINT,
    UNIQUE(run_id,call_id,attempt),
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_tool_queue ON harness_tool_job(status,created_at);

CREATE INDEX harness_tool_occupied ON harness_tool_job(worker_id,actual_finished,status);

CREATE TABLE harness_tool_revocation (
    run_id VARCHAR(128) NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_broker_exchange (
    job_id VARCHAR(64) NOT NULL PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_json MEDIUMTEXT,
    FOREIGN KEY(job_id) REFERENCES harness_tool_job(job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_wait (
    wait_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_wait_run ON harness_wait(run_id, status);

CREATE TABLE harness_control_receipt (
    receipt_id VARCHAR(64) PRIMARY KEY,
    payload_hash VARCHAR(64) NOT NULL,
    response_json MEDIUMTEXT NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_file (
    file_id VARCHAR(128) PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    run_id VARCHAR(128),
    storage_key VARCHAR(64) NOT NULL UNIQUE,
    root_id VARCHAR(128) NOT NULL,
    file_name VARCHAR(256) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    ready_at BIGINT,
    expires_at BIGINT NOT NULL,
    policy_hash VARCHAR(64) NOT NULL,
    upload_hash VARCHAR(64),
    owner_id VARCHAR(128),
    claim_fence BIGINT NOT NULL DEFAULT 0,
    claim_until BIGINT NOT NULL DEFAULT 0,
    next_attempt BIGINT NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(128),
    deleted_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_file_expiry ON harness_file(root_id, status, expires_at);

CREATE TABLE harness_file_request (
    request_id VARCHAR(64) PRIMARY KEY,
    payload_hash VARCHAR(64) NOT NULL,
    file_id VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_file_pin (
    pin_id VARCHAR(128) PRIMARY KEY,
    file_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128),
    owner_id VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    expires_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_file_pin_file ON harness_file_pin(file_id);

CREATE TABLE harness_file_binding (
    run_id VARCHAR(128) NOT NULL,
    file_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (run_id, file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_file_policy (
    node_id VARCHAR(128) PRIMARY KEY,
    root_id VARCHAR(128) NOT NULL,
    policy_hash VARCHAR(64) NOT NULL,
    lease_until BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_file_root (
    root_id VARCHAR(128) PRIMARY KEY,
    instance_id VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_feedback (
    feedback_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    rating VARCHAR(16) NOT NULL,
    reason_json MEDIUMTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX harness_feedback_run ON harness_feedback(run_id, created_at);

CREATE TABLE harness_tenant (
    tenant VARCHAR(128) NOT NULL PRIMARY KEY,
    initialized_at BIGINT NOT NULL,
    template_digest VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
