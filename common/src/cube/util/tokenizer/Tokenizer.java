package cube.util.tokenizer;

import cell.util.log.Logger;
import cube.util.tokenizer.viterbi.FinalSeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Tokenizer {

    private final static String blank = " ";

    /**
     * 默认用户词典是否已加载。
     *
     * <p>原实现每次构造实例都会 stat/枚举 {@code assets/tokenizer/} 并直接加载用户词典，
     * 实例化次数越多 I/O 越多；这里收敛为一次，且推迟到首次分词/预热时执行。</p>
     */
    private final static AtomicBoolean USER_DICT_LOADED = new AtomicBoolean(false);

    public enum SegMode {
        INDEX,
        SEARCH
    }

    /**
     * 构造函数保持轻量：不读词表、不读用户词典。
     *
     * <p>词表加载统一发生在 {@link #dict()} 首次被调用时（即首次分词或
     * {@link #warmUp()}），这样 {@code new Tokenizer()} 不再触发 349k 词条的解析。</p>
     */
    public Tokenizer() {
        // Nothing
    }

    /**
     * 词表实例（首次真正分词时才加载）。
     *
     * <p>改造前这里是 {@code static final WordDictionary}，导致<b>只要引用 Tokenizer 类</b>
     * 就会在类初始化阶段解析 349k 词条；改为方法内取值后，加载时机推迟到首次分词。</p>
     *
     * @return 词表实例。
     */
    private static WordDictionary dict() {
        ensureUserDictLoaded();
        return WordDictionary.getInstance();
    }

    /**
     * 加载默认用户词典（仅一次）。
     *
     * <p>放在 {@link #dict()} 内部而不是构造函数里：构造函数应保持零 I/O，
     * 否则 {@code new Tokenizer()} 依然会把词典加载拉回调用线程。</p>
     */
    private static void ensureUserDictLoaded() {
        if (!USER_DICT_LOADED.compareAndSet(false, true)) {
            return;
        }

        Path path = Paths.get("assets/tokenizer/");
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return;
        }

        try {
            // 先触发主词表加载，再叠加用户词典
            WordDictionary.getInstance().init(path);
        } catch (Throwable t) {
            Logger.w(Tokenizer.class, "Failed to load user dictionaries", t);
        }
    }

    /**
     * HMM 模型实例（首次真正分词时才加载）。
     *
     * @return 模型实例。
     */
    private static FinalSeg seg() {
        return FinalSeg.getInstance();
    }

    /**
     * 预热分词器：显式触发词表与 HMM 模型加载。
     *
     * <p>懒加载只是把成本从"类初始化"挪到"首次分词"，如果不预热，抖动会落到第一个
     * 业务请求上。服务启动时在<b>引导线程</b>里调用本方法，把成本放在可控且有日志的位置。</p>
     *
     * <p>失败只记录告警，不抛出——分词不可用不应阻断服务启动。</p>
     */
    public static void warmUp() {
        long begin = System.currentTimeMillis();
        try {
            // 用 dict() 而不是 WordDictionary.getInstance()：前者会连同默认用户词典一起加载
            WordDictionary wordDictionary = dict();
            FinalSeg.getInstance();
            Logger.i(Tokenizer.class, "Tokenizer warm-up finished, words: " + wordDictionary.size()
                    + ", elapsed: " + (System.currentTimeMillis() - begin) + "ms");
        } catch (Throwable t) {
            Logger.w(Tokenizer.class, "Tokenizer warm-up failed", t);
        }
    }

    /**
     * initialize the user dictionary.
     *
     * @param path user dict dir
     */
    public void initUserDict(Path path) {
        dict().init(path);
    }

    /**
     * 初始化用户词典。
     *
     * @param paths 用户词典文件路径数组。
     */
    public void initUserDict(String[] paths) {
        dict().init(paths);
    }

    private Map<Integer, List<Integer>> createDAG(String sentence, WordDictionary wordDict) {
        Map<Integer, List<Integer>> dag = new HashMap<Integer, List<Integer>>();
        DictSegment trie = wordDict.getTrie();
        char[] chars = sentence.toCharArray();
        int N = chars.length;
        int i = 0, j = 0;
        while (i < N) {
            Hit hit = trie.match(chars, i, j - i + 1);
            if (hit.isPrefix() || hit.isMatch()) {
                if (hit.isMatch()) {
                    if (!dag.containsKey(i)) {
                        List<Integer> value = new ArrayList<Integer>();
                        dag.put(i, value);
                        value.add(j);
                    } else
                        dag.get(i).add(j);
                }
                j += 1;
                if (j >= N) {
                    i += 1;
                    j = i;
                }
            } else {
                i += 1;
                j = i;
            }
        }
        for (i = 0; i < N; ++i) {
            if (!dag.containsKey(i)) {
                List<Integer> value = new ArrayList<Integer>();
                value.add(i);
                dag.put(i, value);
            }
        }
        return dag;
    }


    private Map<Integer, Pair<Integer>> calc(String sentence, Map<Integer, List<Integer>> dag,
                                             WordDictionary wordDict) {
        int N = sentence.length();
        HashMap<Integer, Pair<Integer>> route = new HashMap<Integer, Pair<Integer>>();
        route.put(N, new Pair<Integer>(0, 0.0));
        for (int i = N - 1; i > -1; i--) {
            Pair<Integer> candidate = null;
            for (Integer x : dag.get(i)) {
                double freq = wordDict.getFreq(sentence.substring(i, x + 1)) + route.get(x + 1).freq;
                if (null == candidate) {
                    candidate = new Pair<Integer>(x, freq);
                } else if (candidate.freq < freq) {
                    candidate.freq = freq;
                    candidate.key = x;
                }
            }
            route.put(i, candidate);
        }
        return route;
    }

    /**
     * 标准分词。
     * @param paragraph paragraph。
     * @param mode mode。
     * @return 处理结果。
     */
    public synchronized List<SegToken> process(String paragraph, SegMode mode) {
        WordDictionary wordDict = dict();

        List<SegToken> tokens = new ArrayList<SegToken>();
        StringBuilder sb = new StringBuilder();
        int offset = 0;
        for (int i = 0; i < paragraph.length(); ++i) {
            char ch = CharacterUtil.regularize(paragraph.charAt(i));
            if (CharacterUtil.ccFind(ch)) {
                sb.append(ch);
            }
            else {
                if (sb.length() > 0) {
                    // process
                    if (mode == SegMode.SEARCH) {
                        for (String word : sentenceProcess(sb.toString())) {
                            tokens.add(new SegToken(word, offset, offset += word.length()));
                        }
                    } else {
                        for (String token : sentenceProcess(sb.toString())) {
                            if (token.length() > 2) {
                                String gram2;
                                int j = 0;
                                for (; j < token.length() - 1; ++j) {
                                    gram2 = token.substring(j, j + 2);
                                    if (wordDict.containsWord(gram2))
                                        tokens.add(new SegToken(gram2, offset + j, offset + j + 2));
                                }
                            }
                            if (token.length() > 3) {
                                String gram3;
                                int j = 0;
                                for (; j < token.length() - 2; ++j) {
                                    gram3 = token.substring(j, j + 3);
                                    if (wordDict.containsWord(gram3))
                                        tokens.add(new SegToken(gram3, offset + j, offset + j + 3));
                                }
                            }
                            tokens.add(new SegToken(token, offset, offset += token.length()));
                        }
                    }
                    sb = new StringBuilder();
                    offset = i;
                }
                if (wordDict.containsWord(paragraph.substring(i, i + 1)))
                    tokens.add(new SegToken(paragraph.substring(i, i + 1), offset, ++offset));
                else
                    tokens.add(new SegToken(paragraph.substring(i, i + 1), offset, ++offset));
            }
        }
        if (sb.length() > 0)
            if (mode == SegMode.SEARCH) {
                for (String token : sentenceProcess(sb.toString())) {
                    tokens.add(new SegToken(token, offset, offset += token.length()));
                }
            } else {
                for (String token : sentenceProcess(sb.toString())) {
                    if (token.length() > 2) {
                        String gram2;
                        int j = 0;
                        for (; j < token.length() - 1; ++j) {
                            gram2 = token.substring(j, j + 2);
                            if (wordDict.containsWord(gram2))
                                tokens.add(new SegToken(gram2, offset + j, offset + j + 2));
                        }
                    }
                    if (token.length() > 3) {
                        String gram3;
                        int j = 0;
                        for (; j < token.length() - 2; ++j) {
                            gram3 = token.substring(j, j + 3);
                            if (wordDict.containsWord(gram3))
                                tokens.add(new SegToken(gram3, offset + j, offset + j + 3));
                        }
                    }
                    tokens.add(new SegToken(token, offset, offset += token.length()));
                }
            }

        return tokens;
    }


    /**
     * 整句分句。
     * @param sentence sentence。
     * @return 处理结果。
     */
    public synchronized List<String> sentenceProcess(String sentence) {
        WordDictionary wordDict = dict();
        FinalSeg finalSeg = seg();

        List<String> tokens = new ArrayList<String>();
        int N = sentence.length();
        Map<Integer, List<Integer>> dag = createDAG(sentence, wordDict);
        Map<Integer, Pair<Integer>> route = calc(sentence, dag, wordDict);

        int x = 0;
        int y = 0;
        String buf;
        StringBuilder sb = new StringBuilder();
        while (x < N) {
            y = route.get(x).key + 1;
            String lWord = sentence.substring(x, y);
            if (y - x == 1) {
                sb.append(lWord);
            }
            else {
                if (sb.length() > 0) {
                    buf = sb.toString();
                    sb = new StringBuilder();
                    if (buf.length() == 1) {
                        tokens.add(buf);
                    } else {
                        if (wordDict.containsWord(buf)) {
                            tokens.add(buf);
                        } else {
                            finalSeg.cut(buf, tokens);
                        }
                    }
                }
                tokens.add(lWord);
            }
            x = y;
        }
        buf = sb.toString();
        if (buf.length() > 0) {
            if (buf.length() == 1) {
                tokens.add(buf);
            } else {
                if (wordDict.containsWord(buf)) {
                    tokens.add(buf);
                } else {
                    finalSeg.cut(buf, tokens);
                }
            }
        }

        tokens.removeIf(s -> s.equalsIgnoreCase(blank));

        return tokens;
    }
}
