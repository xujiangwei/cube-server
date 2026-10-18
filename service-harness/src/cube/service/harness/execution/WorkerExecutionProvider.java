package cube.service.harness.execution;

import org.json.JSONObject;

/** A declaration/result adapter only. Physical execution belongs to a certified Python worker. */
public interface WorkerExecutionProvider extends QueryProvider {
  String executionProfile();

  /** Called with a durable, authenticated worker receipt, never with SKILL child output. */
  JSONObject complete(ProviderInvocation invocation, JSONObject output) throws Exception;
}
