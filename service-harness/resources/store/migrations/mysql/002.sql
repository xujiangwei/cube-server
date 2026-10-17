CREATE TABLE harness_release (
tenant VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, skill_name VARCHAR(128) NOT NULL, manifest_json MEDIUMTEXT NOT NULL, provenance_json MEDIUMTEXT NOT NULL, state VARCHAR(16) NOT NULL, version BIGINT NOT NULL, PRIMARY KEY(tenant,digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_binding (
tenant VARCHAR(128) NOT NULL, binding_kind VARCHAR(16) NOT NULL, binding_name VARCHAR(128) NOT NULL, target_ref VARCHAR(256) NOT NULL, version BIGINT NOT NULL, PRIMARY KEY(tenant,binding_kind,binding_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_profile (
tenant VARCHAR(128) NOT NULL, profile_kind VARCHAR(16) NOT NULL, profile_id VARCHAR(128) NOT NULL, profile_version VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, definition_json MEDIUMTEXT NOT NULL, PRIMARY KEY(tenant,profile_kind,profile_id,profile_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_run_config (
run_id VARCHAR(128) NOT NULL PRIMARY KEY, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, config_json MEDIUMTEXT NOT NULL, FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_release_ref (
tenant VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, run_id VARCHAR(128) NOT NULL, PRIMARY KEY(tenant,digest,run_id), FOREIGN KEY(tenant,digest) REFERENCES harness_release(tenant,digest), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_run_skill (
run_id VARCHAR(128) NOT NULL, digest VARCHAR(64) NOT NULL, PRIMARY KEY(run_id,digest), FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_workspace (
run_id VARCHAR(128) NOT NULL PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL UNIQUE, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL, owner_id VARCHAR(128) NOT NULL, fence BIGINT NOT NULL, expires_at BIGINT NOT NULL, max_bytes BIGINT NOT NULL, max_files INTEGER NOT NULL, released INTEGER NOT NULL, FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE harness_context (
digest VARCHAR(64) NOT NULL PRIMARY KEY, run_id VARCHAR(128) NOT NULL, tenant VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL, run_version BIGINT NOT NULL, snapshot_json MEDIUMTEXT NOT NULL, FOREIGN KEY(run_id) REFERENCES harness_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX harness_context_run ON harness_context(run_id,run_version);
