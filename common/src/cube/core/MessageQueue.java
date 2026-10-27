/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import org.json.JSONObject;

/**
 * 消息队列接口。
 */
public interface MessageQueue {

    /**
     * 返回缓存名称。
     *
     * @return 返回缓存名称。
     */
    String getName();

    /**
     * 返回配置数据。
     *
     * @return 返回配置数据。
     */
    JSONObject getConfig();

    /**
     * 按配置初始化消息队列。
     *
     * @param config 配置项。
     */
    void configure(JSONObject config);

    /**
     * 启动消息队列。
     */
    void start();

    /**
     * 停止消息队列并释放连接。
     */
    void stop();

    /**
     * 发布消息。
     *
     * @param topic 主题。
     * @param json  消息体。
     */
    void publish(MQTopic topic, MQMessage json);

    /**
     * 订阅主题。
     *
     * @param topic 主题。
     */
    void subscribe(MQTopic topic);

}
