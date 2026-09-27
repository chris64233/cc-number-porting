package com.chris64233.numberporting.domain;

/**
 * 携转申请状态机：
 * <pre>
 * PENDING_REVIEW --approve--&gt; APPROVED --switch--&gt; SWITCHED --rollback--&gt; ROLLED_BACK
 *       |                    |                            |
 *       |                    |                    (回退窗口关闭)
 *       |                    |                            v
 *       |                    |                        COMPLETED
 *       +--cancel--&gt; CANCELLED&lt;--cancel------------------+
 *       |
 *       +--(授权码失效)--&gt; AUTH_CODE_EXPIRED
 * APPROVED --(授权码失效)--&gt; AUTH_CODE_EXPIRED
 * CANCELLED / AUTH_CODE_EXPIRED / ROLLED_BACK / COMPLETED 为终态。
 * </pre>
 */
public enum PortOrderStatus {
    PENDING_REVIEW("待审核"),
    APPROVED("待切换"),
    SWITCHED("已切换"),
    ROLLED_BACK("已回退"),
    COMPLETED("携转完成（回退窗口已关闭）"),
    CANCELLED("已取消"),
    AUTH_CODE_EXPIRED("授权码失效");

    private final String description;

    PortOrderStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** 活动申请：占用号码唯一活动名额的状态（含回退窗口内的已切换申请）。 */
    public boolean isActive() {
        return this == PENDING_REVIEW || this == APPROVED || this == SWITCHED;
    }

    /** 终态：不可再发生状态迁移。 */
    public boolean isTerminal() {
        return this == ROLLED_BACK || this == COMPLETED
                || this == CANCELLED || this == AUTH_CODE_EXPIRED;
    }
}
