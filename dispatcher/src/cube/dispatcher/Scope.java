/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务节点作用域。
 */
public class Scope {

    protected List<String> cellets;

    public Scope() {
        this.cellets = new ArrayList<>();
    }
}
