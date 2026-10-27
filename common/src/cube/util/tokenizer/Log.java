package cube.util.tokenizer;

/**
 * @description: enable output content to be controlled by switch
 * @author: sharkdoodoo@foxmail.com
 * @date: 2022/6/21
 */
public class Log {

    private static final boolean LOG_ENABLE = Boolean.parseBoolean(System.getProperty("jieba.log.enable", "true"));

    /**
     * 输出调试日志。
     *
     * @param debugInfo 日志内容。
     */
    public static final void debug(String debugInfo) {
        if (LOG_ENABLE) {
            System.out.println(debugInfo);
        }
    }

    /**
     * 输出错误日志。
     *
     * @param errorInfo 日志内容。
     */
    public static final void error(String errorInfo) {
        if (LOG_ENABLE) {
            System.err.println(errorInfo);
        }
    }
}
