/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.member;

import cube.aigc.psychology.ReportPermission;
import cube.aigc.psychology.RetentionPolicy;
import cube.aigc.psychology.app.UserProfile;
import cube.auth.AuthToken;
import cube.common.entity.Contact;
import cube.common.entity.Membership;
import cube.common.entity.User;
import cube.service.aigc.AIGCService;
import cube.service.contact.ContactManager;

/**
 * 会员中心。
 *
 * <p>维护会员权益与用量口径：按域名与用户判定可用额度，并据此决定高级功能是否放行。</p>
 */
public class MemberCenter {

    /**
     * 非注册用户配额。
     */
    public final static int gsVisitorQuotas = 100;

    /**
     * 非会员的注册用户配额。
     */
    public final static int gsUserQuotas = 1000;

    /**
     * 专业版会员每月配额。
     */
    public final static int gsOrdinaryMemberQuotasPerMonth = 10000;

    /**
     * 旗舰版会员每月配置。
     */
    public final static int gsPremiumMemberQuotasPerMonth = -1;

    /**
     * 咨询督导消耗量。
     */
    public final static int gsPowerOfSupervisor = 1000;

    /**
     * 绘画推理消耗量。
     */
    public final static int gsPowerOfPredictPainting = 100;

    /**
     * 咨询内容整理为笔记消耗量。
     */
    public final static int gsPowerOfClinicalRecord = 100;

    /**
     * 非会员报告保存天数。
     *
     * <p>取值已归位到 {@link RetentionPolicy}，以便写入侧与过期过滤侧
     * 引用同一编译期常量。本字段仅为向后兼容保留，请勿在此直接改数值。</p>
     */
    public final static int gsNonmemberRetention = RetentionPolicy.NON_MEMBER_RETENTION_DAYS;

    /**
     * 会员报告保存天数。
     *
     * <p>取值已归位到 {@link RetentionPolicy}，理由同 {@link #gsNonmemberRetention}。</p>
     */
    public final static int gsMemberRetention = RetentionPolicy.MEMBER_RETENTION_DAYS;

    private final static MemberCenter instance = new MemberCenter();

    private AIGCService service;

