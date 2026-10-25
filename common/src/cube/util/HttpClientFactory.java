/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

import cell.util.log.Logger;
import org.eclipse.jetty.client.HttpClient;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP 客户端工厂。
 *
 * <p><b>资源约束</b>：Jetty 的 {@link HttpClient} 自带线程池与 Selector，创建成本高。
 * 原实现对借出数量没有任何上限，调用方一旦漏还就会持续累积。现在：</p>
 * <ul>
 *     <li>并发借出上限 {@link #MAX_CLIENTS}，借出超时抛 {@link IllegalStateException}（明确失败优于静默耗尽）；</li>
 *     <li>统一设置连接超时与空闲超时，避免对端不响应时永久占用线程；</li>
 *     <li>拒绝归还 {@code null}，并识别重复归还（避免许可计数被放大）；</li>
 *     <li>{@link #close()} 负责停止池内实例，在停机时调用。</li>
 * </ul>
 */
public final class HttpClientFactory {

    /**
     * 最大并发借出数量。
     */
    public final static int MAX_CLIENTS = 16;

    /**
     * 借出等待超时（秒）。
     */
    public final static long BORROW_TIMEOUT_SECONDS = 30;

    /**
     * 建立连接超时（毫秒）。
     */
    public final static long CONNECT_TIMEOUT_MILLIS = 5 * 1000L;

    /**
     * 连接空闲超时（毫秒）。
     */
    public final static long IDLE_TIMEOUT_MILLIS = 30 * 1000L;

    private final static HttpClientFactory instance = new HttpClientFactory();

    private ConcurrentLinkedQueue<HttpClient> queue;

    private Semaphore permits;

    /**
     * 当前借出且未归还的实例，用于识别重复归还。
     */
    private Set<HttpClient> borrowed;

    private AtomicInteger created;

    private HttpClientFactory() {
        this.queue = new ConcurrentLinkedQueue<>();
        this.permits = new Semaphore(MAX_CLIENTS);
        this.borrowed = Collections.newSetFromMap(new ConcurrentHashMap<HttpClient, Boolean>());
        this.created = new AtomicInteger(0);
    }

    public static HttpClientFactory getInstance() {
        return HttpClientFactory.instance;
    }

    /**
     * 借出一个 HTTP 客户端。
     *
     * <p>调用方必须在 {@code finally} 里调用 {@link #returnHttpClient(HttpClient)}。</p>
     *
     * @return 已启动的客户端。
     * @throws IllegalStateException 池已满且等待超时，或客户端启动失败时抛出。
     */
    public HttpClient borrowHttpClient() {
        try {
            if (!this.permits.tryAcquire(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Logger.e(HttpClientFactory.class, "#borrowHttpClient - The pool is exhausted, max: " + MAX_CLIENTS
                        + ", borrowed: " + this.borrowed.size());
                throw new IllegalStateException("HttpClient pool exhausted, max: " + MAX_CLIENTS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while borrowing HttpClient", e);
        }

        HttpClient client = this.queue.poll();
        if (null != client) {
            if (client.isStarted()) {
                this.borrowed.add(client);
                return client;
            }

            // 池内实例已被停止或启动失败，丢弃后重建
            this.closeQuietly(client);
            client = null;
        }

        client = this.newClient();
        this.borrowed.add(client);
        return client;
    }

    /**
     * 归还 HTTP 客户端。
     *
     * <p>{@code null} 会被忽略并记录；重复归还会被识别并忽略。</p>
     *
     * @param client 客户端。
     */
    public void returnHttpClient(HttpClient client) {
        if (null == client) {
            Logger.w(HttpClientFactory.class, "#returnHttpClient - Ignore null client");
            return;
        }

        if (!this.borrowed.remove(client)) {
            Logger.w(HttpClientFactory.class, "#returnHttpClient - The client is not borrowed or already returned");
            return;
        }

        if (client.isStopped() || client.isFailed()) {
            this.closeQuietly(client);
            this.permits.release();
            return;
        }

        this.queue.offer(client);
        this.permits.release();
    }

    /**
     * 当前借出未归还的客户端数量。
     *
     * @return 借出数量。
     */
    public int getBorrowedCount() {
        return this.borrowed.size();
    }

    /**
     * 累计创建的客户端数量。
     *
     * @return 创建数量。
     */
    public int getCreatedCount() {
        return this.created.get();
    }

    public void close() {
        Iterator<HttpClient> iter = this.queue.iterator();
        while (iter.hasNext()) {
            HttpClient client = iter.next();
            this.closeQuietly(client);
        }
        this.queue.clear();
    }

    private HttpClient newClient() {
        HttpClient client = new HttpClient();
        client.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        client.setIdleTimeout(IDLE_TIMEOUT_MILLIS);
        client.setFollowRedirects(true);

        try {
            client.start();
        } catch (Exception e) {
            this.closeQuietly(client);
            this.permits.release();
            Logger.e(HttpClientFactory.class, "#newClient - Failed to start HttpClient", e);
            throw new IllegalStateException("Failed to start HttpClient", e);
        }

        this.created.incrementAndGet();
        return client;
    }

    private void closeQuietly(HttpClient client) {
        try {
            client.stop();
        } catch (Exception e) {
            Logger.d(HttpClientFactory.class, "#closeQuietly - " + e.getMessage());
        }
    }
}
