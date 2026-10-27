/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology;

import cell.util.log.Logger;
import cube.aigc.Tokenizable;
import cube.aigc.psychology.algorithm.KnowledgeStrategy;
import cube.aigc.psychology.composition.Scale;
import cube.common.Language;
import cube.common.entity.Membership;
import cube.util.JSONUtils;
import cube.util.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源。
 */
public class Resource {

    private File termDescriptionFile = new File("assets/psychology/interpretation.json");
    private long termDescriptionLastModified = 0;
    private List<KnowledgeStrategy> knowledgeStrategies;

    private File corpusFile = new File("assets/psychology/corpus.json");
    private long corpusLastModified = 0;
    private JSONObject corpusJson = null;

    private File questionnairesPath = new File("assets/psychology/questionnaires/");
    private Map<String, File> scaleNameFileMap = new ConcurrentHashMap<>();

    private File datasetFile = new File("assets/psychology/dataset.json");
    private long datasetFileModified = 0;
    private Dataset dataset;

    private File attentionScriptFile = new File("assets/psychology/scripts/attention.js");
    private long attentionScriptFileModified = 0;
    private String attentionScriptContent = null;

    private File suggestionScriptFile = new File("assets/psychology/scripts/suggestion.js");
    private long suggestionScriptFileModified = 0;
    private String suggestionScriptFileContent = null;

//    private File childStrategyFile = new File("assets/psychology/strategies/child_strategy.md");
//    private long childStrategyFileModified = 0;
//    private String childStrategyContent = null;

//    private File teenagerStrategyFile = new File("assets/psychology/strategies/teenager_strategy.md");
//    private long teenagerStrategyFileModified = 0;
//    private String teenagerStrategyContent = null;

    private File memberFile = new File("assets/psychology/membership.json");
    private long memberFileModified = 0;
    private JSONObject membershipData;

    private Tokenizable tokenizer;

    private final static Resource instance = new Resource();

