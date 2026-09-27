package com.chris64233.numberporting.api;

import java.net.URI;
import java.time.Instant;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.numberporting.api.dto.CreateOrderRequest;
import com.chris64233.numberporting.api.dto.OrderDetail;
import com.chris64233.numberporting.domain.PortOrder;
import com.chris64233.numberporting.service.PortOrderService;

/** 携转申请写操作：提交/审核/切换/取消/回退/授权码到期处理。 */
@RestController
@RequestMapping("/api/port-orders")
public class PortOrderController {

    private final PortOrderService portOrderService;

    public PortOrderController(PortOrderService portOrderService) {
        this.portOrderService = portOrderService;
    }

    /** 提交携转申请（requestId 幂等，重复提交返回同一笔申请，状态码 201）。 */
    @PostMapping
    public ResponseEntity<OrderDetail> create(@Valid @RequestBody CreateOrderRequest req) {
        PortOrder order = portOrderService.createOrder(
                req.requestId(), req.phoneNumber(), req.fromCarrier(), req.toCarrier(),
                req.authCode(), req.windowStart(), req.windowEnd());
        return ResponseEntity.created(URI.create("/api/port-orders/" + order.getId()))
                .body(Mappers.toOrderDetail(order));
    }

    @PostMapping("/{id}/approval")
    public OrderDetail approve(@PathVariable Long id) {
        return Mappers.toOrderDetail(portOrderService.approve(id));
    }

    @PostMapping("/{id}/switch")
    public OrderDetail switch_(@PathVariable Long id) {
        return Mappers.toOrderDetail(portOrderService.switch_(id));
    }

    @PostMapping("/{id}/cancellation")
    public OrderDetail cancel(@PathVariable Long id,
                              @RequestParam(required = false, defaultValue = "用户取消")
                              String reason) {
        return Mappers.toOrderDetail(portOrderService.cancel(id, reason));
    }

    @PostMapping("/{id}/rollback")
    public OrderDetail rollback(@PathVariable Long id,
                                @RequestParam(required = false, defaultValue = "受控回退")
                                String reason) {
        return Mappers.toOrderDetail(portOrderService.rollback(id, reason));
    }

    /** 授权码失效处理：若申请的授权码已过期/吊销，将申请关闭为 AUTH_CODE_EXPIRED。 */
    @PostMapping("/{id}/auth-code-expiry")
    public OrderDetail expire(@PathVariable Long id) {
        return Mappers.toOrderDetail(portOrderService.expireOrderIfCodeExpired(id));
    }
}
