/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.report;

import cell.core.talk.TalkContext;
import cube.common.entity.AICapability;
import cube.common.entity.AIGCUnit;
import cube.common.entity.Contact;
import cube.common.entity.Device;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 单元能力报告。
 *
 * <p>节点上运行的 AIGC 单元以 {@link Contact} 为物理实体：同一个 Contact 可以注册多个
 * {@code AIGCUnit}（{@code AIGCService#setupUnit} 按能力逐个建档），每个单元承载一个
 * {@link AICapability}。因此本报告的条目是「物理实体 → 该实体承载的一个能力」，
 * 控制台按物理实体归并后即可得到「一个实体对应一个或多个能力」的台账视图。</p>
 *
 * <p>单元能力只存在于 service 进程内存里（{@code AIGCService#unitMap}），控制台没有其它
 * 途径获取，因此复用既有的节点上报通道周期上报，控制台仅缓存最近一份快照。</p>
 */
public class UnitReport extends Report {

    /**
     * 报告名称。
     */
    public final static String NAME = "UnitReport";

    /**
     * 单份报告携带的最大条目数：上报队列上限 20 份，报文过大容易被丢弃或拖慢提交。
     */
    public final static int MAX_UNITS = 500;

    /**
     * 单元条目：<code>{id, domain, name, capability}</code>。
     * <code>id</code> 是物理实体（Contact）的 ID，<code>capability</code> 是 AICapability 的 JSON 。
     * 新版报告补充可选的 connected/address/device/platform 部署元信息。
     */
    private final List<JSONObject> units;

    private boolean truncated;

    public UnitReport(String reporter) {
        super(NAME);
        this.setReporter(reporter);
        this.units = new ArrayList<>();
    }

    public UnitReport(JSONObject json) {
        super(json);
        this.units = new ArrayList<>();
        this.truncated = json.optBoolean("truncated", false);

        JSONArray array = json.optJSONArray("units");
        if (null != array) {
            for (int i = 0; i < array.length(); ++i) {
                this.units.add(array.getJSONObject(i));
            }
        }
    }

    /**
     * 添加一个单元的物理实体与能力。
     *
     * @param contact 单元的物理实体。
     * @param capability 单元的能力。
     * @return 超过 {@link #MAX_UNITS} 上限时返回 <code>false</code>，该条目被丢弃。
     */
    public boolean addUnit(Contact contact, AICapability capability) {
        if (this.units.size() >= MAX_UNITS) {
            this.truncated = true;
            return false;
        }

        JSONObject item = new JSONObject();
        item.put("id", contact.getId());
        item.put("domain", contact.getDomain().getName());
        item.put("name", contact.getName());
        item.put("capability", capability.toJSON());
        this.units.add(item);
        return true;
    }

    /**
     * 保留原能力台账条目，并补充当前连接的部署信息。
     * connected=false 的能力仍供原台账使用，不进入当前部署列表。
     */
    public boolean addUnit(AIGCUnit unit) {
        if (!this.addUnit(unit.getContact(), unit.getCapability())) {
            return false;
        }
        JSONObject item = this.units.get(this.units.size() - 1);
        TalkContext context = unit.getContext();
        boolean connected = context != null && context.isValid();
        item.put("connected", connected);
        if (connected) {
            item.put("address", context.getSessionHost());
            Device device = unit.getContact().getDevice(context);
            if (device == null && unit.getContact().numDevices() > 0) {
                device = unit.getContact().getDevice();
            }
            if (device != null) {
                item.put("device", device.getName());
                item.put("platform", device.getPlatform());
            }
        }
        return true;
    }

    /**
     * 判断数据是否被截断。
     *
     * <p>单元数量超过上限时只保留部分数据，此时返回 {@code true} 。</p>
     *
     * @return 被截断返回 {@code true} 。
     */
    public boolean isTruncated() {
        return this.truncated;
    }

    /**
     * 当前报告里的单元条目数。
     */
    public int numUnits() {
        return this.units.size();
    }

    /**
     * 获取单元指标列表。
     *
     * @return 指标列表；无数据时为空列表。
     */
    public List<JSONObject> getUnits() {
        return this.units;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();

        JSONArray array = new JSONArray();
        for (JSONObject item : this.units) {
            array.put(item);
        }
        json.put("units", array);
        json.put("truncated", this.truncated);

        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
