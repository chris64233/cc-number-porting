package com.chris64233.numberporting.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.service.NumberService;
import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.service.view.NumberOwnershipView;
import com.chris64233.numberporting.service.view.RelationshipView;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/numbers")
public class NumberController {

    private final NumberService numberService;
    private final PortingService portingService;

    public NumberController(NumberService numberService, PortingService portingService) {
        this.numberService = numberService;
        this.portingService = portingService;
    }

    /** 号码入网预置，建立号码与初始归属运营商及 ACTIVE 服务关系。 */
    @PostMapping("/provision")
    @ResponseStatus(HttpStatus.CREATED)
    public NumberOwnershipView provision(@Valid @RequestBody ProvisionNumberRequest request) {
        Carrier carrier;
        try {
            carrier = Carrier.valueOf(request.carrier().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_ERROR, "unknown carrier: " + request.carrier());
        }
        var phone = numberService.provision(request.number(), carrier);
        return new NumberOwnershipView(phone.getNumber(), phone.getCurrentCarrier(),
                phone.getProvisionedAt(), phone.getLastSwitchedAt(), null, null);
    }

    /** 号码归属查询（当前归属 + 活动申请）。 */
    @GetMapping("/{number}")
    public NumberOwnershipView ownership(@PathVariable String number) {
        return portingService.getOwnership(number);
    }

    /** 号码服务关系历史（切换/回退后的完整开通/关闭记录）。 */
    @GetMapping("/{number}/relationships")
    public List<RelationshipView> relationships(@PathVariable String number) {
        return portingService.relationshipHistory(number);
    }
}
