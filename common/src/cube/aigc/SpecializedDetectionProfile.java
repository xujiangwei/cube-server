/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc;

/**
 * 已知检测配置（Profile）的目录清单，后续新增检测场景在此登记即可。
 *
 * <p>本枚举只是命名目录，既不是提供方的能力声明，也不是请求白名单：
 * 某个配置是否受支持由 Python Unit 判定，并通过对应的状态码返回结果。</p>
 */
public enum SpecializedDetectionProfile {
    /**
     * 地面视角的烟火检测。
     */
    FireSmoke("fire-smoke"),
    /**
     * 无人机视角的烟火检测，可选启用切片推理。
     */
    UavFireSmoke("uav-fire-smoke");

    /**
     * 配置名称，与 Unit 交互时用于标识具体的检测场景。
     */
    public final String name;

    /**
     * 构造方法。
     *
     * @param name 配置名称
     */
    SpecializedDetectionProfile(String name) {
        this.name = name;
    }
}
