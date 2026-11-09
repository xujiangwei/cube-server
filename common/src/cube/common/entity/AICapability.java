/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 能力集，描述一个 AIGC 单元对外声明的能力。
 *
 * <p>能力由名称、任务类别、版本、有序的子任务列表和描述组成。子任务列表的
 * 首项是主子任务，参与 {@code AIGCUnit.makeQueryKey} 拼装单元查询键，
 * 因此首项决定单元身份；专项检测（{@code SpecializedDetection}）等能力
 * 还会复用后续项承载已启用的 profile，不新增能力扩展字段。</p>
 *
 * <p>子任务列表、兼容层能力声明和用量协议版本使用 {@code volatile} 字段并整体
 * 替换，读取时返回防御性副本或不可变列表，使同一连接重新注册时可以原地刷新
 * 能力而不必更换 Unit 对象，也不会把调用方的可变集合暴露进内部状态。</p>
 *
 * <p>JSON 序列化兼容旧节点：只有一个子任务时写单数字段 {@code subtask}，
 * 否则写数组字段 {@code subtasks}；反序列化优先读取 {@code subtask}。
 * 未声明的可选字段不会写入 JSON，以便旧版本单元解析。</p>
 */
public class AICapability implements JSONable {

    /**
     * 多模态任务类别，对应 {@link Multimodal} 中登记的子任务名称。
     */
    public final static String MultimodalTask = "Multimodal";

    /**
     * 自然语言处理任务类别，对应 {@link NaturalLanguageProcessing} 中登记的子任务名称。
     */
    public final static String NaturalLanguageProcessingTask = "NaturalLanguageProcessing";

    /**
     * 计算机视觉任务类别，对应 {@link ComputerVision} 中登记的子任务名称。
     */
    public final static String ComputerVisionTask = "ComputerVision";

    /**
     * 音频处理任务类别，对应 {@link AudioProcessing} 中登记的子任务名称。
     */
    public final static String AudioProcessingTask = "AudioProcessing";

    /**
     * 数据处理任务类别，对应 {@link DataProcessing} 中登记的子任务名称。
     */
    public final static String DataProcessingTask = "DataProcessing";

    /**
     * 其他处理任务类别，对应 {@link OtherProcessing} 中登记的子任务名称。
     */
    public final static String OtherProcessingTask = "OtherProcessing";

    /**
     * 能力名称，例如 {@code SpecializedDetection}；构造后不可变，参与单元查询键。
     */
    private final String name;

    /**
     * 任务类别，取值为本类声明的任务类别常量之一；构造后不可变。
     */
    private final String task;

    /**
     * 能力版本号；构造后不可变，缺省版本为 {@code 1.0}。
     */
    private final String version;

    /**
     * 有序的子任务列表，首项为主子任务。
     *
     * <p>以不可变列表整体替换的方式更新，避免重新注册时改变单元查询键，
     * 也避免把调用方的可变列表暴露给其他线程。</p>
     */
    private volatile List<String> subtasks;

    /**
     * 能力描述；构造后不可变，参与单元查询键。
     */
    private final String description;

    /**
     * OpenAI / Claude 兼容层的协议能力声明。
     *
     * <p>旧版本单元不声明该字段，默认是空对象。声明内容由
     * {@code CompletionRequest.checkCapabilities} 校验，例如 {@code text}、
     * {@code stream}、{@code images}、{@code tools}、{@code parameters}；
     * 空对象表示该单元不支持结构化对话。</p>
     */
    private volatile JSONObject chatCompletion = new JSONObject();

    /**
     * 用量上报协议版本，0 表示旧版单元未声明该协议。
     *
     * <p>需要按新协议上报用量的场景会据此判断单元是否必须升级。</p>
     */
    private volatile int usageProtocol;

    /**
     * 获取用量上报协议版本。
     *
     * @return 协议版本号，0 表示单元未声明。
     */
    public int getUsageProtocol() {
        return usageProtocol;
    }

    /**
     * 设置用量上报协议版本。
     *
     * @param version 协议版本号，0 表示单元未声明。
     */
    public void setUsageProtocol(int version) {
        usageProtocol = version;
    }