    private Resource() {
        this.knowledgeStrategies = new ArrayList<>();
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public static Resource getInstance() {
        return Resource.instance;
    }

    /**
     * 设置分词器。
     *
     * @param tokenizer 分词器实现。
     */
    public void setTokenizer(Tokenizable tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * 校验文件列表。
     *
     * @return 文件列表。
     */
    public boolean checkFiles() {
        if (!this.termDescriptionFile.exists()) {
            Logger.e(this.getClass(), "#checkFiles - File is NOT exists: " + this.termDescriptionFile.getName());
            return false;
        }
        else {
            Logger.i(this.getClass(), "#checkFiles - File exists: " + this.termDescriptionFile.getName());
        }

        if (!this.corpusFile.exists()) {
            Logger.e(this.getClass(), "#checkFiles - File is NOT exists: " + this.corpusFile.getName());
            return false;
        }
        else {
            Logger.i(this.getClass(), "#checkFiles - File exists: " + this.corpusFile.getName());
        }

        if (!this.datasetFile.exists()) {
            Logger.e(this.getClass(), "#checkFiles - File is NOT exists: " + this.datasetFile.getName());
            return false;
        }
        else {
            Logger.i(this.getClass(), "#checkFiles - File exists: " + this.datasetFile.getName());
        }

        return true;
    }

    /**
     * 加载术语解释表。
     *
     * <p>按类别读取解释文本并建立索引，重复调用会重新加载。</p>
     *
     * @return 知识策略列表；无数据时为空列表。
     */
    public synchronized List<KnowledgeStrategy> loadTermInterpretations() {
        if (this.termDescriptionFile.exists()) {
            if (this.termDescriptionFile.lastModified() != this.termDescriptionLastModified) {
                this.termDescriptionLastModified = this.termDescriptionFile.lastModified();
                this.knowledgeStrategies.clear();

                Logger.i(this.getClass(), "Read term description file: " + this.termDescriptionFile.getAbsolutePath());

                try {
                    byte[] data = Files.readAllBytes(Paths.get(this.termDescriptionFile.getAbsolutePath()));
                    JSONArray array = new JSONArray(new String(data, StandardCharsets.UTF_8));
                    for (int i = 0; i < array.length(); ++i) {
                        KnowledgeStrategy cd = new KnowledgeStrategy(array.getJSONObject(i));
                        this.knowledgeStrategies.add(cd);
                    }
                } catch (IOException e) {
                    Logger.e(Resource.class, "#loadTermInterpretations - I/O error", e);
                }
            }
        }

        return this.knowledgeStrategies;
    }

    /**
     * 获取术语解释。
     *
     * @param term 术语。
     * @return 解释；未收录返回 {@code null} 。
     */
    public KnowledgeStrategy getTermInterpretation(Term term) {
        this.loadTermInterpretations();

        for (KnowledgeStrategy interpretation : this.knowledgeStrategies) {
            if (interpretation.getTerm() == term) {
                return interpretation;
            }
        }

        return null;
    }

    private JSONObject loadMembershipData() {
        if (this.memberFile.exists()) {
            if (this.memberFile.lastModified() != this.memberFileModified) {
                this.memberFileModified = this.memberFile.lastModified();

                Logger.i(this.getClass(), "Read member file: " + this.memberFile.getAbsolutePath());

                try {
                    byte[] data = Files.readAllBytes(Paths.get(this.memberFile.getAbsolutePath()));
                    this.membershipData = new JSONObject(new String(data, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Logger.e(Resource.class, "#loadMembershipData - I/O error", e);
                }
            }
        }

        return this.membershipData;
    }

    /**
     * 获取会员权益清单。
     *
     * @param memberType 会员类型。
     * @return 权益列表；未知类型时为空列表。
     */
    public List<String> getMemberBenefits(String memberType) {
        JSONObject data = this.loadMembershipData();
        if (null == data) {
            return null;
        }

        data = data.getJSONObject("benefits");
        JSONArray list = data.has(memberType) ? data.getJSONArray(memberType) : data.getJSONArray(Membership.TYPE_PREMIUM);
        return JSONUtils.toStringList(list);
    }

    /**
     * 获取问卷目录。
     *
     * @return 问卷根目录。
     */
    public File getQuestionnairesPath() {
        return this.questionnairesPath;
    }

    /**
     * 列出量表文件。
     *
     * @return 文件列表；目录不存在时为空列表。
     */
    public List<File> listScaleFiles() {
        List<File> result = new ArrayList<>();
        File[] files = this.questionnairesPath.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return (!name.startsWith("_") && name.endsWith(".json"));
            }
        });
        if (null == files) {
            return result;
        }

        for (File file : files) {
            result.add(file);
        }
        return result;
    }

    /**
     * 列出联系人可用的量表。
     *
     * @param contactId 联系人 ID 。
     * @return 量表列表；无可用量表时为空列表。
     */
    public List<Scale> listScales(long contactId) {
        List<Scale> result = new ArrayList<>();
        List<File> files = this.listScaleFiles();
        for (File file : files) {
            try {
                Scale scale = new Scale(file, contactId);
                result.add(scale);
                // 量表名对应文件
                this.scaleNameFileMap.put(scale.name, file);
            } catch (Exception e) {
                Logger.w(this.getClass(), "#listScales - File format error: " + file.getAbsolutePath(), e);
            }
        }
        return result;
    }

    /**
     * 按名称加载量表。
     *
     * @param name      量表名称。
     * @param contactId 联系人 ID 。
     * @return 量表；不存在返回 {@code null} 。
     */
    public Scale loadScaleByName(String name, long contactId) {
        File file = this.scaleNameFileMap.get(name);
        if (null == file) {
            List<Scale> list = this.listScales(contactId);
            for (Scale scale : list) {
                if (scale.name.equalsIgnoreCase(name)) {
                    return scale;
                }
            }
        }
        else {
            try {
                return new Scale(file, contactId);
            } catch (Exception e) {
                Logger.w(this.getClass(), "#loadScaleByName - File error: " + file.getAbsolutePath(), e);
            }
        }

        return null;
    }

    /**
     * 按文件名加载量表。
     *
     * @param filename  文件名。
     * @param contactId 联系人 ID 。
     * @return 量表；不存在返回 {@code null} 。
     */
    public Scale loadScaleByFilename(String filename, long contactId) {
        File file = new File(this.questionnairesPath, filename.endsWith(".json") ? filename : filename + ".json");
        if (!file.exists()) {
            Logger.w(this.getClass(), "#loadScaleByFilename - Can NOT find file: " + file.getAbsolutePath());
            return null;
        }
        try {
            Scale scale = new Scale(file, contactId);
            this.scaleNameFileMap.put(scale.name, file);
            return scale;
        } catch (Exception e) {
            Logger.w(this.getClass(), "#loadScaleByFilename - File format error: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 加载数据集。
     *
     * @return 数据集；加载失败返回 {@code null} 。
     */
    public Dataset loadDataset() {
        if (this.datasetFile.exists()) {
            if (this.datasetFile.lastModified() != this.datasetFileModified) {
                this.datasetFileModified = this.datasetFile.lastModified();

                Logger.i(this.getClass(), "Read dataset file: " + this.datasetFile.getAbsolutePath());

                try {
                    byte[] data = Files.readAllBytes(Paths.get(this.datasetFile.getAbsolutePath()));
                    this.dataset = new Dataset(new JSONArray(new String(data, StandardCharsets.UTF_8)));

                    // 预处理
                    for (String question : this.dataset.getQuestions()) {
                        List<String> keywords = this.tokenizer.analyze(question, 7);
                        // 填充问题关键词
                        this.dataset.fillQuestionKeywords(question, keywords.toArray(new String[0]),
                                tokenizer.segment(TextUtils.filterPunctuation(question)).toArray(new String[0]));
                    }
                } catch (Exception e) {
                    Logger.e(this.getClass(), "#loadDataset", e);
                }
            }
        }

        return this.dataset;
    }

    /**
     * 加载注意力脚本。
     *
     * @return 脚本文本；文件缺失时返回空字符串。
     */
    public String loadAttentionScript() {
        if (this.attentionScriptFile.exists()) {
            if (this.attentionScriptFileModified != this.attentionScriptFile.lastModified()) {
                try {
                    byte[] data = Files.readAllBytes(Paths.get(this.attentionScriptFile.getAbsolutePath()));
                    this.attentionScriptContent = new String(data, StandardCharsets.UTF_8);
                    this.attentionScriptFileModified = this.attentionScriptFile.lastModified();
                } catch (Exception e) {
                    Logger.e(this.getClass(), "#loadAttentionScript", e);
                }
            }
        }

        return this.attentionScriptContent;
    }

    /**
     * 加载建议脚本。
     *
     * @return 脚本文本；文件缺失时返回空字符串。
     */
    public String loadSuggestionScript() {
        if (this.suggestionScriptFile.exists()) {
            if (this.suggestionScriptFileModified != this.suggestionScriptFile.lastModified()) {
                try {
                    byte[] data = Files.readAllBytes(Paths.get(this.suggestionScriptFile.getAbsolutePath()));
                    this.suggestionScriptFileContent = new String(data, StandardCharsets.UTF_8);
                    this.suggestionScriptFileModified = this.suggestionScriptFile.lastModified();
                } catch (Exception e) {
                    Logger.e(this.getClass(), "#loadSuggestionScript", e);
                }
            }
        }

        return this.suggestionScriptFileContent;
    }

    /**
     * 获取语料文本。
     *
     * @param category 类别。
     * @param content  内容标识。
     * @return 语料；未找到返回 {@code null} 。
     */
    public String getCorpus(String category, String content) {
        return this.getCorpus(category, content, Language.Chinese);
    }

    /**
     * 按语言获取语料文本。
     *
     * @param category 类别。
     * @param content  内容标识。
     * @param language 语言。
     * @return 语料；未找到返回 {@code null} 。
     */
    public String getCorpus(String category, String content, Language language) {
        if (this.corpusFile.exists()) {
            if (this.corpusFile.lastModified() != this.corpusLastModified) {
                try {
                    byte[] data = Files.readAllBytes(Paths.get(this.corpusFile.getAbsolutePath()));
                    this.corpusJson = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    this.corpusLastModified = this.corpusFile.lastModified();
                } catch (Exception e) {
                    Logger.e(this.getClass(), "#getCorpus", e);
                }
            }
        }
        if (null == this.corpusJson) {
            return "";
        }
        JSONObject categoryJson = this.corpusJson.getJSONObject(category);
        JSONObject contentJson = categoryJson.getJSONObject(content);
        return contentJson.getString(language.simplified);
    }

    /**
     * 获取策略内容。
     *
     * @param name 策略内容。
     * @return 策略内容。
     */
    public String getStrategyContent(String name) {
        String path = "assets/psychology/strategies/";
        String filename = name.endsWith(".md") ? name : (name + ".md");
        File file = new File(path, filename);
        try {
            if (file.exists()) {
                byte[] data = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
                return new String(data, StandardCharsets.UTF_8);
            }
            else {
                return null;
            }
        } catch (Exception e) {
            Logger.e(this.getClass(), "#getStrategyContent", e);
            return null;
        }
    }


}
