/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.filestorage;

import cell.util.log.Logger;
import cube.common.entity.Contact;
import cube.common.entity.SharingReport;
import cube.common.entity.TraceEvent;
import cube.common.entity.VisitTrace;
import cube.util.IPSeeker;
import cube.util.TextUtils;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报告数据计算器。
 */
public class SharingReportor {

    /**
     * IP 库不可用时只提示一次，避免逐条记录刷屏。
     */
    private final static AtomicBoolean IP_DB_WARNED = new AtomicBoolean(false);

    private ServiceStorage storage;

    public SharingReportor(ServiceStorage storage) {
        this.storage = storage;
    }

    public SharingReport reportCountRecord(Contact contact) {
        int numTag = this.storage.countSharingTag(contact.getDomain().getName(), contact.getId(), true);
        int numEventView = this.storage.countTraceEvent(contact.getDomain().getName(), contact.getId(),
                TraceEvent.View);
        int numEventExtract = this.storage.countTraceEvent(contact.getDomain().getName(), contact.getId(),
                TraceEvent.Extract);
        int numEventShare = this.storage.countTraceEvent(contact.getDomain().getName(), contact.getId(),
                TraceEvent.Share);

        SharingReport report = new SharingReport(SharingReport.CountRecord);
        report.totalSharingTag = numTag;
        report.totalEventView = numEventView;
        report.totalEventExtract = numEventExtract;
        report.totalEventShare = numEventShare;
        return report;
    }

    public SharingReport reportTopNRecords(Contact contact, int topNum) {
        SharingReport report = new SharingReport(SharingReport.TopCountRecord);

        Map<String, Integer> viewCountMap = this.storage.queryCodeCountByEvent(contact.getDomain().getName(),
                contact.getId(), TraceEvent.View);
        report.addTopViewRecords(viewCountMap, topNum);

        Map<String, Integer> extractCountMap = this.storage.queryCodeCountByEvent(contact.getDomain().getName(),
                contact.getId(), TraceEvent.Extract);
        report.addTopExtractRecords(extractCountMap, topNum);

        return report;
    }

