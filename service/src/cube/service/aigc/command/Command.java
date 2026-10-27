/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.command;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 命令抽象基类。
 *
 * <p>命令可被执行器调度并序列化为 JSON 。</p>
 *
 * @deprecated 命令体系已不再维护，新代码请直接调用对应服务接口。
 */
public abstract class Command implements JSONable, Runnable {

    /** 命令名称，用于在注册表中检索与日志标识。 */
    public final String name;

    public Command(String name) {
        this.name = name;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>默认与完整 JSON 一致，子类可覆盖以裁剪字段。</p>
     *
     * @return 精简后的 JSON 。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
