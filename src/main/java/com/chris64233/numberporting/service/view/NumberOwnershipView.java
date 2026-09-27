package com.chris64233.numberporting.service.view;

import java.time.Instant;

import com.chris64233.numberporting.domain.Carrier;

/**
 * 号码归属查询结果：当前归属运营商 + 占用号码的活动申请（如有）。
 */
public record NumberOwnershipView(
        String number,
        Carrier currentCarrier,
        Instant provisionedAt,
        Instant lastSwitchedAt,
        String activeApplicationId,
        String activeApplicationStatus) {
}
