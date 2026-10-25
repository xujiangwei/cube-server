package cube.util.tokenizer;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;

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

    public final Map<String, Double> freqs = new HashMap<String, Double>();
    public final Set<String> loadedPath = new HashSet<String>();
    private Double minFreq = Double.MAX_VALUE;
    private Double total = 0.0;
    private DictSegment _dict;

    private WordDictionary() {
        this.loadDict();
    }

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
     *
     * @param userPath
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


    public void loadUserDict(Path userDict) {
        loadUserDict(userDict, StandardCharsets.UTF_8);
    }

    public void loadUserDict(String userDictPath) {
        loadUserDict(userDictPath, StandardCharsets.UTF_8);
    }

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

    public DictSegment getTrie() {
        return this._dict;
    }


    public boolean containsWord(String word) {
        return freqs.containsKey(word);
    }


    public Double getFreq(String key) {
        if (containsWord(key))
            return freqs.get(key);
        else
            return minFreq;
    }
}
