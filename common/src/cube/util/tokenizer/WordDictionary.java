package cube.util.tokenizer;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;

/**
 * 主词典。
 *
 * <p>维护词到词频的映射与词典前缀树，支持从多个路径加载主词典与用户词典。
 * 以单例存在，全局共享一份词表。</p>
 */
public class WordDictionary {

    /**
     * 延迟持有者。
     *
     * <p>用类初始化锁代替原来的双检锁：{@code getInstance()} 首次被调用时才加载词表，
     * 由 JVM 保证线程安全与安全发布（原实现缺少 {@code volatile}，属不安全发布）。</p>
     *
     * <p>关键收益：<b>引用 {@link Tokenizer} 这个类不再触发 349k 词条的加载</b>，
     * 只有真正调用分词时才会解析词表。</p>
     */
    private static final class Holder {
        private static final WordDictionary INSTANCE = new WordDictionary();
    }

    private static final String MAIN_DICT = "assets/tokenizer/dict.txt";
    private static String USER_DICT_SUFFIX = ".dict";

    /** 词到词频的映射。 */
    public final Map<String, Double> freqs = new HashMap<String, Double>();
    /** 已加载的词典文件路径，用于避免重复加载。 */
    public final Set<String> loadedPath = new HashSet<String>();
    private Double minFreq = Double.MAX_VALUE;
    private Double total = 0.0;
    private DictSegment _dict;

