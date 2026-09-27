package com.chris64233.numberporting.support;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.CarrierRepository;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortEventRepository;
import com.chris64233.numberporting.repository.PortOrderRepository;
import com.chris64233.numberporting.repository.ServiceRelationRepository;
import com.chris64233.numberporting.service.NumberProvisioningService;
import com.chris64233.numberporting.service.PortOrderService;
import com.chris64233.numberporting.service.PortQueryService;

/** 集成测试基类：每个测试前清空数据、重置时钟、内置三家运营商与常用构造辅助。 */
@SpringBootTest(classes = {
        com.chris64233.numberporting.NumberPortingApplication.class,
        TestClockConfig.class
})
public abstract class AbstractPortingIntegrationTest {

    protected static final String CMCC = "CMCC";
    protected static final String CUCC = "CUCC";
    protected static final String CTCC = "CTCC";

    @Autowired protected PortOrderService orderService;
    @Autowired protected NumberProvisioningService provisioning;
    @Autowired protected PortQueryService queryService;
    @Autowired protected CarrierRepository carriers;
    @Autowired protected PhoneNumberRepository numbers;
    @Autowired protected ServiceRelationRepository relations;
    @Autowired protected AuthorizationCodeRepository authCodes;
    @Autowired protected PortOrderRepository orders;
    @Autowired protected PortEventRepository events;

    @org.junit.jupiter.api.BeforeEach
    void resetDatabaseAndClock() {
        events.deleteAllInBatch();
        orders.deleteAllInBatch();
        authCodes.deleteAllInBatch();
        relations.deleteAllInBatch();
        numbers.deleteAllInBatch();
        carriers.deleteAllInBatch();
        TestClockConfig.reset();
        provisioning.ensureCarrier(CMCC, "中国移动");
        provisioning.ensureCarrier(CUCC, "中国联通");
        provisioning.ensureCarrier(CTCC, "中国电信");
    }

    /** 号码入网。 */
    protected void register(String number, String carrier) {
        provisioning.registerNumber(number, carrier);
    }

    /** 签发 ttl 有效的授权码。 */
    protected String issueCode(String code, String number, Duration ttl) {
        provisioning.issueAuthCode(code, number, ttl);
        return code;
    }

    /** 签发 24 小时有效的授权码。 */
    protected String issueCode(String code, String number) {
        return issueCode(code, number, Duration.ofHours(24));
    }

    /** 提交一笔标准申请（窗口为当前时刻前后 1 小时）。 */
    protected com.chris64233.numberporting.domain.PortOrder submitOrder(
            String requestId, String number, String from, String to, String code) {
        Instant now = TestClockConfig.now();
        return orderService.createOrder(requestId, number, from, to, code,
                now.minusSeconds(3600), now.plusSeconds(3600));
    }

    /** 提交 → 审核 → 切换 的完整前置，返回申请。 */
    protected com.chris64233.numberporting.domain.PortOrder approvedOrder(
            String requestId, String number, String from, String to, String code) {
        var order = submitOrder(requestId, number, from, to, code);
        return orderService.approve(order.getId());
    }

    protected com.chris64233.numberporting.domain.PortOrder switchedOrder(
            String requestId, String number, String from, String to, String code) {
        var order = approvedOrder(requestId, number, from, to, code);
        return orderService.switch_(order.getId());
    }
}
