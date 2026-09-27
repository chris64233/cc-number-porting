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

import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.service.view.ApplicationView;
import com.chris64233.numberporting.service.view.EventView;
import com.chris64233.numberporting.service.view.SubmitApplicationCommand;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/port-applications")
public class PortingController {

    private final PortingService portingService;

    public PortingController(PortingService portingService) {
        this.portingService = portingService;
    }

    /** 提交携转申请（申请号幂等；并发同号码最多一笔成功）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationView submit(@Valid @RequestBody SubmitPortingRequest request) {
        return portingService.submit(new SubmitApplicationCommand(
                request.applicationId(), request.number(), request.donorCarrier(), request.recipientCarrier(),
                request.authCode(), request.windowStart(), request.windowEnd()));
    }

    /** 申请详情。 */
    @GetMapping("/{applicationId}")
    public ApplicationView get(@PathVariable String applicationId) {
        return portingService.getApplication(applicationId);
    }

    /** 申请维度事件时间线。 */
    @GetMapping("/{applicationId}/events")
    public List<EventView> applicationEvents(@PathVariable String applicationId) {
        return portingService.applicationTimeline(applicationId);
    }

    /** 审核通过 → APPROVED（待切换）。 */
    @PostMapping("/{applicationId}/approve")
    public ApplicationView approve(@PathVariable String applicationId) {
        return portingService.approve(applicationId);
    }

    /** 审核拒绝。 */
    @PostMapping("/{applicationId}/reject")
    public ApplicationView reject(@PathVariable String applicationId,
                                  @RequestBody(required = false) RejectApplicationRequest request) {
        return portingService.reject(applicationId, request == null ? null : request.reason());
    }

    /** 取消申请（PENDING_REVIEW / APPROVED）。 */
    @PostMapping("/{applicationId}/cancel")
    public ApplicationView cancel(@PathVariable String applicationId) {
        return portingService.cancel(applicationId);
    }

    /** 执行切换：原子更新归属、关闭旧关系、建立新关系。 */
    @PostMapping("/{applicationId}/switch")
    public ApplicationView switchNow(@PathVariable String applicationId) {
        return portingService.switchApplication(applicationId);
    }

    /** 回退窗口内受控回退：完整恢复原归属与服务关系。 */
    @PostMapping("/{applicationId}/rollback")
    public ApplicationView rollback(@PathVariable String applicationId) {
        return portingService.rollback(applicationId);
    }
}
