/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.util.CachedQueueExecutor;
import cell.util.log.Logger;
import cube.benchmark.ResponseTime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抽象的服务 Cellet 单元。
 */
public abstract class AbstractCellet extends Cellet {

    protected static ExecutorService sExecutor;

    protected AtomicLong listenedCounter = new AtomicLong(0L);

    protected ConcurrentHashMap<String, List<ResponseTime>> responseTimeMap = new ConcurrentHashMap<>();

    protected int maxListLength = 50;

    public AbstractCellet(String name) {
        super(name);
    }

    /**
     * 初始化共享线程池。
     *
     * <p>必须同步：多个 Cellet 会并发调用，不同步时可能各自建一个池，其中一个永远泄漏
     * （线程持续存活且无人引用）。</p>
     *
     * @param properties 配置，允许为 {@code null} 。
     */
    public synchronized static void initialize(Properties properties) {
        if (null == AbstractCellet.sExecutor) {
            if (null == properties) {
                AbstractCellet.sExecutor = Executors.newFixedThreadPool(32);
                Logger.i(AbstractCellet.class, "The thread pool type (NO configs): " + "fixed - max: " + 32);
            }
            else {
                int max = 8;
                try {
                    max = Integer.parseInt(properties.getProperty("threadpool.max", "32"));
                } catch (Exception e) {
                    // 配置非法时使用默认值
                    Logger.d(AbstractCellet.class, "#initialize - Failed to parse the config 'threadpool.max', fallback to " + max + ": " + e.getMessage());
                }
                if (properties.getProperty("threadpool.type", "cached").equalsIgnoreCase("cached")) {
                    AbstractCellet.sExecutor = CachedQueueExecutor.newCachedQueueThreadPool(max);
                }
                else {
                    AbstractCellet.sExecutor = Executors.newFixedThreadPool(max);
                }
                Logger.i(AbstractCellet.class, "The thread pool type: "
                        + properties.getProperty("threadpool.type", "cached") + " - max: " + max);
            }
        }
    }

    /**
     * 关闭共享线程池。
     *
     * <p>幂等：重复调用不会抛异常。关闭后置空，允许在测试或重启场景重新初始化。</p>
     */
    public static void halt() {
        ExecutorService executor = AbstractCellet.sExecutor;
        AbstractCellet.sExecutor = null;

        if (null == executor) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public ExecutorService getExecutor() {
        return AbstractCellet.sExecutor;
    }

    protected void execute(Runnable task) {
        AbstractCellet.sExecutor.execute(task);
    }

    /**
     * 获取数据接收计数器。
     *
     * @return 返回数据接收计数器。
     */
    public AtomicLong getListenedCounter() {
        return this.listenedCounter;
    }

    /**
     * 获取应答时间记录。
     *
     * <p>返回内部映射的只读视图：调用方无法通过返回值修改本单元的状态。
     * 视图是实时的，仍会反映后续新增的应答时间。</p>
     *
     * @return 返回应答时间记录。
     */
    public Map<String, List<ResponseTime>> getResponseTimes() {
        return Collections.unmodifiableMap(this.responseTimeMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onListened(TalkContext talkContext, Primitive primitive) {
        this.listenedCounter.incrementAndGet();
    }

    protected ResponseTime markResponseTime(String mark) {
        ResponseTime time = new ResponseTime(mark);
        time.beginning = System.currentTimeMillis();

        List<ResponseTime> list = this.responseTimeMap.get(mark);
        if (null == list) {
            list = new Vector<>();
            this.responseTimeMap.put(mark, list);
        }
        list.add(time);
        if (list.size() > this.maxListLength) {
            list.remove(0);
        }

        return time;
    }
}
