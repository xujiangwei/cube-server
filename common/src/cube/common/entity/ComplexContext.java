/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cell.util.log.Logger;
import cube.aigc.Page;
import cube.aigc.psychology.composition.Subtask;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 复合会话内容。
 */
public class ComplexContext extends Entity {

    private final boolean simplex;

    private PromptTemplate promptTemplate;

    private String strategy;

    private String subtask;

    private final List<ComplexResource> resources;

    private boolean inferable = false;

    private final AtomicBoolean inferring = new AtomicBoolean(false);

    private List<String> inferenceResult;

    private boolean networking = false;
    private boolean networkingInferEnd = false;
    private List<Page> networkingPages;
    private String networkingResult;

    public ComplexContext() {
        super(Utils.generateSerialNumber());
        this.simplex = true;
        this.resources = new ArrayList<>();
    }

    public ComplexContext(boolean simplex) {
        super(Utils.generateSerialNumber());
        this.simplex = simplex;
        this.resources = new ArrayList<>();
    }

    public ComplexContext(JSONObject json) {
        super(json);
        this.simplex = json.has("type") ?
                parseSimplex(json.getString("type")) : json.getBoolean("simplex");

        if (json.has("promptTemplate")) {
            this.promptTemplate = new PromptTemplate(json.getJSONObject("promptTemplate"));
        }

        if (json.has("strategy")) {
            this.strategy = json.getString("strategy");
        }

        this.resources = new ArrayList<>();
        if (json.has("resources")) {
            JSONArray array = json.getJSONArray("resources");
            for (int i = 0; i < array.length(); ++i) {
                JSONObject data = array.getJSONObject(i);
                String subject = data.getString("subject");
                if (subject.equalsIgnoreCase(ComplexResource.Subject.File.name())) {
                    this.resources.add(new FileResource(data));
                }
                else if (subject.equalsIgnoreCase(ComplexResource.Subject.Attachment.name())) {
                    this.resources.add(new AttachmentResource(data));
                }
                else if (subject.equalsIgnoreCase(ComplexResource.Subject.Widget.name())) {
                    this.resources.add(new WidgetResource(data));
                }
                else if (subject.equalsIgnoreCase(ComplexResource.Subject.Hyperlink.name())) {
                    this.resources.add(new HyperlinkResource(data));
                }
                else if (subject.equalsIgnoreCase(ComplexResource.Subject.Chart.name())) {
                    this.resources.add(new ChartResource(data));
                }
                else {
                    Logger.e(this.getClass(), "Unknown complex context resource subject: " + subject);
                }
            }
        }

        if (json.has("inferable")) {
            this.inferable = json.getBoolean("inferable");
        }
        if (json.has("inferring")) {
            this.inferring.set(json.getBoolean("inferring"));
        }

        if (json.has("inferenceResult")) {
            this.inferenceResult = new ArrayList<>();
            JSONArray list = json.getJSONArray("inferenceResult");
            for (int i = 0; i < list.length(); ++i) {
                this.inferenceResult.add(list.getString(i));
            }
        }

        if (json.has("networking")) {
            this.networking = json.getBoolean("networking");
        }
        if (json.has("networkingInferEnd")) {
            this.networkingInferEnd = json.getBoolean("networkingInferEnd");
        }
        if (json.has("networkingPages")) {
            this.networkingPages = new ArrayList<>();
            JSONArray list = json.getJSONArray("networkingPages");
            for (int i = 0; i < list.length(); ++i) {
                this.networkingPages.add(new Page(list.getJSONObject(i)));
            }
        }
        if (json.has("networkingResult")) {
            this.networkingResult = json.getString("networkingResult");
        }

        if (json.has("subtask")) {
            this.subtask = json.getString("subtask");
        }
    }

    /**
     * 判断上下文是否为轻量模式。
     *
     * <p>轻量模式跳过复杂推理链路，只做直接应答。</p>
     *
     * @return 轻量模式返回 {@code true} 。
     */
    public boolean isSimplified() {
        return this.simplex;
    }

    /**
     * 获取提示词模板。
     *
     * <p>模板决定本次会话的指令骨架，可能为 {@code null} ，此时使用单元默认模板。</p>
     *
     * @return 提示词模板。
     */
    public PromptTemplate getPromptTemplate() {
        return this.promptTemplate;
    }

    /**
     * 获取策略标识。
     *
     * <p>策略名由业务层定义，用于在同类上下文中选择不同的处理路径。</p>
     *
     * @return 策略标识；未设置时为 {@code null} 。
     */
    public String getStrategy() {
        return this.strategy;
    }

    /**
     * 设置当前子任务。
     *
     * <p>只保存子任务名称，不持有子任务实例。</p>
     *
     * @param subtask 子任务。
     */
    public void setSubtask(Subtask subtask) {
        this.subtask = subtask.name;
    }

    /**
     * 统计上下文携带的资源数量。
     *
     * @return 资源个数。
     */
    public int numResources() {
        return this.resources.size();
    }

