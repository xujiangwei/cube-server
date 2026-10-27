package cube.util.tokenizer;

import java.util.regex.Pattern;


public class CharacterUtil {
    /** 正则跳过规则。 */
    public static Pattern reSkip = Pattern.compile("(\\d+\\.\\d+|[a-zA-Z0-9]+)");
    private static final char[] connectors = new char[] { '+', '#', '&', '.', '_', '-' };


    /**
     * 判断是否中文字母。
     *
     * @param ch 中文字母。
     * @return 中文字母。
     */
    public static boolean isChineseLetter(char ch) {
        if (ch >= 0x4E00 && ch <= 0x9FA5)
            return true;
        return false;
    }


    /**
     * 判断是否英文字母。
     *
     * @param ch 英文字母。
     * @return 英文字母。
     */
    public static boolean isEnglishLetter(char ch) {
        if ((ch >= 0x0041 && ch <= 0x005A) || (ch >= 0x0061 && ch <= 0x007A))
            return true;
        return false;
    }


    /**
     * 判断是否数字。
     *
     * @param ch 数字。
     * @return 数字。
     */
    public static boolean isDigit(char ch) {
        if (ch >= 0x0030 && ch <= 0x0039)
            return true;
        return false;
    }


    /**
     * 判断是否连接符。
     *
     * @param ch 连接符。
     * @return 连接符。
     */
    public static boolean isConnector(char ch) {
        for (char connector : connectors)
            if (ch == connector)
                return true;
        return false;
    }


    /**
     * 判断字符是否为中文。
     *
     * @param ch 字符。
     * @return 中文返回 {@code true} 。
     */
    public static boolean ccFind(char ch) {
        if (isChineseLetter(ch))
            return true;
        if (isEnglishLetter(ch))
            return true;
        if (isDigit(ch))
            return true;
        if (isConnector(ch))
            return true;
        return false;
    }


    /**
     * 全角 to 半角,大写 to 小写
     * @param input input。
     * 输入字符
     * @return 转换后的字符
     */
    public static char regularize(char input) {
        if (input == 12288) {
            return 32;
        }
        else if (input > 65280 && input < 65375) {
            return (char) (input - 65248);
        }
        else if (input >= 'A' && input <= 'Z') {
            return (input += 32);
        }
        return input;
    }

}
