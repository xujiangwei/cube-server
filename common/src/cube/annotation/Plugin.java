/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
/**
 * 插件声明注解。
 *
 * <p>标注在插件实现类上，运行时由插件管理器扫描并注册。</p>
 */
public @interface Plugin {

    String targetModule();
}