    /**
     * 使用完整的能力字段构造能力集。
     *
     * <p>子任务列表会被复制为不可变副本，调用方后续修改传入的列表不影响本对象。</p>
     *
     * @param name 能力名称。
     * @param task 任务类别。
     * @param version 能力版本号。
     * @param subtasks 有序的子任务列表，首项为主子任务。
     * @param description 能力描述。
     */
    public AICapability(String name, String task, String version, List<String> subtasks, String description) {
        this.name = name;
        this.task = task;
        this.version = version;
        setSubtasks(subtasks);
        this.description = description;
    }

    /**
     * 从 JSON 结构还原能力集。
     *
     * <p>兼容旧节点的单数字段 {@code subtask}：存在该字段时优先按单个子任务读取，
     * 否则读取数组字段 {@code subtasks}，两者都不存在时子任务列表为空。
     * 缺省版本为 {@code 1.0}，缺省用量协议版本为 0，未声明的
     * {@code chatCompletion} 保持空对象；声明对象会被复制，不与入参共享引用。</p>
     *
     * @param json 能力的 JSON 表示，需包含 {@code name}、{@code task} 和 {@code description}。
     */
    public AICapability(JSONObject json) {
        this.name = json.getString("name");
        this.task = json.getString("task");
        this.version = json.has("version") ? json.getString("version") : "1.0";

        this.subtasks = new ArrayList<>();
        if (json.has("subtask")) {
            this.subtasks.add(json.getString("subtask"));
        } else if (json.has("subtasks")) {
            JSONArray array = json.getJSONArray("subtasks");
            for (int i = 0; i < array.length(); ++i) {
                this.subtasks.add(array.getString(i));
            }
        }

        this.description = json.getString("description");
        this.usageProtocol = json.optInt("usageProtocol", 0);
        if (json.optJSONObject("chatCompletion") != null) {
            this.chatCompletion = new JSONObject(json.getJSONObject("chatCompletion").toString());
        }
    }

    /**
     * 获取能力名称。
     *
     * @return 能力名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取任务类别。
     *
     * @return 任务类别。
     */
    public String getTask() {
        return this.task;
    }

    /**
     * 获取能力版本号。
     *
     * @return 能力版本号。
     */
    public String getVersion() {
        return this.version;
    }

    /**
     * 获取主子任务，即有序子任务列表的首项。
     *
     * <p>该值参与单元查询键的拼装，决定单元身份；子任务列表为空时调用会抛出
     * {@link IndexOutOfBoundsException}。</p>
     *
     * @return 主子任务名称。
     */
    public String getPrimarySubtask() {
        return this.subtasks.get(0);
    }

    /**
     * 判断子任务列表中是否包含指定名称。
     *
     * @param subtask 待检查的子任务名称。
     * @return 包含该子任务时返回 {@code true}。
     */
    public boolean containsSubtask(String subtask) {
        return this.subtasks.contains(subtask);
    }

    /**
     * 获取有序子任务列表的副本。
     *
     * <p>首项为主子任务并决定单元身份；专项检测等能力的后续项还可承载
     * profile 声明。返回的是新列表，调用方修改副本不会改变本对象的能力。</p>
     *
     * @return 按声明顺序排列的子任务列表副本。
     */
    public List<String> getSubtasks() {
        return new ArrayList<>(this.subtasks);
    }

