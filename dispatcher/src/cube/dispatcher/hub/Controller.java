/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.hub;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制器，用于控制访问请求。
 */
public class Controller {

    private ConcurrentHashMap<String, VisitLog> visitLogMap;

    public Controller() {
        this.visitLogMap = new ConcurrentHashMap<>();
    }

    /**
     * 校验访问是否在冷却期外。
     *
     * <p>按通道码与路径组合记录访问时间，同一组合在冷却时间内重复访问会被拒绝。
     * 首次访问总是放行。</p>
     *
     * @param channelCode 通道码。
     * @param path        请求路径。
     * @param coolingTime 冷却时间，单位毫秒。
     * @return 允许访问返回 {@code true} 。
     */
    public boolean verify(String channelCode, String path, long coolingTime) {
        String key = channelCode + "_" + path;

        VisitLog visitLog = this.visitLogMap.get(key);
        if (null == visitLog) {
            visitLog = new VisitLog();
            this.visitLogMap.put(key, visitLog);
            return true;
        }

        return visitLog.timing(coolingTime);
    }

    protected class VisitLog {

        private long timingPoint1;

        private long timingPoint2;

        protected VisitLog() {
            this.timingPoint1 = System.currentTimeMillis();
        }

        protected synchronized boolean timing(long coolingTime) {
            this.timingPoint2 = this.timingPoint1;
            this.timingPoint1 = System.currentTimeMillis();

            if (this.timingPoint1 - this.timingPoint2 < coolingTime) {
                return false;
            }
            else {
                return true;
            }
        }
    }
}
