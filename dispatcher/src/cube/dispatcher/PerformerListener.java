/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

import cell.api.Speakable;
import cell.core.talk.Primitive;
import cell.core.talk.PrimitiveInputStream;

import java.io.IOException;

/**
 * 执行器监听器。
 */
public interface PerformerListener {

    void onReceived(String cellet, Primitive primitive);

    default void onReceivedStream(Speakable source, String cellet, PrimitiveInputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    default void onDisconnected(Speakable source) {
    }
}
