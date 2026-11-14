package cube.aigc.unit;

import org.json.JSONArray;
import java.util.function.Consumer;

/** Platform-owned execution boundary. Implementations own durable dispatch and physical reservations. */
public interface UnitGateway {
    /** Logical operation readiness for the caller; never exposes instances or transport diagnostics. */
    JSONArray availability(UnitExecutionContext context);

    /** Submit once per identity/idempotencyKey; a duplicate must return the existing execution. */
    void execute(UnitExecutionRequest request, Consumer<UnitExecutionReceipt> listener);

    /** Query/cancel only executions owned by the authenticated context. Unknown is never replayed. */
    UnitExecutionReceipt inspect(UnitExecutionContext context, String executionRef);
    UnitExecutionReceipt cancel(UnitExecutionContext context, String executionRef);
}
