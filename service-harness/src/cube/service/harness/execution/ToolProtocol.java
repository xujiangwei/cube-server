/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONObject;

/** Additive CPU Runner protocol; model v1/v2/v3 contracts remain frozen. */
public final class ToolProtocol {
  public static JSONObject parse(String schema, byte[] raw) {
    try {
      JSONObject value = StrictJson.object(raw);
      int version = value.optInt("protocolVersion", 4);
      String root =
          schema.equals("sandbox-receipt") || version == 6
              ? "runner-v6"
              : version == 5 ? "runner-v5" : "runner";
      Contract.validate(
          value, StrictJson.object(Contract.resource(root + "/schemas/" + schema + ".json")));
      return value;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
    }
  }

  public static JSONObject runtime() {
    return runtime(4);
  }

  public static JSONObject runtime(int version) {
    try {
      return StrictJson.object(
          Contract.resource((version == 4 ? "runner" : "runner-v" + version) + "/runtime.json"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
    }
  }

  public static void verify() {
    try {
      for (String root : new String[] {"runner", "runner-v5", "runner-v6"}) {
        JSONObject manifest = StrictJson.object(Contract.resource(root + "/manifest.json"));
        for (String p : manifest.keySet())
          if (!RuntimeSupport.sha(Contract.resource(root + "/" + p)).equals(manifest.getString(p)))
            throw new IllegalArgumentException("RUNNER_CONTRACT_DIGEST_MISMATCH");
      }
      if (!cube.service.harness.store.JdbcRunStore.hash(runtime().getJSONObject("sources"))
          .equals(runtime().getString("digest")))
        throw new IllegalArgumentException("RUNNER_SOURCE_DIGEST_MISMATCH");
    } catch (java.io.IOException e) {
      throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
    }
  }
}
