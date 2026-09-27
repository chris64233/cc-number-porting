package com.chris64233.numberporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.chris64233.numberporting.domain.Carrier;

import tools.jackson.databind.ObjectMapper;

/**
 * REST 端到端：预置、签发授权码、提交、审核、切换、查询归属/时间线，
 * 以及校验失败、资源不存在、唯一活动申请冲突、幂等重放等错误码。
 */
@AutoConfigureMockMvc
class PortingApiIntegrationTest extends AbstractIntegrationTest {

    private static final String NUMBER = "13700000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        provision(NUMBER, Carrier.CHINA_MOBILE);
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        String code = issueCode(NUMBER, Duration.ofHours(4));

        Map<String, Object> body = Map.of(
                "applicationId", "http-1",
                "number", NUMBER,
                "donorCarrier", "CHINA_MOBILE",
                "recipientCarrier", "CHINA_UNICOM",
                "authCode", code,
                "windowStart", Instant.now(clock).minus(Duration.ofHours(1)).toString(),
                "windowEnd", Instant.now(clock).plus(Duration.ofHours(2)).toString());

        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(post("/api/port-applications/http-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/port-applications/http-1/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SWITCHED"))
                .andExpect(jsonPath("$.rollbackDeadline").exists());

        mockMvc.perform(get("/api/numbers/" + NUMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCarrier").value("CHINA_UNICOM"))
                .andExpect(jsonPath("$.activeApplicationId").value("http-1"));

        mockMvc.perform(get("/api/numbers/" + NUMBER + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPLICATION_SUBMITTED"))
                .andExpect(jsonPath("$[*].eventType").value(
                        org.hamcrest.Matchers.hasItems("SWITCH_COMPLETED")));

        mockMvc.perform(get("/api/port-applications/http-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.donorCarrier").value("CHINA_MOBILE"));
    }

    @Test
    void idempotentReplayReturnsSameApplication() throws Exception {
        String code = issueCode(NUMBER, Duration.ofHours(4));
        Map<String, Object> body = submitBody("http-2", code);

        mockMvc.perform(post("/api/port-applications")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotentReplay").value(true));
    }

    @Test
    void validationErrorsAreReportedWithStableErrorCode() throws Exception {
        // 号码格式非法
        Map<String, Object> bad = Map.of(
                "applicationId", "http-3",
                "number", "abc",
                "donorCarrier", "CHINA_MOBILE",
                "recipientCarrier", "CHINA_UNICOM",
                "authCode", "X",
                "windowStart", Instant.now(clock).minusSeconds(60).toString(),
                "windowEnd", Instant.now(clock).plusSeconds(3600).toString());
        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownResourcesAndConflictsReturnExplicitErrorCodes() throws Exception {
        mockMvc.perform(get("/api/numbers/13999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NUMBER_NOT_FOUND"));

        mockMvc.perform(get("/api/port-applications/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("APPLICATION_NOT_FOUND"));

        String code = issueCode(NUMBER, Duration.ofHours(4));
        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody("http-4", code))))
                .andExpect(status().isCreated());
        String code2 = issueCode(NUMBER, Duration.ofHours(4));
        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody("http-5", code2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACTIVE_APPLICATION_EXISTS"));
    }

    @Test
    void reusingAnAuthCodeReturnsAlreadyUsedError() throws Exception {
        String code = issueCode(NUMBER, Duration.ofHours(4));
        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody("http-6", code))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/port-applications/http-6/cancel")).andExpect(status().isOk());

        mockMvc.perform(post("/api/port-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody("http-7", code))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUTH_CODE_ALREADY_USED"));
    }

    private Map<String, Object> submitBody(String applicationId, String code) {
        return Map.of(
                "applicationId", applicationId,
                "number", NUMBER,
                "donorCarrier", "CHINA_MOBILE",
                "recipientCarrier", "CHINA_UNICOM",
                "authCode", code,
                "windowStart", Instant.now(clock).minus(Duration.ofHours(1)).toString(),
                "windowEnd", Instant.now(clock).plus(Duration.ofHours(2)).toString());
    }
}
