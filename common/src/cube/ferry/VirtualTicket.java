/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cube.common.notice.MessagingCountMessages;
import cube.core.AbstractModule;

/**
 * 虚拟 Ferry House 。
 */
public class VirtualTicket extends Ticket {

    private long maxDiskSize = 256L * 1024 * 1024 * 1024;

    private DomainInfo domainInfo;

    private BoxReport boxReport;

    public VirtualTicket(DomainInfo domainInfo) {
        super(domainInfo.getDomain().getName(), domainInfo.toLicence(), null);
        this.domainInfo = domainInfo;
    }

    /**
     * 获取域信息。
     *
     * @return 域信息。
     */
    public DomainInfo getDomainInfo() {
        return this.domainInfo;
    }

    /**
     * 获取框报告。
     *
     * @param module 框报告。
     * @return 框报告。
     */
    public BoxReport getBoxReport(AbstractModule module) {
        if (null == this.boxReport) {
            this.boxReport = this.buildReport(module);
        }

        return this.boxReport;
    }

    private BoxReport buildReport(AbstractModule module) {
        AbstractModule messaging = module.getKernel().getModule("Messaging");
        MessagingCountMessages count = new MessagingCountMessages(this.domainInfo.getDomain().getName());
        MessagingCountMessages result = (MessagingCountMessages) messaging.notify(count);

        BoxReport report = new BoxReport(this.domainInfo.getDomain().getName());
        report.setTotalMessages(result.getCount());

        report.setFreeDiskSize(this.maxDiskSize);
        return report;
    }
}
