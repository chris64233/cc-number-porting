package com.chris64233.numberporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.CarrierRepository;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortEventRepository;
import com.chris64233.numberporting.repository.PortOrderRepository;
import com.chris64233.numberporting.repository.ServiceRelationRepository;
import com.chris64233.numberporting.support.TestClockConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** REST 端到端测试：申请/审核/切换/回退/查询接口与错误码。 */
@AutoConfigureMockMvc
class PortingApiTest extends AbstractApiTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    private String createOrderJson(String requestId) {
        return """
                {
                  "requestId": "%s",
                  "phoneNumber": "13600000001",
                  "fromCarrier": "CMCC",
                  "toCarrier": "CUCC",
                  "authCode": "API-1",
                  "windowStart": "%s",
                  "windowEnd": "%s"
                }""".formatted(requestId,
                TestClockConfig.now().minusSeconds(3600),
                TestClockConfig.now().plusSeconds(3600));
    }

    private MvcResult postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json == null ? "" : json))
                .andReturn();
    }

    private long createOrderAndGetId(String requestId) throws Exception {
        MvcResult result = postJson("/api/port-orders", createOrderJson(requestId));
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    @Test
    void full_flow_over_http_and_idempotent_submit() throws Exception {
        // 首次提交 201。
        mockMvc.perform(post("/api/port-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson("API-REQ-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.id").exists());

        long orderId = createOrderAndGetId("API-REQ-1");

        // 重复提交同 requestId 返回同一笔申请（幂等）。
        mockMvc.perform(post("/api/port-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson("API-REQ-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId));

        // 第二笔活动申请被拒绝 409 + 稳定错误码。
        mockMvc.perform(post("/api/port-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson("API-REQ-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACTIVE_ORDER_EXISTS"));

        // 审核 → 切换。
        mockMvc.perform(post("/api/port-orders/" + orderId + "/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/port-orders/" + orderId + "/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SWITCHED"));

        // 归属查询。
        mockMvc.perform(get("/api/numbers/13600000001/ownership"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCarrier").value("CUCC"))
                .andExpect(jsonPath("$.activeOrderId").value(orderId))
                .andExpect(jsonPath("$.ownershipConsistent").value(true));

        // 申请详情。
        mockMvc.perform(get("/api/port-orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authCode").value("API-1"))
                .andExpect(jsonPath("$.rollbackDeadline").isNotEmpty());

        // 时间线查询。
        mockMvc.perform(get("/api/numbers/13600000001/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ORDER_CREATED"))
                .andExpect(jsonPath("$[1].type").value("ORDER_APPROVED"))
                .andExpect(jsonPath("$[2].type").value("SWITCHED"))
                .andExpect(jsonPath("$[2].toStatus").value("SWITCHED"));

        // 回退后归属恢复。
        mockMvc.perform(post("/api/port-orders/" + orderId + "/rollback?reason=test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mockMvc.perform(get("/api/numbers/13600000001/ownership"))
                .andExpect(jsonPath("$.currentCarrier").value("CMCC"))
                .andExpect(jsonPath("$.activeOrderId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void validation_error_returns_400() throws Exception {
        mockMvc.perform(post("/api/port-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"phoneNumber\":\"13600000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void unknown_order_returns_404() throws Exception {
        mockMvc.perform(get("/api/port-orders/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
    }

    @Test
    void revoke_endpoint_closes_order() throws Exception {
        long orderId = createOrderAndGetId("API-REQ-3");
        mockMvc.perform(post("/api/admin/authorization-codes/API-1/revocation?reason=test"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/port-orders/" + orderId))
                .andExpect(jsonPath("$.status").value("AUTH_CODE_EXPIRED"));
    }
}

/** API 测试基类：MockMvc 全栈环境 + 每个测试前清库建基础数据。 */
@org.springframework.boot.test.context.SpringBootTest(classes = {
        NumberPortingApplication.class,
        TestClockConfig.class
})
abstract class AbstractApiTestBase {

    @Autowired protected PortOrderRepository orders;
    @Autowired protected PortEventRepository events;
    @Autowired protected AuthorizationCodeRepository authCodes;
    @Autowired protected ServiceRelationRepository relations;
    @Autowired protected PhoneNumberRepository numbers;
    @Autowired protected CarrierRepository carriers;
    @Autowired protected com.chris64233.numberporting.service.NumberProvisioningService provisioning;

    @BeforeEach
    void setUp() {
        events.deleteAllInBatch();
        orders.deleteAllInBatch();
        authCodes.deleteAllInBatch();
        relations.deleteAllInBatch();
        numbers.deleteAllInBatch();
        carriers.deleteAllInBatch();
        TestClockConfig.reset();
        provisioning.ensureCarrier("CMCC", "中国移动");
        provisioning.ensureCarrier("CUCC", "中国联通");
        provisioning.registerNumber("13600000001", "CMCC");
        provisioning.issueAuthCode("API-1", "13600000001",
                TestClockConfig.now().plus(Duration.ofHours(12)));
    }
}
