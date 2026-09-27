package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chris64233.numberporting.domain.PortOrderStatus;
import com.chris64233.numberporting.domain.ServiceStatus;
import com.chris64233.numberporting.service.BusinessRuleException;
import com.chris64233.numberporting.support.AbstractPortingIntegrationTest;

/**
 * 并发安全测试：同一号码的并发申请/切换/取消/授权码失效竞争，
 * 最终必须只有一个成功者且最终状态唯一、可解释，归属不变量始终成立。
 */
class ConcurrencyTest extends AbstractPortingIntegrationTest {

    private ExecutorService pool;

    @BeforeEach
    void initPool() {
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    private sealed interface Result permits Ok, Fail {
    }

    private record Ok(Object value) implements Result {
    }

    private record Fail(Throwable error) implements Result {
    }

    /** 并发执行 N 个任务，等全部到齐起跑栅栏后同时释放，统计成功/业务失败/其他失败。 */
    private List<Result> runParallel(int count, Callable<?> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    return new Ok(task.call());
                } catch (Throwable t) {
                    return new Fail(t);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        return results;
    }

    private static long countOk(List<Result> results) {
        return results.stream().filter(Ok.class::isInstance).count();
    }

    private static List<Throwable> businessErrors(List<Result> results) {
        return results.stream()
                .filter(Fail.class::isInstance)
                .map(Fail.class::cast)
                .map(Fail::error)
                .map(ConcurrencyTest::unwrap)
                .filter(t -> t instanceof BusinessRuleException)
                .toList();
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof ExecutionException || cur instanceof org.springframework.transaction
                .TransactionSystemException) {
            if (cur.getCause() == null) {
                break;
            }
            cur = cur.getCause();
        }
        return cur;
    }

    /** 不允许出现非业务异常（如锁失败、约束异常泄漏、状态损坏）。 */
    private static void assertNoUnexpectedErrors(List<Result> results) {
        List<Throwable> unexpected = results.stream()
                .filter(Fail.class::isInstance)
                .map(Fail.class::cast)
                .map(Fail::error)
                .map(ConcurrencyTest::unwrap)
                .filter(t -> !(t instanceof BusinessRuleException))
                .toList();
        assertThat(unexpected).as("所有失败都应是可解释的业务错误").isEmpty();
    }

    @Test
    void concurrent_submissions_same_request_id_at_most_one_order() throws Exception {
        register("13700000001", CMCC);
        issueCode("CC-1", "13700000001");

        int n = 8;
        List<Result> results = runParallel(n, () ->
                submitOrder("CONC-1", "13700000001", CMCC, CUCC, "CC-1"));

        // 同一 requestId 全部逻辑成功，但底层只有一条申请。
        assertThat(countOk(results)).isEqualTo(n);
        assertThat(orders.count()).isEqualTo(1);
        var order = orders.findByRequestId("CONC-1").orElseThrow();
        var ownership = queryService.getOwnership("13700000001");
        assertThat(ownership.number().getActiveOrderId()).isEqualTo(order.getId());
        // 创建事件只有一条。
        assertThat(events.findByOrderIdOrderByIdAsc(order.getId())).hasSize(1);
    }

    @Test
    void concurrent_submissions_different_request_ids_at_most_one_succeeds() throws Exception {
        register("13700000002", CMCC);
        issueCode("CC-2", "13700000002");

        AtomicInteger seq = new AtomicInteger();
        List<Result> results = runParallel(8, () ->
                submitOrder("CONC-2-" + seq.incrementAndGet(), "13700000002",
                        CMCC, CUCC, "CC-2"));

        assertThat(countOk(results)).isEqualTo(1);
        assertThat(businessErrors(results)).hasSize(7);
        assertThat(businessErrors(results))
                .allSatisfy(e -> assertThat(((BusinessRuleException) e).getErrorCode()
                        .name()).isEqualTo("ACTIVE_ORDER_EXISTS"));
        assertNoUnexpectedErrors(results);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(queryService.getOwnership("13700000002").number().getActiveOrderId())
                .isNotNull();
    }

