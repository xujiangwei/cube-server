/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 联系人辅助函数。
 */
public class ContactHelper {

    private ContactHelper() {
    }

    /**
     * 按 JSON 构造联系人对象。
     *
     * <p>根据数据中的类型标记决定构造群组还是普通联系人。</p>
     *
     * @param data 联系人 JSON 。
     * @return 联系人对象；数据不合法返回 {@code null} 。
     */
    public static AbstractContact create(JSONObject data) {
        if (Group.isGroup(data)) {
            return new Group(data);
        }
        else if (AnonymousContact.isAnonymous(data)) {
            return new AnonymousContact(data);
        }
        else {
            return new Contact(data);
        }
    }
}
