/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.robodroid;

import cell.util.log.Logger;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.dispatcher.robodroid.handler.*;
import cube.dispatcher.util.Tickable;
import cube.util.HttpServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Robodroid Cellet 服务单元。
 */
public class RobodroidCellet extends AbstractCellet implements Tickable {

    /** 单元名称，同时作为端点注册名。 */
    public final static String NAME = "Robodroid";

    /**
     * Roboengine 的 API 地址，默认指向本机。
     *
     * <p>可由配置项 {@code robodroid.api} 覆盖。</p>
     */
    protected static String API_URL = "http://127.0.0.1:2280/event/callback/AfKbNrmDvJQWxkMFNlYQfFHVrRZkbjNi";

    /**
     * 回调地址，供 Roboengine 反向推送事件。
     *
     * <p>可由配置项 {@code robodroid.callback} 覆盖，必须与对外暴露的地址一致，否则收不到事件。</p>
     */
    protected static String CALLBACK_URL = "http://127.0.0.1:7010/robodroid/event/kLBNGSmrTbmNlBfTYcIaKqYQiDPKoTkE";

    /**
     * 执行机。
     */
    private Performer performer;

    private boolean enabled = false;

    private boolean registered = false;

    private int tickCount = 0;

    public RobodroidCellet() {
        super(NAME);
    }

    /**
     * 装载单元：按配置决定是否启用机器人服务。
     *
     * <p>关闭时只打印告警并直接返回，不注册任何端点，也不启动管理器；
     * 启用时依次启动管理器、注册端点并上报回调地址。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");
        this.performer.addTickable(this);

        // 配置
        if (this.performer.getProperties().containsKey("robodroid.enabled")) {
            this.enabled = Boolean.parseBoolean(
                    this.performer.getProperties().getProperty("robodroid.enabled", "false"));
        }
        if (this.performer.getProperties().containsKey("robodroid.api")) {
            API_URL = this.performer.getProperties().getProperty("robodroid.api").trim();
        }
        if (this.performer.getProperties().containsKey("robodroid.callback")) {
            CALLBACK_URL = this.performer.getProperties().getProperty("robodroid.callback").trim();
        }

        if (this.enabled) {
            Manager.getInstance().start();

            setupHandler();

            registerCallback();
        } else {
            Logger.w(this.getClass(), "Robodroid service is NOT enabled");
        }

        return true;
    }

    /**
     * 卸载单元：停止管理器并撤销回调注册。
     *
     * <p>未启用时只摘除定时回调，不做其他清理。</p>
     */
    @Override
    public void uninstall() {
        this.performer.removeTickable(this);

        if (this.enabled) {
            Manager.getInstance().stop();

            deregisterCallback();
        }
    }

    private void setupHandler() {
        HttpServer httpServer = this.performer.getHttpServer();

        ContextHandler eventHandler = new ContextHandler();
        eventHandler.setContextPath(RoboengineEventHandler.CONTEXT_PATH);
        eventHandler.setHandler(new RoboengineEventHandler(this.performer));
        httpServer.addContextHandler(eventHandler);

        httpServer.addContextHandler(new RegisterCallback(this.performer));
        httpServer.addContextHandler(new DeregisterCallback(this.performer));
        httpServer.addContextHandler(new GetOnlineList(this.performer));
        httpServer.addContextHandler(new GetAccountList(this.performer));
        httpServer.addContextHandler(new GetAccount(this.performer));
        httpServer.addContextHandler(new Perform(this.performer));
        httpServer.addContextHandler(new Cancel(this.performer));
    }

    private void registerCallback() {
        HttpClient client = new HttpClient();

        try {
            client.start();

            JSONObject data = new JSONObject();
            data.put("url", CALLBACK_URL);

            StringContentProvider provider = new StringContentProvider(data.toString());
            ContentResponse response = client.POST(API_URL).content(provider).send();
            if (response.getStatus() != HttpStatus.OK_200) {
                this.registered = false;
                Logger.w(this.getClass(), "#registerCallback - register callback URL failed: " + response.getStatus());
            } else {
                this.registered = true;
                Logger.i(this.getClass(), "#registerCallback - register callback: " + CALLBACK_URL);
            }
        } catch (Exception e) {
            Logger.e(this.getClass(), "#registerCallback", e);
        } finally {
            if (client.isStarted()) {
                try {
                    client.stop();
                } catch (Exception e) {
                }
            }
        }
    }

    private void deregisterCallback() {
        HttpClient client = new HttpClient();

        try {
            client.start();

            JSONObject data = new JSONObject();
            data.put("url", CALLBACK_URL);
            data.put("deregister", true);

            StringContentProvider provider = new StringContentProvider(data.toString());
            ContentResponse response = client.POST(API_URL).content(provider).send();
            if (response.getStatus() != HttpStatus.OK_200) {
                Logger.w(this.getClass(), "#deregisterCallback - deregister callback URL failed: " + response.getStatus());
            } else {
                Logger.i(this.getClass(), "#deregisterCallback - deregister callback: " + CALLBACK_URL);
            }

            this.registered = false;
        } catch (Exception e) {
            Logger.e(this.getClass(), "#deregisterCallback", e);
        } finally {
            if (client.isStarted()) {
                try {
                    client.stop();
                } catch (Exception e) {
                }
            }
        }
    }

    /**
     * 周期性维护。
     *
     * <p>未启用时直接返回。每 3 个 tick 检查一次回调是否已注册，未注册则重新注册；
     * 每 6 个 tick 探测一次 Roboengine 是否可用，异常时把注册标记置为未注册，
     * 下一轮会自动重新注册，形成自愈。</p>
     *
     * @param now 当前时间戳，单位毫秒。
     */
    @Override
    public void onTick(long now) {
        if (!this.enabled) {
            return;
        }

        ++this.tickCount;
        if (this.tickCount >= Integer.MAX_VALUE) {
            this.tickCount = 1;
        }

        if (this.tickCount % 3 == 0) {
            // 每30秒执行一次
            if (!this.registered) {
                this.registerCallback();
            }
        }

        if (this.tickCount % 6 == 0) {
            // 检查 Roboengine 服务是否可用
            checkRoboengine();
        }
    }

    private boolean checkRoboengine() {
        HttpClient client = new HttpClient();

        try {
            client.start();

            JSONObject data = new JSONObject();

            StringContentProvider provider = new StringContentProvider(data.toString());
            ContentResponse response = client.POST(API_URL).content(provider).send();
            Logger.d(this.getClass(), "#checkRoboengine - " + response.getStatus());
        } catch (ExecutionException e) {
            this.registered = false;
            Logger.w(this.getClass(), "#checkRoboengine - Roboengine unavailable", e);
            return false;
        } catch (TimeoutException e) {
            this.registered = false;
            Logger.w(this.getClass(), "#checkRoboengine - Roboengine unavailable", e);
            return false;
        } catch (InterruptedException e) {
            this.registered = false;
            Logger.w(this.getClass(), "#checkRoboengine - Roboengine unavailable", e);
            return false;
        } catch (Exception e) {
            this.registered = false;
            Logger.w(this.getClass(), "#checkRoboengine - Roboengine unavailable", e);
            return false;
        } finally {
            if (client.isStarted()) {
                try {
                    client.stop();
                } catch (Exception e) {
                }
            }
        }

        return true;
    }
}
