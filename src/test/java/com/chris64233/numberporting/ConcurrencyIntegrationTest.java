package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.domain.RelationshipStatus;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.repository.PortingApplicationRepository;
import com.chris64233.numberporting.repository.ServiceRelationshipRepository;
import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.service.view.ApplicationView;

/**
 * 并发正确性：所有同号码写路径在号码行锁上串行化，最多一笔成功，失败方拿到明确错误码；
 * 绝不出现双归属 / 无归属 / 多笔活动申请。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private PortingService portingService;

    @Autowired
    private PortingApplicationRepository applicationRepository;

    @Autowired
    private ServiceRelationshipRepository relationshipRepository;

    private ExecutorService pool;

    @BeforeEach
    void initPool() {
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @org.junit.jupiter.api.AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    private <T> List<Result<T>> runParallel(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Future<Result<T>>> futures = tasks.stream()
                .map(t -> pool.submit((Callable<Result<T>>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return Result.ok(t.call());
                    } catch (Throwable e) {
                        return Result.fail(e);
                    }
                }))
                .toList();
        List<Result<T>> results = new ArrayList<>();
        for (Future<Result<T>> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    record Result<T>(T value, Throwable error) {
        static <T> Result<T> ok(T v) {
            return new Result<>(v, null);
        }

        static <T> Result<T> fail(Throwable e) {
            return new Result<>(null, e);
        }

        boolean succeeded() {
            return error == null;
        }
    }

    @Test
    void concurrentSubmissionsForSameNumberOnlyOneSucceeds() throws Exception {
        String number = "13900000001";
        provision(number, Carrier.CHINA_MOBILE);
        List<String> codes = IntStream.range(0, THREADS)
                .mapToObj(i -> issueCode(number, Duration.ofHours(2))).toList();

        List<Callable<ApplicationView>> tasks = IntStream.range(0, THREADS)
                .<Callable<ApplicationView>>mapToObj(i -> () -> portingService.submit(
                        defaultCommand("submit-" + i, number, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM,
                                codes.get(i))))
                .toList();

        List<Result<ApplicationView>> results = runParallel(tasks);

        long success = results.stream().filter(Result::succeeded).count();
        assertThat(success).as("exactly one concurrent submission must succeed").isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.succeeded()).findFirst().orElseThrow().error())
                .isInstanceOf(BusinessRuleException.class);
        assertThat(((BusinessRuleException) results.stream().filter(r -> !r.succeeded())
                .findFirst().orElseThrow().error()).getErrorCode())
                .isIn(ErrorCode.ACTIVE_APPLICATION_EXISTS, ErrorCode.CONCURRENT_MODIFICATION);

        ApplicationView winner = results.stream().filter(Result::succeeded)
                .map(Result::value).findFirst().orElseThrow();
        assertThat(winner.status()).isEqualTo(PortingStatus.PENDING_REVIEW);
        assertThat(applicationRepository.findByPhoneNumber_NumberAndActiveSlotNotNull(number))
                .isPresent();
        // 只有赢家的授权码被消费
        assertThat(relationshipRepository.countActiveByNumber(number, RelationshipStatus.ACTIVE))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateSubmitsWithSameApplicationIdAreIdempotent() throws Exception {
        String number = "13900000002";
        provision(number, Carrier.CHINA_MOBILE);
        String code = issueCode(number, Duration.ofHours(2));

        List<Callable<ApplicationView>> tasks = IntStream.range(0, THREADS)
                .<Callable<ApplicationView>>mapToObj(i -> () -> portingService.submit(
                        defaultCommand("duplicate-id", number, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code)))
                .toList();

        List<Result<ApplicationView>> results = runParallel(tasks);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.succeeded()).isTrue();
            assertThat(r.value().applicationId()).isEqualTo("duplicate-id");
        });
        assertThat(applicationRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentSwitchesOfSameApplicationOnlyOneSucceedsAndOwnershipIsConsistent() throws Exception {
        String number = "13900000003";
        provision(number, Carrier.CHINA_MOBILE);
        String code = issueCode(number, Duration.ofHours(2));
        portingService.submit(defaultCommand("sw", number, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
        portingService.approve("sw");

        List<Callable<ApplicationView>> tasks = IntStream.range(0, THREADS)
                .<Callable<ApplicationView>>mapToObj(i -> () -> portingService.switchApplication("sw"))
                .toList();

        List<Result<ApplicationView>> results = runParallel(tasks);

        long success = results.stream().filter(Result::succeeded).count();
        assertThat(success).as("exactly one concurrent switch must succeed").isEqualTo(1);
        results.stream().filter(r -> !r.succeeded()).forEach(r -> {
            assertThat(r.error()).isInstanceOf(BusinessRuleException.class);
            assertThat(((BusinessRuleException) r.error()).getErrorCode())
                    .as("loser error: %s", r.error().toString())
                    .isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        });

        // 唯一归属：恰好一条 ACTIVE 关系且与号码归属一致为联通
        assertThat(portingService.getOwnership(number).currentCarrier()).isEqualTo(Carrier.CHINA_UNICOM);
        assertThat(relationshipRepository.countActiveByNumber(number, RelationshipStatus.ACTIVE)).isEqualTo(1);
        assertThat(relationshipRepository.findActiveCarrier(number, RelationshipStatus.ACTIVE))
                .contains(Carrier.CHINA_UNICOM);
        assertThat(relationshipRepository.findHistoryByNumber(number)).hasSize(2);
    }

    @Test
    void concurrentCancelAndSwitchEndsInExactlyOneExplainableState() throws Exception {
        String number = "13900000004";
        provision(number, Carrier.CHINA_MOBILE);
        String code = issueCode(number, Duration.ofHours(2));
        portingService.submit(defaultCommand("cs", number, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
        portingService.approve("cs");

        List<Callable<String>> tasks = List.of(
                () -> {
                    portingService.switchApplication("cs");
                    return "SWITCHED";
                },
                () -> {
                    portingService.cancel("cs");
                    return "CANCELLED";
                });

        List<Result<String>> results = runParallel(tasks);
        long success = results.stream().filter(Result::succeeded).count();
        assertThat(success).as("exactly one of cancel/switch wins").isEqualTo(1);

        PortingStatus finalStatus = portingService.getApplication("cs").status();
        Carrier ownership = portingService.getOwnership(number).currentCarrier();

        // 最终状态唯一，且归属、关系与状态自洽
        if (finalStatus == PortingStatus.SWITCHED) {
            assertThat(ownership).isEqualTo(Carrier.CHINA_UNICOM);
            assertThat(relationshipRepository.findActiveCarrier(number, RelationshipStatus.ACTIVE))
                    .contains(Carrier.CHINA_UNICOM);
            assertThat(results.stream().filter(Result::succeeded).map(Result::value).findFirst().orElseThrow())
                    .isEqualTo("SWITCHED");
        } else {
            assertThat(finalStatus).isEqualTo(PortingStatus.CANCELLED);
            assertThat(ownership).isEqualTo(Carrier.CHINA_MOBILE);
            assertThat(relationshipRepository.findActiveCarrier(number, RelationshipStatus.ACTIVE))
                    .contains(Carrier.CHINA_MOBILE);
        }
        assertThat(relationshipRepository.countActiveByNumber(number, RelationshipStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void concurrentRevokeAndSubmitEndsInExactlyOneExplainableState() throws Exception {
        String number = "13900000005";
        provision(number, Carrier.CHINA_MOBILE);
        String code = issueCode(number, Duration.ofHours(2));

        List<Callable<String>> tasks = List.of(
                () -> {
                    portingService.submit(defaultCommand("rs", number, Carrier.CHINA_MOBILE,
                            Carrier.CHINA_UNICOM, code));
                    return "SUBMITTED";
                },
                () -> {
                    authorizationCodeService.revoke(code);
                    return "REVOKED";
                });

        List<Result<String>> results = runParallel(tasks);

        PortingStatus activeStatus = applicationRepository
                .findByPhoneNumber_NumberAndActiveSlotNotNull(number)
                .map(a -> a.getStatus()).orElse(null);

        // 两种合法结局互斥：
        // A) 提交先到：申请 PENDING_REVIEW；撤销针对已消费授权码为空操作
        // B) 撤销先到：提交被 AUTH_CODE_REVOKED 明确拒绝，号码无活动申请、归属不变
        if (activeStatus == PortingStatus.PENDING_REVIEW) {
            assertThat(results.get(0).succeeded()).isTrue();
            assertThat(relationshipRepository.findActiveCarrier(number, RelationshipStatus.ACTIVE))
                    .contains(Carrier.CHINA_MOBILE);
        } else {
            assertThat(activeStatus).isNull();
            assertThat(results.get(0).succeeded()).isFalse();
            Throwable error = results.get(0).error();
            assertThat(error).isInstanceOf(BusinessRuleException.class);
            assertThat(((BusinessRuleException) error).getErrorCode()).isEqualTo(ErrorCode.AUTH_CODE_REVOKED);
            assertThat(portingService.getOwnership(number).currentCarrier()).isEqualTo(Carrier.CHINA_MOBILE);
        }
        assertThat(relationshipRepository.countActiveByNumber(number, RelationshipStatus.ACTIVE)).isEqualTo(1);
    }
}
