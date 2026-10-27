/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import cell.api.Servable;
import cell.core.talk.BaseServer;
import cell.core.talk.TalkContext;
import cell.util.log.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理器守护线程。
 * 不使用系统的定时器机制，而使用线程自旋方式，让整个任务始终持有时间片。
 */
public class ManagementDaemon extends Thread {

    private final Kernel kernel;

    /**
     * 运行标志。
     *
     * <p>必须 {@code volatile}：{@link #terminate()} 由停机线程调用，
     * 缺少可见性时循环可能一直读到旧值，导致停机无效。</p>
     */
    private volatile boolean spinning = true;

    private final long spinningSleep = 60 * 1000;

    private long lastCheckTime = System.currentTimeMillis();

    private final ExecutorService executor;

    public ManagementDaemon(Kernel kernel) {
        setName("ManagementDaemon");
        setDaemon(true);
        this.kernel = kernel;
        this.executor = Executors.newFixedThreadPool(8);
    }

    /**
     * 守护线程主循环。
     *
     * <p>周期性检查各模块状态并触发相应维护动作，直到线程被中断。</p>
     */
    @Override
    public void run() {
        while (this.spinning) {
            try {
                Thread.sleep(this.spinningSleep);
            } catch (InterruptedException e) {
                // 停机时由 terminate() 中断，属预期路径
                if (!this.spinning) {
                    break;
                }

                Logger.d(ManagementDaemon.class, "#run - Interrupted, continue spinning");
                continue;
            }

            if (!this.spinning) {
                break;
            }

            List<AbstractModule> list = this.kernel.getModules();
            for (int i = 0, size = list.size(); i < size; ++i) {
                final AbstractModule module = list.get(i);
                this.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // 单模块抛异常不能影响其它模块，也不能静默消失
                        try {
                            module.onTick(module, kernel);
                        } catch (Throwable t) {
                            Logger.w(ManagementDaemon.class, "#run - onTick failed: "
                                    + module.getClass().getName(), t);
                        }
                    }
                });
            }

            if (System.currentTimeMillis() - this.lastCheckTime > 10 * 60 * 1000) {
                this.lastCheckTime = System.currentTimeMillis();

                try {
                    for (Servable server : this.kernel.getNucleus().getTalkService().getServers()) {
                        List<TalkContext> contextList = server.getAllContext();
                        for (TalkContext context : contextList) {
                            if (!server.isActive(context)) {
                                // 非活跃
                                BaseServer baseServer = (BaseServer) server;
                                baseServer.hangup(context.getSession(), true);
                            }
                        }
                    }
                } catch (Exception e) {
                    Logger.e(ManagementDaemon.class, "#run - Unhandled exception", e);
                }
            }
        }
    }

    /**
     * 终止守护线程。
     *
     * <p>先置标志再中断：线程可能正阻塞在 60 秒的 {@code sleep} 里，
     * 只置标志会让停机最多多等一分钟。随后等待线程与任务池收尾。</p>
     */
    public final void terminate() {
        this.spinning = false;
        this.interrupt();

        if (Thread.currentThread() != this) {
            try {
                this.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (null != this.executor && !this.executor.isShutdown()) {
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.executor.shutdownNow();
            }
        }
    }
}
