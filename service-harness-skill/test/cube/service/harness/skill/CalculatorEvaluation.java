package cube.service.harness.skill;

import org.json.JSONObject;
import java.util.Properties;

/** Diagnostic entry for executing the real arithmetic provider, never shipped in the extension JAR. */
public final class CalculatorEvaluation {
    public static void main(String[] args) {
        CalculatorProvider provider = new CalculatorProvider(new Properties());
        JSONObject input = new JSONObject().put("expression", args[0]);
        JSONObject result = provider.calculate(input);
        provider.validateResult(input, result);
        System.out.println(result.toString());
    }
}
