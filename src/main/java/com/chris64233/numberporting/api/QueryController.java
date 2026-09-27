package com.chris64233.numberporting.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.numberporting.api.dto.EventView;
import com.chris64233.numberporting.api.dto.NumberOwnership;
import com.chris64233.numberporting.api.dto.OrderDetail;
import com.chris64233.numberporting.service.PortQueryService;

/** 只读查询：申请详情、号码归属、切换事件时间线。 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final PortQueryService queryService;

    public QueryController(PortQueryService queryService) {
        this.queryService = queryService;
    }

    /** 申请详情。 */
    @GetMapping("/port-orders/{id}")
    public OrderDetail order(@PathVariable Long id) {
        return Mappers.toOrderDetail(queryService.getOrder(id));
    }

    /** 号码归属：当前运营商、当前活动申请。 */
    @GetMapping("/numbers/{number}/ownership")
    public NumberOwnership ownership(@PathVariable String number) {
        var view = queryService.getOwnership(number);
        return Mappers.toOwnership(
                view.number().getNumber(),
                view.number().getCurrentCarrier(),
                view.number().getActiveOrderId(),
                view.ownershipConsistent());
    }

    /** 号码的全部携转申请（按创建时间）。 */
    @GetMapping("/numbers/{number}/port-orders")
    public List<OrderDetail> orders(@PathVariable String number) {
        return queryService.ordersOfNumber(number).stream().map(Mappers::toOrderDetail).toList();
    }

    /** 号码的切换事件时间线（不可变事件，按发生顺序）。 */
    @GetMapping("/numbers/{number}/timeline")
    public List<EventView> numberTimeline(@PathVariable String number) {
        return queryService.timelineOfNumber(number).stream()
                .map(Mappers::toEventView).toList();
    }

    /** 单笔申请的事件时间线。 */
    @GetMapping("/port-orders/{id}/timeline")
    public List<EventView> orderTimeline(@PathVariable Long id) {
        return queryService.timelineOfOrder(id).stream()
                .map(Mappers::toEventView).toList();
    }
}