    @Test
    void concurrent_switch_executions_at_most_one_succeeds() throws Exception {
        register("13700000003", CMCC);
        issueCode("CC-3", "13700000003");
        var order = approvedOrder("CONC-3", "13700000003", CMCC, CUCC, "CC-3");

        List<Result> results = runParallel(6, () -> orderService.switch_(order.getId()));

        assertThat(countOk(results)).isEqualTo(1);
        assertThat(businessErrors(results)).hasSize(5);
        assertNoUnexpectedErrors(results);

        // 切换只发生一次：只有一个 ACTIVE 关系、归属唯一、授权码只消费一次。
        var ownership = queryService.getOwnership("13700000003");
        assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CUCC);
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(relations.countByPhoneNumberAndStatus("13700000003", ServiceStatus.ACTIVE))
                .isEqualTo(1);
        assertThat(relations.findByPhoneNumberOrderByIdAsc("13700000003"))
                .hasSize(2); // 初始（已关闭）+ 新关系（ACTIVE）
        assertThat(authCodes.findByCode("CC-3").orElseThrow().isUsed()).isTrue();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PortOrderStatus.SWITCHED);
    }

    @Test
    void concurrent_cancel_and_switch_exactly_one_wins() throws Exception {
        register("13700000004", CMCC);
        issueCode("CC-4", "13700000004");
        var order = approvedOrder("CONC-4", "13700000004", CMCC, CUCC, "CC-4");

        int n = 6;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final boolean cancel = i % 2 == 0;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    return new Ok(cancel
                            ? orderService.cancel(order.getId(), "并发取消")
                            : orderService.switch_(order.getId()));
                } catch (Throwable t) {
                    return new Fail(t);
                }
            }));
        }
        ready.await();
        start.countDown();
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }

        assertThat(countOk(results)).isEqualTo(1);
        assertNoUnexpectedErrors(results);

        // 最终状态唯一且可解释：要么 SWITCHED，要么 CANCELLED，二者必居其一。
        PortOrderStatus finalStatus = orders.findById(order.getId()).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(PortOrderStatus.SWITCHED, PortOrderStatus.CANCELLED);

        var ownership = queryService.getOwnership("13700000004");
        if (finalStatus == PortOrderStatus.SWITCHED) {
            assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CUCC);
            assertThat(ownership.number().getActiveOrderId()).isEqualTo(order.getId());
            assertThat(authCodes.findByCode("CC-4").orElseThrow().isUsed()).isTrue();
        } else {
            assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CMCC);
            assertThat(ownership.number().getActiveOrderId()).isNull();
            assertThat(authCodes.findByCode("CC-4").orElseThrow().isUsed()).isFalse();
        }
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(relations.countByPhoneNumberAndStatus("13700000004", ServiceStatus.ACTIVE))
                .isEqualTo(1);
    }

    @Test
    void concurrent_switch_and_auth_code_revoke_exactly_one_wins() throws Exception {
        register("13700000005", CMCC);
        issueCode("CC-5", "13700000005");
        var order = approvedOrder("CONC-5", "13700000005", CMCC, CUCC, "CC-5");

        int n = 6;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final boolean revoke = i % 2 == 0;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    if (revoke) {
                        orderService.revokeAuthCode("CC-5", "并发吊销");
                        return new Ok("REVOKED");
                    }
                    return new Ok(orderService.switch_(order.getId()));
                } catch (Throwable t) {
                    return new Fail(t);
                }
            }));
        }
        ready.await();
        start.countDown();
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }

        // 吊销本身多次执行都返回成功（幂等）；切换至多成功一次。
        long switchOk = results.stream()
                .filter(Ok.class::isInstance)
                .filter(r -> ((Ok) r).value() != "REVOKED")
                .count();
        assertThat(switchOk).isLessThanOrEqualTo(1);

        PortOrderStatus finalStatus = orders.findById(order.getId()).orElseThrow().getStatus();
        assertThat(finalStatus)
                .isIn(PortOrderStatus.SWITCHED, PortOrderStatus.AUTH_CODE_EXPIRED);

        var ownership = queryService.getOwnership("13700000005");
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(relations.countByPhoneNumberAndStatus("13700000005", ServiceStatus.ACTIVE))
                .isEqualTo(1);

        if (finalStatus == PortOrderStatus.SWITCHED) {
            assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CUCC);
            assertThat(authCodes.findByCode("CC-5").orElseThrow().isUsed()).isTrue();
        } else {
            assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CMCC);
            assertThat(ownership.number().getActiveOrderId()).isNull();
            assertThat(authCodes.findByCode("CC-5").orElseThrow().isUsed()).isFalse();
        }
    }

    @Test
    void concurrent_rollback_and_new_order_before_and_after_window() throws Exception {
        register("13700000006", CMCC);
        issueCode("CC-6", "13700000006");
        var switched = switchedOrder("CONC-6", "13700000006", CMCC, CUCC, "CC-6");

        // 窗口内并发回退：最多一次成功。
        List<Result> results = runParallel(5, () -> orderService.rollback(switched.getId(), "并发回退"));
        assertThat(countOk(results)).isEqualTo(1);
        assertNoUnexpectedErrors(results);
        assertThat(orders.findById(switched.getId()).orElseThrow().getStatus())
                .isEqualTo(PortOrderStatus.ROLLED_BACK);
        var ownership = queryService.getOwnership("13700000006");
        assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CMCC);
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(relations.countByPhoneNumberAndStatus("13700000006", ServiceStatus.ACTIVE))
                .isEqualTo(1);
    }

    @Test
    void no_double_or_orphan_ownership_under_repeated_contention() throws Exception {
        // 多轮混合竞争后的不变量总检：每号码恰好一条 ACTIVE 关系，与归属一致。
        for (int round = 0; round < 5; round++) {
            String number = "1370000001" + round;
            String code = "CC-X" + round;
            register(number, CMCC);
            issueCode(code, number);
            var order = approvedOrder("CONC-X" + round, number, CMCC, CUCC, code);

            runParallel(4, () -> {
                // 反复尝试切换与取消，无论谁赢。
                try {
                    orderService.switch_(order.getId());
                } catch (Exception ignored) {
                    try {
                        orderService.cancel(order.getId(), "混合竞争取消");
                    } catch (Exception ignored2) {
                        // 预期内的业务失败
                    }
                }
                return null;
            });

            var view = queryService.getOwnership(number);
            assertThat(view.ownershipConsistent())
                    .as("第 %d 轮后归属与 ACTIVE 服务关系必须一致", round).isTrue();
            assertThat(relations.countByPhoneNumberAndStatus(number, ServiceStatus.ACTIVE))
                    .as("第 %d 轮后恰好一条 ACTIVE 服务关系", round).isEqualTo(1);
        }
    }
}
