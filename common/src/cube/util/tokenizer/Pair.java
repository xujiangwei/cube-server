package cube.util.tokenizer;

public class Pair<K> {
    /** 键。 */
    public K key;
    public Double freq = 0.0;

    public Pair(K key, double freq) {
	this.key = key;
	this.freq = freq;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
	return "Candidate [key=" + key + ", freq=" + freq + "]";
    }

}
