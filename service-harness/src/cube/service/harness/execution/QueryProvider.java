/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import org.json.JSONObject;

/**
 * Trusted, administrator-installed extension for schema-validated tool arguments.
 * Business APIs, credentials, projection and interpretation belong to the extension. Extensions run
 * with JVM privileges; uploaded SKILL code cannot install one.
 */
public interface QueryProvider extends AutoCloseable {
    /**
     * Cleanup/probe only. Must never repeat the original operation or claim an unobserved outcome.
     */
    default boolean reconcile(ProviderInvocation invocation) throws Exception {
        return false;
    }

    default String runnerType() {
        return "tool";
    }

    default boolean independentExecution() {
        return false;
    }

    default JSONObject query(ProviderInvocation invocation, JSONObject arguments) throws Exception {
        return query(arguments);
    }

    String operation();

    String permission();

    JSONObject inputSchema();

    JSONObject resultSchema();

    void validate(JSONObject arguments);

    JSONObject query(JSONObject arguments) throws Exception;

    void validateResult(JSONObject arguments, JSONObject result);

    @Override
    void close();
}
