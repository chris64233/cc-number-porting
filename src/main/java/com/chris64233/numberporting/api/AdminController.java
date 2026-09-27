package com.chris64233.numberporting.api;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.numberporting.api.dto.IssueAuthCodeRequest;
import com.chris64233.numberporting.api.dto.RegisterNumberRequest;
import com.chris64233.numberporting.domain.AuthorizationCode;
import com.chris64233.numberporting.service.NumberProvisioningService;
import com.chris64233.numberporting.service.PortOrderService;

/** 联调/测试辅助与运营商侧管理接口：号码入网、运营商维护、授权码签发与吊销。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final NumberProvisioningService provisioningService;
    private final PortOrderService portOrderService;

    public AdminController(NumberProvisioningService provisioningService,
                           PortOrderService portOrderService) {
        this.provisioningService = provisioningService;
        this.portOrderService = portOrderService;
    }

    @PostMapping("/carriers")
    public ResponseEntity<Void> ensureCarrier(@RequestParam String code,
                                              @RequestParam String name) {
        provisioningService.ensureCarrier(code, name);
        return ResponseEntity.created(URI.create("/api/admin/carriers/" + code)).build();
    }

    /** 号码入网：建立初始归属运营商与初始 ACTIVE 服务关系。 */
    @PostMapping("/numbers")
    public ResponseEntity<Void> registerNumber(@RequestBody RegisterNumberRequest req) {
        provisioningService.registerNumber(req.number(), req.carrier());
        return ResponseEntity.created(URI.create("/api/numbers/" + req.number())).build();
    }

    /** 原运营商签发授权码（明确有效期）。 */
    @PostMapping("/authorization-codes")
    public ResponseEntity<Void> issueAuthCode(@RequestBody IssueAuthCodeRequest req) {
        AuthorizationCode code = provisioningService.issueAuthCode(
                req.code(), req.phoneNumber(), req.expiresAt());
        return ResponseEntity.created(
                URI.create("/api/admin/authorization-codes/" + code.getCode())).build();
    }

    /** 授权码主动失效（吊销）：所有使用该码的活动申请被关闭。 */
    @PostMapping("/authorization-codes/{code}/revocation")
    public ResponseEntity<Void> revokeAuthCode(@org.springframework.web.bind.annotation
                                                       .PathVariable String code,
                                               @RequestParam(required = false,
                                                       defaultValue = "运营商主动失效")
                                               String reason) {
        portOrderService.revokeAuthCode(code, reason);
        return ResponseEntity.noContent().build();
    }
}
