package com.chris64233.numberporting.api;

import com.chris64233.numberporting.api.dto.EventView;
import com.chris64233.numberporting.api.dto.NumberOwnership;
import com.chris64233.numberporting.api.dto.OrderDetail;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortEvent;
import com.chris64233.numberporting.domain.PortOrder;

/** 实体 → 对外 DTO 映射。 */
final class Mappers {

    private Mappers() {
    }

    static OrderDetail toOrderDetail(PortOrder o) {
        return new OrderDetail(
                o.getId(),
                o.getRequestId(),
                o.getPhoneNumber(),
                o.getFromCarrier().getCode(),
                o.getToCarrier().getCode(),
                o.getAuthCode(),
                o.getWindowStart(),
                o.getWindowEnd(),
                o.getStatus(),
                o.getCreatedAt(),
                o.getApprovedAt(),
                o.getSwitchedAt(),
                o.getRollbackDeadline(),
                o.getRolledBackAt(),
                o.getCancelledAt(),
                o.getExpiredAt(),
                o.getNote());
    }

    static NumberOwnership toOwnership(String number, Carrier carrier, Long activeOrderId,
                                       boolean ownershipConsistent) {
        return new NumberOwnership(number, carrier.getCode(), carrier.getName(),
                activeOrderId, ownershipConsistent);
    }

    static EventView toEventView(PortEvent e) {
        return new EventView(
                e.getId(),
                e.getOrderId(),
                e.getPhoneNumber(),
                e.getType().name(),
                e.getType().description,
                e.getFromStatus(),
                e.getToStatus(),
                e.getDetail(),
                e.getOccurredAt());
    }
}
