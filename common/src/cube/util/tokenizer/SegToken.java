package cube.util.tokenizer;

public class SegToken {

    /** 词。 */
    public String word;

    /** 词在原文中的起始偏移。 */
    public int startOffset;

    /** 词在原文中的结束偏移。 */
    public int endOffset;

    public SegToken(String word, int startOffset, int endOffset) {
        this.word = word;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        return "[" + word + ", " + startOffset + ", " + endOffset + "]";
    }
}
