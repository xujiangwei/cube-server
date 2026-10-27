package cube.util.tokenizer;

public class SegToken {

    /** 词。 */
    public String word;

    public int startOffset;

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
