package cube.util.pinyin;

import java.util.HashMap;
import java.util.Map;

public final class PinyinRules {
    private final Map<String, String[]> mOverrides = new HashMap<String, String[]>();

    /**
     * 添加单字拼音规则。
     *
     * @param c      汉字。
     * @param pinyin 拼音。
     * @return 当前规则对象，便于链式调用。
     */
    public PinyinRules add(char c, String pinyin) {
        mOverrides.put(String.valueOf(c), new String[]{pinyin});
        return this;
    }

    /**
     * 添加词语拼音规则。
     *
     * @param str    词语。
     * @param pinyin 拼音。
     * @return 当前规则对象，便于链式调用。
     */
    public PinyinRules add(String str, String pinyin) {
        mOverrides.put(str, new String[]{pinyin});
        return this;
    }

    String toPinyin(char c) {
        return mOverrides.get(String.valueOf(c))[0];
    }

    PinyinMapDict toPinyinMapDict() {
        return new PinyinMapDict() {
            @Override
            public Map<String, String[]> mapping() {
                return mOverrides;
            }
        };
    }
}