    private MemberCenter() {
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public static MemberCenter getInstance() {
        return MemberCenter.instance;
    }

    /**
     * 启动会员中心。
     *
     * @param service AIGC 服务实例。
     */
    public void start(AIGCService service) {
        this.service = service;
    }

    /** 停止会员中心并释放资源。 */
    public void stop() {
    }

    /**
     * 获取用户
     * @param domain 域。
     * @param user user。
     * @return 计算结果。
     */
    public int getUsageOfThisMonth(String domain, User user) {
        Membership membership = ContactManager.getInstance().getMembershipSystem().getMembership(
                domain, user.getId(), Membership.STATE_NORMAL);
        // TODO XJW
        return 0;
    }

    /**
     * 查询联系人本月的用量。
     *
     * <p>当前未接入用量统计，固定返回 0 ，调用方不应据此判定额度耗尽。</p>
     *
     * @param contact 联系人。
     * @return 固定返回 0 。
     */
    public int getUsageOfThisMonth(Contact contact) {
        // TODO XJW
        return 0;
    }

    /**
     * 用户剩余的可用量。
     * @param user user。
     * @param membership membership。
     * @return 计算结果。
     */
    public int getRemainingUsages(User user, Membership membership) {
        // 本月用量
        int usage = this.getUsageOfThisMonth(user.getDomain().getName(), user);

        if (!user.isRegistered()) {
            // 未注册
            return gsVisitorQuotas - usage;
        }

        if (null == membership) {
            // 非会员
            return gsUserQuotas - usage;
        }
        else {
            // 会员
            if (membership.isOrdinaryRank()) {
                return gsOrdinaryMemberQuotasPerMonth - usage;
            }
            else {
                return Integer.MAX_VALUE;
            }
        }
    }

    /**
     * 判定绘画报告权限。
     *
     * <p>按本月用量与会员等级计算剩余额度，额度不足时拒绝并给出原因。</p>
     *
     * @param domain   域。
     * @param user     用户。
     * @param reportSn 报告序列号。
     * @return 权限判定结果。
     */
    public ReportPermission allowPredictPainting(String domain, User user, long reportSn) {
        // 本月用量
        int usage = this.getUsageOfThisMonth(domain, user);

        if (!user.isRegistered()) {
            // 非注册用户
            // 计算用量
            if (usage >= gsVisitorQuotas) {
                // 最小权限
                return new ReportPermission(user.getId(), reportSn);
            }
            else {
                // 权限
                ReportPermission permission = ReportPermission.createAllPermissions(user.getId(), reportSn);
                permission.attention = false;
                permission.dimensionScore = false;
                return permission;
            }
        }

        Membership membership = ContactManager.getInstance().getMembershipSystem().getMembership(
                domain, user.getId(), Membership.STATE_NORMAL);
        if (null == membership) {
            // 注册用户，非会员
            if (usage >= gsUserQuotas) {
                // 最小权限
                return new ReportPermission(user.getId(), reportSn);
            }
            else {
                // 权限
                ReportPermission permission = ReportPermission.createAllPermissions(user.getId(), reportSn);
                permission.attention = false;
                permission.dimensionScore = false;
                return permission;
            }
        }
        else {
            // 会员
            if (membership.isOrdinaryRank()) {
                // 专业版会员
                if (usage >= gsOrdinaryMemberQuotasPerMonth) {
                    // 最小权限
                    return new ReportPermission(user.getId(), reportSn);
                }
            }

            // 权限
            ReportPermission permission = ReportPermission.createAllPermissions(user.getId(), reportSn);
            permission.attention = false;
            permission.dimensionScore = false;
            return permission;
        }

        // 起始日期
//        long startTime = membership.getTimestamp();
//
//        // 截止日期
//        Calendar calendar = Calendar.getInstance();
//        calendar.setTimeInMillis(startTime);
//        calendar.add(Calendar.MONTH, 1);
//        long endTime = calendar.getTimeInMillis();
//
//        int num = PsychologyScene.getInstance().numScaleReports(user.getId(), AIGCStateCode.Ok.code,
//                true, startTime, endTime);
//        if (membership.type.equalsIgnoreCase(Membership.TYPE_ORDINARY)) {
//            // 普通会员
//            if (num >= gsOrdinaryMemberTimesPerMonth) {
//                // 最小权限
//                return new ReportPermission(user.getId(), reportSn);
//            }
//            else {
//                // 全部权限
//                return ReportPermission.createAllPermissions(user.getId(), reportSn);
//            }
//        }
//        else {
//            // 高级会员
//            if (gsPremiumMemberTimesPerMonth > 0 && num >= gsPremiumMemberTimesPerMonth) {
//                // 最小权限
//                return new ReportPermission(user.getId(), reportSn);
//            }
//            else {
//                // 全部权限
//                return ReportPermission.createAllPermissions(user.getId(), reportSn);
//            }
//        }
    }

//    public static int getUsageOfThisMonth(long id, Membership membership) {
//        // 起始日期
//        long startTime = membership.getTimestamp();
//
//        // 截止日期
//        Calendar calendar = Calendar.getInstance();
//        calendar.setTimeInMillis(startTime);
//        calendar.add(Calendar.MONTH, 1);
//        long endTime = calendar.getTimeInMillis();
//
//        return PsychologyScene.getInstance().numPsychologyReports(id,
//                AIGCStateCode.Ok.code, true, startTime, endTime);
//    }

    /**
     * 获取应用侧用户画像。
     *
     * <p>按令牌中的联系人信息组装画像，缺失字段留空。</p>
     *
     * @param authToken 访问令牌。
     * @return 用户画像。
     */
    public UserProfile getAppUserProfile(AuthToken authToken) {
        UserProfile profile = new UserProfile();

        Contact contact = ContactManager.getInstance().getContact(authToken.getDomain(), authToken.getContactId());
        profile.totalPoints = ContactManager.getInstance().getPointSystem().total(contact);
        profile.pointList = ContactManager.getInstance().getPointSystem().listPoints(contact);

        // 本月用量
        profile.usageOfThisMonth = this.getUsageOfThisMonth(contact);

        Membership membership = ContactManager.getInstance().getMembershipSystem().getMembership(contact, Membership.STATE_NORMAL);
        if (null != membership) {
            // 是会员
            profile.membership = membership;
            if (membership.isOrdinaryRank()) {
                // 每月限制
                profile.limitPerMonth = gsOrdinaryMemberQuotasPerMonth;
            }
            else {
                // 每月限制
                profile.limitPerMonth = gsPremiumMemberQuotasPerMonth;
            }
        }
        else {
            User user = this.service.getUser(authToken.getCode());
            if (user.isRegistered()) {
                // 注册用户
                // 每月限制
                profile.limitPerMonth = gsUserQuotas;
            }
            else {
                // 访客
                // 每月限制
                profile.limitPerMonth = gsVisitorQuotas;
            }
        }

        // 已作废数据
//        profile.permissibleReports = this.numPsychologyReports(authToken.getContactId(), AIGCStateCode.Ok.code, true);
//        List<PaintingReport> reports = this.getPsychologyReports(authToken.getContactId(), AIGCStateCode.Ok.code, 1);
//        if (!reports.isEmpty()) {
//            try {
//                profile.hexagonScore = reports.get(0).getDimensionScore();
//                profile.personality = reports.get(0).getEvaluationReport().getPersonalityAccelerator().getBigFivePersonality();
//            } catch (Exception e) {
//                Logger.w(this.getClass(), "#getAppUserProfile", e);
//            }
//        }

        return profile;
    }
}
