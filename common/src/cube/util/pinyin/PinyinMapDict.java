package cube.util.pinyin;

import java.util.Map;
import java.util.Set;

/**
 * 基于{@link Map}的字典实现，利于添加自定义字典
 *
 * Created by guyacong on 2016/12/23.
 */

public abstract class PinyinMapDict implements PinyinDict {

    /**
     * Key为字典的词，Value为该词所对应的拼音
     *
     * @return 包含词和对应拼音的 {@link Map}
     */
    public abstract Map<String, String[]> mapping();


    /**
     * 获取词典中的全部词语。
     *
     * @return 词语集合。
     */
    @Override
    public Set<String> words() {
        return mapping() != null ? mapping().keySet() : null;
    }

    /**
     * 查询词语的拼音。
     *
     * @param word 词语。
     * @return 拼音数组；词典中无该词时返回 {@code null} 。
     */
    @Override
    public String[] toPinyin(String word) {
        return mapping() != null ? mapping().get(word) : null;
    }
}