    /**
     * 原子替换有序子任务列表。
     *
     * <p>复制入参并包装为不可变列表后整体替换，调用方后续修改传入的列表不影响
     * 本对象；整体替换保证重新注册时读到的始终是某一版完整声明，不会看到
     * 增删过程中的中间状态。首项保持不变才能维持单元查询键稳定。</p>
     *
     * @param values 按声明顺序排列的新子任务列表。
     */
    public void setSubtasks(List<String> values) {
        this.subtasks = Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 获取能力描述。
     *
     * @return 能力描述。
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * 获取兼容层协议能力声明的副本。
     *
     * <p>旧版本单元未声明时返回空对象；返回的是新对象，修改它不会改变本对象的声明。</p>
     *
     * @return 兼容层协议能力声明。
     */
    public JSONObject getChatCompletion() {
        return new JSONObject(this.chatCompletion.toString());
    }

    /**
     * 设置兼容层协议能力声明。
     *
     * <p>入参会被复制，不与调用方共享引用；重新注册时可用空对象清除旧声明。</p>
     *
     * @param capability 兼容层协议能力声明，例如 {@code text}、{@code stream} 等能力开关。
     */
    public void setChatCompletion(JSONObject capability) {
        this.chatCompletion = new JSONObject(capability.toString());
    }

    /**
     * 序列化为完整的 JSON 结构。
     *
     * <p>子任务列表恰好一项时写单数字段 {@code subtask}，否则写数组字段
     * {@code subtasks}（空列表写出空数组）；用量协议版本大于 0 时才写
     * {@code usageProtocol}；兼容层能力声明非空时才写 {@code chatCompletion}。</p>
     *
     * @return 能力的 JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        List<String> subtasks = this.subtasks;
        JSONObject json = new JSONObject();
        if (usageProtocol > 0) json.put("usageProtocol", usageProtocol);
        json.put("name", this.name);
        json.put("task", this.task);
        json.put("version", this.version);

        if (subtasks.size() == 1) {
            json.put("subtask", subtasks.get(0));
        } else {
            JSONArray array = new JSONArray();
            for (String subtask : subtasks) {
                array.put(subtask);
            }
            json.put("subtasks", array);
        }

        json.put("description", this.description);
        if (this.chatCompletion.length() != 0) {
            json.put("chatCompletion", getChatCompletion());
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 结构。
     *
     * <p>当前实现直接委托 {@link #toJSON()}，字段与完整序列化一致，并未裁减；
     * 保留该入口是为了满足 {@code JSONable} 的接口契约。</p>
     *
     * @return 能力的 JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }


    /**
     * 多模态任务的子任务名称常量。
     */
    public static class Multimodal {

        /**
         * 文生图。
         */
        public final static String TextToImage = "TextToImage";

        /**
         * 图生文。
         */
        public final static String ImageToText = "ImageToText";

        /**
         * 文生视频。
         */
        public final static String TextToVideo = "TextToVideo";

        /**
         * 视觉问答。
         */
        public final static String VisualQuestionAnswering = "VisualQuestionAnswering";

        /**
         * 文档视觉问答。
         */
        public final static String DocumentQuestionAnswering = "DocumentQuestionAnswering";

        /**
         * 特征提取。
         */
        public final static String FeatureExtraction = "FeatureExtraction";

        /**
         * 图文生文。
         */
        public final static String ImageTextToText = "ImageTextToText";

        /**
         * 私有构造方法，禁止实例化。
         */
        private Multimodal() {
        }
    }


    /**
     * 自然语言处理任务的子任务名称常量。
     */
    public static class NaturalLanguageProcessing {

        /**
         * 分词。
         */
        public final static String Segmentation = "Segmentation";

        /**
         * 文本分类。
         */
        public final static String TextClassification = "TextClassification";

        /**
         * 词元分类。
         */
        public final static String TokenClassification = "TokenClassification";

        /**
         * 表格问答。
         */
        public final static String TableQuestionAnswering = "TableQuestionAnswering";

        /**
         * 问答。
         */
        public final static String QuestionAnswering = "QuestionAnswering";

        /**
         * 零样本分类。
         */
        public final static String ZeroShotClassification = "ZeroShotClassification";

        /**
         * 翻译。
         */
        public final static String Translation = "Translation";

        /**
         * 文本摘要。
         */
        public final static String Summarization = "Summarization";

        /**
         * 对话生成。
         */
        public final static String Conversational = "Conversational";

        /**
         * 文本生成。
         */
        public final static String TextGeneration = "TextGeneration";

        /**
         * 文本到文本生成。
         */
        public final static String Text2TextGeneration = "Text2TextGeneration";

        /**
         * 掩码填充。
         */
        public final static String FillMask = "FillMask";

        /**
         * 句子相似度。
         */
        public final static String SentenceSimilarity = "SentenceSimilarity";

        /**
         * 情感分析。
         */
        public final static String SentimentAnalysis = "SentimentAnalysis";

        /**
         * 关键词抽取。
         */
        public final static String ExtractKeywords = "ExtractKeywords";

        /**
         * 多任务处理。
         */
        public final static String MultiTask = "MultiTask";

        /**
         * 知识理解。
         */
        public final static String KnowledgeComprehension = "KnowledgeComprehension";

        /**
         * 语义检索。
         */
        public final static String SemanticSearch = "SemanticSearch";

        /**
         * 同义句挖掘。
         */
        public final static String ParaphraseMining = "ParaphraseMining";

        /**
         * 检索重排序。
         */
        public final static String RetrieveReRank = "RetrieveReRank";

        /**
         * 私有构造方法，禁止实例化。
         */
        private NaturalLanguageProcessing() {
        }
    }


    /**
     * 计算机视觉任务的子任务名称常量。
     */
    public static class ComputerVision {

        /**
         * 深度估计。
         */
        public final static String DepthEstimation = "DepthEstimation";

        /**
         * 图像分类。
         */
        public final static String ImageClassification = "ImageClassification";

        /**
         * 目标检测。
         */
        public final static String ObjectDetection = "ObjectDetection";

        /**
         * 图像分割。
         */
        public final static String ImageSegmentation = "ImageSegmentation";

        /**
         * 图生图。
         */
        public final static String ImageToImage = "ImageToImage";

        /**
         * 无条件图像生成。
         */
        public final static String UnconditionalImageGeneration = "UnconditionalImageGeneration";

        /**
         * 视频分类。
         */
        public final static String VideoClassification = "VideoClassification";

        /**
         * 零样本图像分类。
         */
        public final static String ZeroShotImageClassification = "ZeroShotImageClassification";

        /**
         * 面部表情识别。
         */
        public final static String FacialExpressionRecognition = "FacialExpressionRecognition";

        /**
         * 姿态估计。
         */
        public final static String PoseEstimation = "PoseEstimation";

        /**
         * 私有构造方法，禁止实例化。
         */
        private ComputerVision() {
        }
    }


    /**
     * 音频处理任务的子任务名称常量。
     */
    public static class AudioProcessing {

        /**
         * 文本转语音。
         */
        public final static String TextToSpeech = "TextToSpeech";

        /**
         * 自动语音识别。
         */
        public final static String AutomaticSpeechRecognition = "AutomaticSpeechRecognition";

        /**
         * 音频到音频转换。
         */
        public final static String AudioToAudio = "AudioToAudio";

        /**
         * 音频分类。
         */
        public final static String AudioClassification = "AudioClassification";

        /**
         * 语音活动检测。
         */
        public final static String VoiceActivityDetection = "VoiceActivityDetection";

        /**
         * 说话人验证。
         */
        public final static String SpeakerVerification = "SpeakerVerification";

        /**
         * 说话人识别。
         */
        public final static String SpeakerIdentification = "SpeakerIdentification";

        /**
         * 说话人分割聚类、说话人分离。
         */
        public final static String SpeakerDiarization = "SpeakerDiarization";

        /**
         * 私有构造方法，禁止实例化。
         */
        private AudioProcessing() {
        }
    }


    /**
     * 数据处理任务的子任务名称常量。
     */
    public static class DataProcessing {

        /**
         * 抽取 URL 内容。
         */
        public final static String ExtractURLContent = "ExtractURLContent";

        /**
         * 私有构造方法，禁止实例化。
         */
        private DataProcessing() {
        }
    }

    /**
     * 心理处理相关的子任务名称常量。
     */
    public static class PsychologyProcessing {

        /**
         * 生成绘画模板文章。
         */
        public final static String GeneratePaintingTemplateArticle = "GeneratePaintingTemplateArticle";

        /**
         * 私有构造方法，禁止实例化。
         */
        private PsychologyProcessing() {
        }
    }

    /**
     * 其他处理任务的子任务名称常量。
     */
    public static class OtherProcessing {

        /**
         * 心理学。
         */
        public final static String Psychology = "Psychology";

        /**
         * 私有构造方法，禁止实例化。
         */
        private OtherProcessing() {
        }
    }
}
