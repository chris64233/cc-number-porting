package com.chris64233.numberporting.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.PortEvent;
import com.chris64233.numberporting.domain.PortOrder;
import com.chris64233.numberporting.domain.ServiceRelation;
import com.chris64233.numberporting.domain.ServiceStatus;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortEventRepository;
import com.chris64233.numberporting.repository.PortOrderRepository;
import com.chris64233.numberporting.repository.ServiceRelationRepository;

/** 只读查询服务：申请详情、号码归属、切换事件时间线。 */
@Service
public class PortQueryService {

    private final PortOrderRepository orders;
    private final PhoneNumberRepository numbers;
    private final PortEventRepository events;
    private final ServiceRelationRepository relations;

    public PortQueryService(PortOrderRepository orders,
                            PhoneNumberRepository numbers,
                            PortEventRepository events,
                            ServiceRelationRepository relations) {
        this.orders = orders;
        this.numbers = numbers;
        this.events = events;
        this.relations = relations;
    }

    @Transactional(readOnly = true)
    public PortOrder getOrder(Long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.ORDER_NOT_FOUND,
                        String.valueOf(orderId)));
    }

    /** 按幂等键查申请（不存在返回 null）。 */
    @Transactional(readOnly = true)
    public PortOrder findByRequestId(String requestId) {
        return orders.findByRequestId(requestId).orElse(null);
    }

    @Transactional(readOnly = true)
    public PhoneNumber getNumber(String number) {
        return numbers.findById(number)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number));
    }

    /** 号码归属：当前运营商、活动申请 id，以及当前 ACTIVE 服务关系（一致性校验）。 */
    @Transactional(readOnly = true)
    public NumberOwnershipView getOwnership(String number) {
        PhoneNumber phone = getNumber(number);
        ServiceRelation active = relations
                .findFirstByPhoneNumberAndStatusOrderByIdDesc(number, ServiceStatus.ACTIVE)
                .orElse(null);
        boolean consistent = active != null
                && active.getCarrier().getCode().equals(phone.getCurrentCarrier().getCode());
        return new NumberOwnershipView(phone, active, consistent);
    }

    /** 某号码的完整事件时间线（按发生顺序，事件不可变）。 */
    @Transactional(readOnly = true)
    public List<PortEvent> timelineOfNumber(String number) {
        if (!numbers.existsById(number)) {
            throw new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number);
        }
        return events.findByPhoneNumberOrderByIdAsc(number);
    }

    /** 某笔申请的事件时间线。 */
    @Transactional(readOnly = true)
    public List<PortEvent> timelineOfOrder(Long orderId) {
        if (!orders.existsById(orderId)) {
            throw new BusinessRuleException(ErrorCode.ORDER_NOT_FOUND, String.valueOf(orderId));
        }
        return events.findByOrderIdOrderByIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public List<PortOrder> ordersOfNumber(String number) {
        if (!numbers.existsById(number)) {
            throw new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number);
        }
        return orders.findByPhoneNumberOrderByCreatedAtAsc(number);
    }

    /** 号码归属视图：归属、服务关系以及二者是否一致（归属不变量自检）。 */
    public record NumberOwnershipView(PhoneNumber number, ServiceRelation activeRelation,
                                      boolean ownershipConsistent) {
    }
}