    /**
     * 报告历史访问数据总数。
     * @param contact
     * @param duration
     * @param durationUnit
     * @return
     */
    public SharingReport reportHistoryTotal(Contact contact, int duration, int durationUnit) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE),
                0, 0, 0);
        long endTime = calendar.getTimeInMillis();

        switch (durationUnit) {
            case Calendar.DATE:
                calendar.add(Calendar.DATE, -duration);
                break;
            case Calendar.MONTH:
                calendar.add(Calendar.MONTH, -duration);
                break;
            case Calendar.YEAR:
                calendar.add(Calendar.YEAR, -duration);
                break;
            default:
                break;
        }
        long beginTime = calendar.getTimeInMillis();

        List<Long> separators = new ArrayList<>();
        if (durationUnit == Calendar.DATE || (duration <= 3 && durationUnit == Calendar.MONTH)) {
            long time = beginTime;
            while (time < endTime) {
                time += 24 * 60 * 60 * 1000;
                separators.add(time - 1000);
            }
        }
        else {
            long time = beginTime;
            while (time < endTime) {
                // 按月分隔数据
                calendar.add(Calendar.MONTH, 1);
                time = calendar.getTimeInMillis();
                separators.add(time - 1000);
            }
        }

        SharingReport report = new SharingReport(SharingReport.HistoryEventRecord);

        // IP 数据
        Map<String, AtomicInteger> ipMap = new HashMap<>();
        // OS 数据
        Map<String, AtomicInteger> osMap = new HashMap<>();
        // 浏览器数据
        Map<String, AtomicInteger> swMap = new HashMap<>();

        long begin = beginTime;
        for (Long separateTime : separators) {
            if (Logger.isDebugLevel()) {
                Logger.d(this.getClass(), "#reportHistoryTotal - time interval: "
                        + (new Date(begin)).toString() + " - " + (new Date(separateTime)).toString());
            }

            List<ServiceStorage.TimePoint> list = this.storage.queryEventTimeline(contact.getDomain().getName(),
                    contact.getId(), TraceEvent.View, begin, separateTime);
            // 添加记录
            report.addEventToTimeline(begin, TraceEvent.View, list.size());

            for (ServiceStorage.TimePoint tp : list) {
                mergeTimePoint(tp, ipMap, osMap, swMap);
            }

            list = this.storage.queryEventTimeline(contact.getDomain().getName(),
                    contact.getId(), TraceEvent.Extract, begin, separateTime);
            // 添加记录
            report.addEventToTimeline(begin, TraceEvent.Extract, list.size());

            for (ServiceStorage.TimePoint tp : list) {
                mergeTimePoint(tp, ipMap, osMap, swMap);
            }

            list = this.storage.queryEventTimeline(contact.getDomain().getName(),
                    contact.getId(), TraceEvent.Share, begin, separateTime);
            // 添加记录
            report.addEventToTimeline(begin, TraceEvent.Share, list.size());

            for (ServiceStorage.TimePoint tp : list) {
                mergeTimePoint(tp, ipMap, osMap, swMap);
            }

            begin = separateTime + 1000;
        }

        for (Map.Entry<String, AtomicInteger> entry : ipMap.entrySet()) {
            report.addIPTotal(entry.getKey(), entry.getValue().get());
        }
        for (Map.Entry<String, AtomicInteger> entry : osMap.entrySet()) {
            report.addOSTotal(entry.getKey(), entry.getValue().get());
        }
        for (Map.Entry<String, AtomicInteger> entry : swMap.entrySet()) {
            report.addSWTotal(entry.getKey(), entry.getValue().get());
        }

        return report;
    }

    private void mergeTimePoint(ServiceStorage.TimePoint timePoint, Map<String, AtomicInteger> ipMap,
                                Map<String, AtomicInteger> osMap,
                                Map<String, AtomicInteger> swMap) {
        String ipLocation = resolveLocation(timePoint.address);
        if (ipMap.containsKey(ipLocation)) {
            ipMap.get(ipLocation).incrementAndGet();
        }
        else {
            ipMap.put(ipLocation, new AtomicInteger(1));
        }
        if (null != timePoint.userAgent) {
            JSONObject us = TextUtils.parseUserAgent(timePoint.userAgent);
            String osName = us.getString("osName");
            String swName = us.getString("browserName");

            if (osMap.containsKey(osName)) {
                osMap.get(osName).incrementAndGet();
            }
            else {
                osMap.put(osName, new AtomicInteger(1));
            }

            if (swMap.containsKey(swName)) {
                swMap.get(swName).incrementAndGet();
            }
            else {
                swMap.put(swName, new AtomicInteger(1));
            }
        }
        else if (null != timePoint.agent) {
            // TODO
        }
    }

    /**
     * 解析 IP 归属地。
     *
     * <p>IP 库缺失时退化为原始地址：报表宁可少一列归属地，也不该因为一个可选依赖整体失败。
     * 原实现直接链式调用 {@code getInstance().findIP(...)}，库缺失时抛 NPE 打断整份报表。</p>
     *
     * @param address IP 地址。
     * @return 归属地或原始地址。
     */
    private static String resolveLocation(String address) {
        try {
            String location = IPSeeker.getInstance().findIP(address).getMainInfo();
            return (null == location || location.isEmpty()) ? address : location;
        } catch (Exception e) {
            if (IP_DB_WARNED.compareAndSet(false, true)) {
                Logger.w(SharingReportor.class, "#resolveLocation - IP database is unavailable, "
                        + "use raw address: " + e.getMessage());
            }
            return address;
        }
    }

    /**
     *
     * @param contact
     * @param duration
     * @param durationUnit
     * @return
     */
    public SharingReport reportVisitorEventTimeline(Contact contact, int duration, int durationUnit) {
        SharingReport report = new SharingReport(SharingReport.VisitorRecord);

        Calendar calendar = Calendar.getInstance();
        calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE),
                0, 0, 0);
        long endTime = calendar.getTimeInMillis();

        switch (durationUnit) {
            case Calendar.DATE:
                calendar.add(Calendar.DATE, -duration);
                break;
            case Calendar.MONTH:
                calendar.add(Calendar.MONTH, -duration);
                break;
            case Calendar.YEAR:
                calendar.add(Calendar.YEAR, -duration);
                break;
            default:
                break;
        }
        long beginTime = calendar.getTimeInMillis();

        // 按照时间检索
        List<VisitTrace> list = this.storage.searchVisitTraces(contact.getDomain().getName(), contact.getId(),
                beginTime, endTime);
        for (VisitTrace visitTrace : list) {
            long contactId = visitTrace.contactId;
            report.appendVisitorEvent(contactId, visitTrace.getCode(), visitTrace.event, 1);
        }

        return report;
    }

    public SharingReport reportFileTypeTotals(Contact contact) {
        SharingReport report = new SharingReport(SharingReport.FileTypeTotalRecord);

        Map<String, AtomicInteger> map = this.storage.countSharingFileType(contact.getDomain().getName(),
                contact.getId(), true);
        for (Map.Entry<String, AtomicInteger> entry : map.entrySet()) {
            report.addValidFileType(entry.getKey(), entry.getValue().get());
        }

        if (null == report.validFileTypeList) {
            report.validFileTypeList = new ArrayList<>();
        }

        map = this.storage.countSharingFileType(contact.getDomain().getName(),
                contact.getId(), false);
        for (Map.Entry<String, AtomicInteger> entry : map.entrySet()) {
            report.addExpiredFileType(entry.getKey(), entry.getValue().get());
        }

        if (null == report.expiredFileTypeList) {
            report.expiredFileTypeList = new ArrayList<>();
        }

        return report;
    }
}
