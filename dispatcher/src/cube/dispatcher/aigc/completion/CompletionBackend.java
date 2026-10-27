/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 能力请求的后端抽象。
 *
 * <p>每个订阅各自持有路由与取消状态，互不影响；取消订阅即释放该路由占用的资源。</p>
 */
public interface CompletionBackend {

    interface Listener { void onEvent(JSONObject event); }

    interface Subscription { void cancel(); }

    JSONArray models(String token, long timeoutMs);

    Subscription generate(String token, String id, JSONObject request, long timeoutMs, Listener listener);
}