    /**
     * 判断是否携带指定类别的资源。
     *
     * @param subject 资源类别。
     * @return 存在该类资源时返回 {@code true} 。
     */
    public boolean hasResource(ComplexResource.Subject subject) {
        for (ComplexResource res : this.resources) {
            if (res.subject == subject) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取首个资源。
     *
     * <p>只应在确定至少有一个资源时调用，否则会抛出下标越界异常。</p>
     *
     * @return 第一个资源。
     */
    public ComplexResource getResource() {
        return this.resources.get(0);
    }

    /**
     * 获取首个文件资源。
     *
     * @return 文件资源；不存在时返回 {@code null} 。
     */
    public FileResource getFileResource() {
        for (ComplexResource res : this.resources) {
            if (res instanceof FileResource) {
                return (FileResource) res;
            }
        }
        return null;
    }

    /**
     * 获取首个附件资源。
     *
     * @return 附件资源；不存在时返回 {@code null} 。
     */
    public AttachmentResource getAttachmentResource() {
        for (ComplexResource res : this.resources) {
            if (res instanceof AttachmentResource) {
                return (AttachmentResource) res;
            }
        }
        return null;
    }

    /**
     * 获取全部资源。
     *
     * <p>返回内部列表本身，调用方不应直接修改。</p>
     *
     * @return 资源列表。
     */
    public List<ComplexResource> getResources() {
        return this.resources;
    }

    /**
     * 追加一个资源。
     *
     * @param resource 待追加的资源。
     */
    public void addResource(ComplexResource resource) {
        this.resources.add(resource);
    }

    /**
     * 标记上下文是否可推理。
     *
     * @param value 为 {@code true} 表示允许走推理链路。
     */
    public void setInferable(boolean value) {
        this.inferable = value;
    }

    /**
     * 判断上下文是否可推理。
     *
     * @return 允许推理返回 {@code true} 。
     */
    public boolean isInferable() {
        return this.inferable;
    }

    /**
     * 标记上下文是否正在推理。
     *
     * <p>该标记用于防止同一上下文被并发重复推理。</p>
     *
     * @param value 为 {@code true} 表示推理进行中。
     */
    public void setInferring(boolean value) {
        this.inferring.set(value);
    }

    /**
     * 判断上下文是否正在推理。
     *
     * @return 推理进行中返回 {@code true} 。
     */
    public boolean isInferring(){
        return this.inferring.get();
    }

    /**
     * 追加一条推理结果。
     *
     * <p>首次调用时惰性创建结果列表；方法加锁，允许推理线程并发写入。</p>
     *
     * @param result 推理产生的文本片段。
     */
    public synchronized void addInferenceResult(String result) {
        if (null == this.inferenceResult) {
            this.inferenceResult = new ArrayList<>();
        }

        this.inferenceResult.add(result);
    }

    /**
     * 获取推理结果。
     *
     * @return 推理结果片段列表；尚无结果时为 {@code null} 。
     */
    public List<String> getInferenceResult() {
        return this.inferenceResult;
    }

    /**
     * 设置是否启用联网检索。
     *
     * @param networking 为 {@code true} 表示本次请求需要联网补充信息。
     */
    public void setNetworking(boolean networking) {
        this.networking = networking;
    }

    /**
     * 回填联网检索结果。
     *
     * <p>置位检索结束标记，并累积检索到的页面与摘要文本。
     * 多次调用会持续累加，用于多轮检索。</p>
     *
     * @param pages  检索到的页面，允许为 {@code null} 。
     * @param result 检索摘要，允许为 {@code null} 。
     */
    public void fixNetworkingResult(List<Page> pages, String result) {
        this.networkingInferEnd = true;

        if (null != pages) {
            if (null == this.networkingPages) {
                this.networkingPages = new ArrayList<>();
            }
            this.networkingPages.addAll(pages);
        }
        if (null != result) {
            this.networkingResult = result;
        }
    }

    private boolean parseSimplex(String value) {
        return (value.equalsIgnoreCase("lightweight") || value.equalsIgnoreCase("simplex"));
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 上下文 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("simplex", this.simplex);

        if (null != this.promptTemplate) {
            json.put("promptTemplate", this.promptTemplate.toJSON());
        }

        if (null != this.strategy) {
            json.put("strategy", this.strategy);
        }

        JSONArray array = new JSONArray();
        for (ComplexResource resource : this.resources) {
            array.put(resource.toJSON());
        }
        json.put("resources", array);

        json.put("inferable", this.inferable);
        json.put("inferring", this.inferring.get());

        if (null != this.inferenceResult) {
            JSONArray list = new JSONArray();
            for (String result : this.inferenceResult) {
                list.put(result);
            }
            json.put("inferenceResult", list);
        }

        json.put("networking", this.networking);
        json.put("networkingInferEnd", this.networkingInferEnd);

        if (null != this.networkingPages) {
            JSONArray list = new JSONArray();
            for (Page page : this.networkingPages) {
                list.put(page.toCompactJSON());
            }
            json.put("networkingPages", list);
        }

        if (null != this.networkingResult) {
            json.put("networkingResult", this.networkingResult);
        }

        if (null != this.subtask) {
            json.put("subtask", this.subtask);
        }

        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 上下文 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        if (json.has("inferable")) {
            json.remove("inferable");
            json.remove("inferring");
        }
        if (json.has("inferenceResult")) {
            json.remove("inferenceResult");
        }
        if (json.has("networkingPages")) {
            json.remove("networkingPages");
        }
        return json;
    }
}
