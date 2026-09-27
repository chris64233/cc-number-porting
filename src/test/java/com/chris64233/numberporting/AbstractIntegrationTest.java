package com.chris64233.numberporting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.service.AuthorizationCodeService;
import com.chris64233.numberporting.service.NumberService;
import com.chris64233.numberporting.service.view.SubmitApplicationCommand;
import com.chris64233.numberporting.testsupport.MutableClock;
import com.chris64233.numberporting.testsupport.TestClockConfiguration;

/**
 * 集成测试基类：注入可控时钟与领域服务；每个测试前清空全部业务表并重置时钟，
 * 保证测试间相互独立（并发测试使用真实事务，不依赖方法级回滚）。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected NumberService numberService;

    @Autowired
    protected AuthorizationCodeService authorizationCodeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabaseAndClock() {
        clock.setInstant(TestClockConfiguration.BASE);
        // TRUNCATE 不触发行级触发器，因此可以清空 append-only 的 porting_event
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("porting_event", "service_relationship", "porting_application",
                "authorization_code", "phone_number")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    protected void provision(String number, Carrier carrier) {
        numberService.provision(number, carrier);
    }

    protected String issueCode(String number, Duration validity) {
        return authorizationCodeService.issue(number, validity).getCode();
    }

    protected SubmitApplicationCommand command(String applicationId, String number,
                                               Carrier donor, Carrier recipient, String authCode,
                                               Instant windowStart, Instant windowEnd) {
        return new SubmitApplicationCommand(applicationId, number, donor.name(), recipient.name(),
                authCode, windowStart, windowEnd);
    }

    /** 默认窗口：BASE-1h .. BASE+2h，有效期授权码另由 issueCode 控制。 */
    protected SubmitApplicationCommand defaultCommand(String applicationId, String number,
                                                      Carrier donor, Carrier recipient, String authCode) {
        return command(applicationId, number, donor, recipient, authCode,
                TestClockConfiguration.BASE.minus(Duration.ofHours(1)),
                TestClockConfiguration.BASE.plus(Duration.ofHours(2)));
    }
}