    private WordDictionary() {
        this.loadDict();
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public static WordDictionary getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 词条数量。
     *
     * @return 已加载的词条数，词表加载失败时为 0。
     */
    public int size() {
        return this.freqs.size();
    }

    /**
     * Initialize the user dictionary.
     * @param userPath userPath。
     */
    public void init(Path userPath) {
        String absPath = userPath.toAbsolutePath().toString();
        Log.debug("initialize user dictionary:" + absPath);
        synchronized (WordDictionary.class) {
            if (loadedPath.contains(absPath))
                return;

            DirectoryStream<Path> stream;
            try {
                stream = Files.newDirectoryStream(userPath, String.format(Locale.getDefault(), "*%s", USER_DICT_SUFFIX));
                for (Path path : stream) {
                    if (path.getFileName().startsWith(".")) {
                        // 跳过 "." 开头的文件
                        continue;
                    }

                    Log.debug(String.format(Locale.getDefault(), "loading dict %s", path.toString()));
                    this.loadUserDict(path);
                }
                loadedPath.add(absPath);
            } catch (IOException e) {
                Log.error(String.format(Locale.getDefault(), "%s: load user dict failure!", userPath.toString()));
            }
        }
    }

    /**
     * 按路径初始化词典。
     *
     * @param paths 主词典文件路径数组。
     */
    public void init(String[] paths) {
        synchronized (WordDictionary.class) {
            for (String path : paths) {
                if (!loadedPath.contains(path)) {
                    try {
                        Log.debug("initialize user dictionary: " + path);
                        this.loadUserDict(path);
                        loadedPath.add(path);
                    } catch (Exception e) {
                        Log.error(String.format(Locale.getDefault(), "%s: load user dict failure!", path));
                    }
                }
            }
        }
    }

    /**
     * let user just use their own dict instead of the default dict
     */
    public void resetDict() {
        _dict = new DictSegment((char) 0);
        freqs.clear();
    }

    /**
     * 加载主词典。
     *
     * <p>逐行读取词典文件，建立词频表与前缀树；已加载的路径会跳过。</p>
     */
    public void loadDict() {
        _dict = new DictSegment((char) 0);
        InputStream is = null;
        try {
            is = new FileInputStream(new File(MAIN_DICT));
            BufferedReader br = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));

            long s = System.currentTimeMillis();
            while (br.ready()) {
                String line = br.readLine();
                String[] tokens = line.split("[\t ]+");

                if (tokens.length < 2)
                    continue;

                String word = tokens[0];
                double freq = Double.valueOf(tokens[1]);
                total += freq;
                word = addWord(word);
                freqs.put(word, freq);
            }
            // normalize
            for (Entry<String, Double> entry : freqs.entrySet()) {
                entry.setValue((Math.log(entry.getValue() / total)));
                minFreq = Math.min(entry.getValue(), minFreq);
            }
            Log.debug(String.format(Locale.getDefault(), "main dict load finished, time elapsed %d ms",
                    System.currentTimeMillis() - s));
        } catch (IOException e) {
            Log.error(String.format(Locale.getDefault(), "%s load failure!", MAIN_DICT));
        } finally {
            try {
                if (null != is)
                    is.close();
            } catch (IOException e) {
                Log.error(String.format(Locale.getDefault(), "%s close failure!", MAIN_DICT));
            }
        }
    }


    private String addWord(String word) {
        if (null != word && !"".equals(word.trim())) {
            String key = word.trim().toLowerCase(Locale.getDefault());
            _dict.fillSegment(key.toCharArray());
            return key;
        } else
            return null;
    }


    /**
     * 加载用户词典。
     *
     * @param userDict 词典文件路径，按 UTF-8 读取。
     */
    public void loadUserDict(Path userDict) {
        loadUserDict(userDict, StandardCharsets.UTF_8);
    }

    /**
     * 加载用户词典。
     *
     * @param userDictPath 词典文件路径，按 UTF-8 读取。
     */
    public void loadUserDict(String userDictPath) {
        loadUserDict(userDictPath, StandardCharsets.UTF_8);
    }

    /**
     * 按指定字符集加载用户词典。
     *
     * @param userDict 词典文件路径。
     * @param charset  文件字符集。
     */
    public void loadUserDict(Path userDict, Charset charset) {
        try {
            BufferedReader br = Files.newBufferedReader(userDict, charset);
            long s = System.currentTimeMillis();
            int count = 0;
            while (br.ready()) {
                String line = br.readLine();
                String[] tokens = line.split("[\t ]+");

                if (tokens.length < 1) {
                    // Ignore empty line
                    continue;
                }

                String word = tokens[0];

                double freq = 3.0d;
                if (tokens.length == 2)
                    freq = Double.valueOf(tokens[1]);
                word = addWord(word);
                freqs.put(word, Math.log(freq / total));
                count++;
            }
            Log.debug(String.format(Locale.getDefault(), "user dict %s load finished, tot words:%d, time elapsed:%dms", userDict.toString(), count, System.currentTimeMillis() - s));
            br.close();
        } catch (IOException e) {
            Log.error(String.format(Locale.getDefault(), "%s: load user dict failure!", userDict.toString()));
        }
    }

    /**
     * 按指定字符集加载用户词典。
     *
     * @param userDictPath 词典文件路径。
     * @param charset      文件字符集。
     */
    public void loadUserDict(String userDictPath, Charset charset) {
        InputStream is = null;
        try {
            is = new FileInputStream(new File(userDictPath));
            BufferedReader br = new BufferedReader(new InputStreamReader(is, charset));

            long s = System.currentTimeMillis();
            int count = 0;
            while (br.ready()) {
                String line = br.readLine();
                String[] tokens = line.split("[\t ]+");

                if (tokens.length < 1) {
                    // Ignore empty line
                    continue;
                }

                String word = tokens[0];

                double freq = 3.0d;
                if (tokens.length == 2)
                    freq = Double.valueOf(tokens[1]);
                word = addWord(word);
                freqs.put(word, Math.log(freq / total));
                count++;
            }
            Log.debug(String.format(Locale.getDefault(), "user dict %s load finished, tot words:%d, time elapsed:%dms", userDictPath, count, System.currentTimeMillis() - s));
            br.close();
        } catch (IOException e) {
            Log.error(String.format(Locale.getDefault(), "%s: load user dict failure!", userDictPath));
        }
    }

    /**
     * 获取词典前缀树根节点。
     *
     * @return 根节点。
     */
    public DictSegment getTrie() {
        return this._dict;
    }


    /**
     * 判断是否包含词。
     *
     * @param word 词。
     * @return 词。
     */
    public boolean containsWord(String word) {
        return freqs.containsKey(word);
    }


    /**
     * 查询词频。
     *
     * @param key 词。
     * @return 词频。
     */
    public Double getFreq(String key) {
        if (containsWord(key))
            return freqs.get(key);
        else
            return minFreq;
    }
}
