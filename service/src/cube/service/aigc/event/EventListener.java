/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.event;

/**
 * 事件监听器。
 *
 * <p>由事件中心在收到对应事件时回调，实现方应按事件名称区分处理逻辑。</p>
 */
public interface EventListener {

    void onEvent(EventCenter center, Event event);
}
