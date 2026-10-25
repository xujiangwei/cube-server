/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.mq;

import cell.adapter.CelletAdapter;
import cube.core.AbstractMQ;
import cube.core.MQMessage;
import cube.core.MQTopic;
import org.json.JSONObject;

/**
 * 基于适配器实现的 MQ 。
 *
 * @deprecated 占位实现：{@code start} / {@code stop} / {@code publish} / {@code subscribe}
 * 全部为空方法，装上之后不会产生任何实际行为，调用方也无法感知失效。
 * 仓库内没有任何装配调用点（{@code Kernel#installMQ} 亦无调用方），保留仅为二进制兼容。
 * 若将来恢复 MQ 能力，应实现完整语义后去掉本标注，而不是继续复用空实现。
 */
@Deprecated
public class AdapterMQ extends AbstractMQ {

    public final static String TYPE = "AMQ";

    private String name;

    private JSONObject config;

    private CelletAdapter adapter;

    public AdapterMQ(String name) {
        super(name);
    }

    @Override
    public void configure(JSONObject config) {
        super.configure(config);
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void publish(MQTopic topic, MQMessage json) {

    }

    @Override
    public void subscribe(MQTopic topic) {

    }
}
