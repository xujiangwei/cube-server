/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.resource;

import cube.aigc.complex.attachment.CardAttachment;
import cube.aigc.complex.attachment.ThingAttachment;
import cube.aigc.complex.widget.Button;

/**
 * 附件构建器。
 */
public class AttachmentBuilder {

    public AttachmentBuilder() {
    }

    /**
     * 构建卡片附件。
     *
     * <p>当前未实现，固定返回 {@code null} 。</p>
     *
     * @param content  卡片内容。
     * @param okButton 确认按钮。
     * @return 固定返回 {@code null} 。
     */
    public CardAttachment buildCard(String content, Button okButton) {
        return null;
    }

    /**
     * 构建物附件。
     *
     * @param content    展示内容。
     * @param viewButton 查看按钮。
     * @return 物附件。
     */
    public ThingAttachment buildThing(String content, Button viewButton) {
        ThingAttachment attachment = new ThingAttachment(content);
        attachment.addActionButton(viewButton);
        return attachment;
    }
}
