/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.robodroid;

import cell.util.log.Logger;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 接口管理器。
 */
public class Manager {

    private final static Manager instance = new Manager();

    private HttpClient client;

    private List<String> callbackList;

    private Manager() {
        this.callbackList = new ArrayList<>();
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public static Manager getInstance() {
        return Manager.instance;
    }

    /**
     * 启动管理器。
     *
     * <p>创建 HTTP 客户端用于回调通知。重复调用会重建客户端。</p>
     */
    public void start() {
        this.client = new HttpClient();

        try {
            this.client.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 停止管理器。
     *
     * <p>停止并释放 HTTP 客户端，允许重复调用。</p>
     */
    public void stop() {
        if (null != this.client) {
            try {
                this.client.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 注册回调地址。
     *
     * <p>同一地址只保留一次，重复注册不会产生重复通知。</p>
     *
     * @param url 回调地址。
     */
    public void registerCallback(String url) {
        synchronized (this.callbackList) {
            if (this.callbackList.contains(url)) {
                return;
            }

            this.callbackList.add(url);
        }
    }

    /**
     * 撤销回调地址。
     *
     * @param url 回调地址。
     */
    public void deregisterCallback(String url) {
        synchronized (this.callbackList) {
            this.callbackList.remove(url);
        }
    }

    /**
     * 向所有已注册地址推送回调。
     *
     * <p>逐个地址发送，单个地址失败不影响其余地址。</p>
     *
     * @param name 回调名称。
     * @param data 回调载荷。
     */
    public void callback(String name, JSONObject data) {
        synchronized (this.callbackList) {
            for (String url : this.callbackList) {
                JSONObject event = new JSONObject();
                event.put("name", name);
                event.put("data", data);

                StringContentProvider provider = new StringContentProvider(event.toString());
                try {
                    ContentResponse response = this.client.POST(url).content(provider).timeout(3, TimeUnit.SECONDS).send();
                    if (response.getStatus() != HttpStatus.OK_200) {
                        if (Logger.isDebugLevel()) {
                            Logger.d(this.getClass(), "Callback \"" + url + "\" failed");
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (TimeoutException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
