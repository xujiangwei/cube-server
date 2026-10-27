/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 链条节点。
 */
public class ChainNode extends Entity {

    /**
     * 操作事件。
     */
    private String event;

    /**
     * 操作人。
     */
    private AbstractContact who;

    /**
     * 被操作的实体。
     */
    private FileLabel what;

    /**
     * 操作时间。
     */
    private long when;

    /**
     * 做了什么操作方式。
     */
    private TransmissionMethod method;

    /**
     * 追踪轨道。
     */
    private List<String> tracks;

    /**
     * 相邻前向节点。
     */
    private ChainNode previous;

    /**
     * 相邻后向节点。
     */
    private ChainNode next;

    /**
     * 子节点。
     */
    private List<ChainNode> children;

    /**
     * 数量。
     */
    private int total;

    /**
     * 访问追踪。
     */
    private VisitTrace visitTrace;

    public ChainNode(Long id, String domain, String event, AbstractContact who, FileLabel what, long when) {
        super(id, domain);
        this.event = event;
        this.who = who;
        this.what = what;
        this.when = when;
        this.tracks = new ArrayList<>();
    }

    public ChainNode(long id, String domain, ChainNode previous, ChainNode next) {
        super(id, domain);
        this.previous = previous;
        this.next = next;
    }

    public ChainNode(long id, String domain, String event) {
        super(id, domain);
        this.event = event;

    }

    public ChainNode(VisitTrace visitTrace) {
        super();
        this.visitTrace = visitTrace;
        this.event = visitTrace.event;
        this.total = 1;
    }

    /** 获取节点事件名称。 */
    public String getEvent() {
        return this.event;
    }

    /**
     * 获取事件主体。
     *
     * <p>可能是联系人或设备，取决于事件类型。</p>
     */
    public AbstractContact getWho() {
        return this.who;
    }

    /** 获取事件关联的文件。 */
    public FileLabel getWhat() {
        return this.what;
    }

    /** 获取事件发生时间。 */
    public long getWhen() {
        return this.when;
    }

    /**
     * 追加一条传播路径标记。
     *
     * <p>相同标记只保留一次，避免路径重复。</p>
     */
    public void addTrack(String track) {
        if (this.tracks.contains(track)) {
            return;
        }

        this.tracks.add(track);
    }

    /** 获取传播路径标记列表。 */
    public List<String> getTracks() {
        return this.tracks;
    }

    /** 获取传播方式。 */
    public TransmissionMethod getMethod() {
        return this.method;
    }

    /** 设置传播方式。 */
    public void setMethod(TransmissionMethod method) {
        this.method = method;
    }

    /** 批量追加子节点。 */
    public void addChildren(List<ChainNode> list) {
        if (null == this.children) {
            this.children = new ArrayList<>();
        }

        this.children.addAll(list);
    }

    /** 追加单个子节点。 */
    public void addChild(ChainNode node) {
        if (null == this.children) {
            this.children = new ArrayList<>();
        }

        this.children.add(node);
    }

    /**
     * 获取子节点列表。
     *
     * @return 子节点列表；无子节点时返回空列表。
     */
    public List<ChainNode> getChildren() {
        if (null == this.children) {
            this.children = new ArrayList<>();
        }

        return this.children;
    }

    /**
     * 累加访问计数。
     *
     * @return 累加后的计数。
     */
    public int incrementTotal() {
        return ++this.total;
    }

    /** 获取访问轨迹。 */
    public VisitTrace getVisitTrace() {
        return this.visitTrace;
    }

    /** 按 ID 判断节点是否相同。 */
    @Override
    public boolean equals(Object o) {
        if (o instanceof ChainNode) {
            ChainNode other = (ChainNode) o;
            if (other.id.equals(this.id)) {
                return true;
            }
        }

        return false;
    }

    /** 序列化为完整 JSON 。 */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("event", this.event);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>只输出节点标识与关键字段，不递归子节点。</p>
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = new JSONObject();
        json.put("event", this.event);
        json.put("total", this.total);

        if (null != this.visitTrace) {
            json.put("visitTrace", this.visitTrace.toMiniJSON());
        }

        JSONArray array = new JSONArray();
        if (null != this.children) {
            for (ChainNode child : this.children) {
                array.put(child.toCompactJSON());
            }
        }
        json.put("children", array);

        return json;
    }
}
