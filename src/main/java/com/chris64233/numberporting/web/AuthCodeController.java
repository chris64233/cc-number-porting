package com.chris64233.numberporting.web;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.numberporting.domain.AuthorizationCode;
import com.chris64233.numberporting.service.AuthorizationCodeService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth-codes")
public class AuthCodeController {

    private final AuthorizationCodeService authCodeService;

    public AuthCodeController(AuthorizationCodeService authCodeService) {
        this.authCodeService = authCodeService;
    }

    /** 由号码当前归属运营商签发授权码。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthCodeResponse issue(@Valid @RequestBody IssueAuthCodeRequest request) {
        Duration validity = request.validityMinutes() == null
                ? null : Duration.ofMinutes(request.validityMinutes());
        return toResponse(authCodeService.issue(request.number(), validity));
    }

    /** 撤销授权码；引用它的活动申请同步失效。对终态授权码幂等。 */
    @PostMapping("/{code}/revoke")
    public AuthCodeResponse revoke(@PathVariable String code) {
        return toResponse(authCodeService.revoke(code));
    }

    private static AuthCodeResponse toResponse(AuthorizationCode c) {
        return new AuthCodeResponse(c.getCode(), c.getPhoneNumber().getNumber(), c.getCarrier().name(),
                c.getStatus(), c.getIssuedAt(), c.getExpiresAt(), c.getConsumedAt(),
                c.getConsumedByApplicationId(), c.getRevokedAt());
    }
}
