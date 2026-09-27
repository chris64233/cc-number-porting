package com.chris64233.numberporting.domain;

/**
 * 携转申请生命周期状态。
 *
 * <ul>
 *   <li>{@link #PENDING_REVIEW} 已提交，等待审核（活动申请）</li>
 *   <li>{@link #APPROVED} 审核通过，待切换（活动申请）</li>
 *   <li>{@link #SWITCHED} 已切换至新运营商，处于可回退窗口内（活动申请，窗口结束后自动释放）</li>
 *   <li>{@link #ROLLED_BACK} 已受控回退至原运营商（终态）</li>
 *   <li>{@link #CANCELLED} 用户取消（终态）</li>
 *   <li>{@link #REJECTED} 审核拒绝（终态）</li>
 *   <li>{@link #EXPIRED} 授权码失效或切换窗口结束未完成切换（终态）</li>
 * </ul>
 */
public enum PortingStatus {
    PENDING_REVIEW,
    APPROVED,
    SWITCHED,
    ROLLED_BACK,
    CANCELLED,
    REJECTED,
    EXPIRED;

    /**
     * 是否占用号码的“活动申请”名额：同一号码同一时刻最多一笔。
     */
    public boolean isActive() {
        return this == PENDING_REVIEW || this == APPROVED || this == SWITCHED;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
