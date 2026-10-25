package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.store.StoreException;
import java.math.*;
import java.util.Properties;
import org.json.JSONObject;

/** Closed arithmetic grammar, no script engine, reflection, variables or function calls. */
public class CalculatorProvider extends AbstractProvider {
    public CalculatorProvider(Properties p) { super("calculator", "common.calculator.evaluate", "calculator.evaluate", p); }
    protected JSONObject execute(ProviderInvocation c, JSONObject args) { return calculate(args); }
    JSONObject calculate(JSONObject args) {
        validate(args);
        try {
            BigDecimal result = new Parser(args.getString("expression")).parse().stripTrailingZeros();
            if (result.precision() - result.scale() > 4096 || result.scale() > 4096)
                throw new StoreException("CALCULATION_LIMIT");
            return new JSONObject().put("result", result.toPlainString()).put("precision", "DECIMAL128");
        } catch (ArithmeticException e) { throw new StoreException("CALCULATION_LIMIT"); }
    }
    private static final class Parser {
        private static final MathContext MC = MathContext.DECIMAL128;
        private final String text;
        private int pos, depth;
        Parser(String text) { this.text = text; }
        BigDecimal parse() {
            BigDecimal value = sum();
            space();
            if (pos != text.length()) bad();
            return value;
        }
        BigDecimal sum() {
            BigDecimal left = product();
            while (true) {
                if (eat('+')) left = left.add(product(), MC);
                else if (eat('-')) left = left.subtract(product(), MC);
                else return left;
            }
        }
        BigDecimal product() {
            BigDecimal left = atom();
            while (true) {
                if (eat('*')) left = left.multiply(atom(), MC);
                else if (eat('/')) left = left.divide(nonzero(atom()), MC);
                else if (eat('%')) left = left.remainder(nonzero(atom()), MC);
                else return left;
            }
        }
        BigDecimal atom() {
            if (++depth > 32) throw new StoreException("CALCULATION_LIMIT");
            try {
                if (eat('+')) return atom();
                if (eat('-')) return atom().negate(MC);
                if (eat('(')) { BigDecimal value = sum(); if (!eat(')')) bad(); return value; }
                space();
                int start = pos, digits = 0;
                boolean dot = false;
                while (pos < text.length()) {
                    char ch = text.charAt(pos);
                    if (ch >= '0' && ch <= '9') { digits++; pos++; }
                    else if (ch == '.' && !dot) { dot = true; pos++; }
                    else break;
                }
                if (digits == 0) bad();
                if (digits > 128) throw new StoreException("CALCULATION_LIMIT");
                return new BigDecimal(text.substring(start, pos), MC);
            } finally { depth--; }
        }
        private static BigDecimal nonzero(BigDecimal value) {
            if (value.signum() == 0) throw new StoreException("CALCULATION_DIVIDE_BY_ZERO");
            return value;
        }
        void space() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        boolean eat(char value) { space(); if (pos < text.length() && text.charAt(pos) == value) { pos++; return true; } return false; }
        void bad() { throw new StoreException("CALCULATION_EXPRESSION_INVALID"); }
    }
}
