/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Properties;

/**
 * 四则运算技能：封闭的算术文法。
 *
 * <p>不使用脚本引擎、反射、变量与函数调用，只支持加减乘除取模、括号与一元正负号，
 * 数值语义固定为 {@link MathContext#DECIMAL128} 。</p>
 */
public class CalculatorProvider extends AbstractProvider {
    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public CalculatorProvider(Properties p) {
        super("calculator", "common.calculator.evaluate", "calculator.evaluate", p);
    }

    /**
     * 执行计算。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数，含 {@code expression} 。
     * @return 返回计算结果。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) {
        return calculate(args);
    }

    /**
     * 校验并计算表达式。
     *
     * @param args 调用参数，含 {@code expression} 。
     * @return 返回结果，含 {@code result} 与 {@code precision} 。
     */
    JSONObject calculate(JSONObject args) {
        validate(args);
        try {
            BigDecimal result = new Parser(args.getString("expression")).parse().stripTrailingZeros();
            if (result.precision() - result.scale() > Parser.MAX_INTEGER_DIGITS
                    || result.scale() > Parser.MAX_INTEGER_DIGITS) {
                throw new StoreException("CALCULATION_LIMIT");
            }
            return new JSONObject()
                    .put("result", result.toPlainString())
                    .put("precision", "DECIMAL128");
        } catch (ArithmeticException e) {
            throw new StoreException("CALCULATION_LIMIT");
        }
    }

    /**
     * 表达式解析器：递归下降，只认封闭文法里的符号。
     */
    private static final class Parser {
        /**
         * 数值精度上下文，固定为 DECIMAL128 。
         */
        private static final MathContext MC = MathContext.DECIMAL128;

        /**
         * 一元符号与括号允许的最大嵌套层数。
         */
        private static final int MAX_DEPTH = 32;

        /**
         * 单个数字字面量允许的最大位数。
         */
        private static final int MAX_DIGITS = 128;

        /**
         * 结果允许的最大整数位数或小数位数。
         */
        private static final int MAX_INTEGER_DIGITS = 4096;

        /**
         * 待解析的表达式原文。
         */
        private final String text;

        /**
         * 当前解析位置。
         */
        private int pos;

        /**
         * 当前递归深度，用于限制嵌套层数。
         */
        private int depth;

        /**
         * 构造函数。
         *
         * @param text 表达式原文。
         */
        Parser(String text) {
            this.text = text;
        }

        /**
         * 解析整个表达式，并要求所有字符都被消费。
         *
         * @return 返回计算结果。
         */
        BigDecimal parse() {
            BigDecimal value = sum();
            space();
            if (pos != text.length()) {
                bad();
            }
            return value;
        }

        /**
         * 解析加减表达式。
         *
         * @return 返回计算结果。
         */
        BigDecimal sum() {
            BigDecimal left = product();
            while (true) {
                if (eat('+')) {
                    left = left.add(product(), MC);
                } else if (eat('-')) {
                    left = left.subtract(product(), MC);
                } else {
                    return left;
                }
            }
        }

        /**
         * 解析乘除取模表达式。
         *
         * @return 返回计算结果。
         */
        BigDecimal product() {
            BigDecimal left = atom();
            while (true) {
                if (eat('*')) {
                    left = left.multiply(atom(), MC);
                } else if (eat('/')) {
                    left = left.divide(nonzero(atom()), MC);
                } else if (eat('%')) {
                    left = left.remainder(nonzero(atom()), MC);
                } else {
                    return left;
                }
            }
        }

        /**
         * 解析最小单元：一元符号、括号或数字。
         *
         * @return 返回计算结果。
         */
        BigDecimal atom() {
            if (++depth > MAX_DEPTH) {
                throw new StoreException("CALCULATION_LIMIT");
            }
            try {
                if (eat('+')) {
                    return atom();
                }
                if (eat('-')) {
                    return atom().negate(MC);
                }
                if (eat('(')) {
                    BigDecimal value = sum();
                    if (!eat(')')) {
                        bad();
                    }
                    return value;
                }
                space();
                int start = pos;
                int digits = 0;
                boolean dot = false;
                while (pos < text.length()) {
                    char ch = text.charAt(pos);
                    if (ch >= '0' && ch <= '9') {
                        digits++;
                        pos++;
                    } else if (ch == '.' && !dot) {
                        // 一个数字里只允许出现一个小数点。
                        dot = true;
                        pos++;
                    } else {
                        break;
                    }
                }
                if (digits == 0) {
                    bad();
                }
                if (digits > MAX_DIGITS) {
                    throw new StoreException("CALCULATION_LIMIT");
                }
                return new BigDecimal(text.substring(start, pos), MC);
            } finally {
                depth--;
            }
        }

        /**
         * 校验除数不为零。
         *
         * @param value 除数。
         * @return 返回原除数。
         */
        private static BigDecimal nonzero(BigDecimal value) {
            if (value.signum() == 0) {
                throw new StoreException("CALCULATION_DIVIDE_BY_ZERO");
            }
            return value;
        }

        /**
         * 跳过空白字符。
         */
        void space() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 尝试消费一个指定字符。
         *
         * @param value 待消费的字符。
         * @return 消费成功返回 {@code true} ，否则返回 {@code false} 。
         */
        boolean eat(char value) {
            space();
            if (pos < text.length() && text.charAt(pos) == value) {
                pos++;
                return true;
            }
            return false;
        }

        /**
         * 表达式非法时抛出。
         */
        void bad() {
            throw new StoreException("CALCULATION_EXPRESSION_INVALID");
        }
    }
}
