package dev.omatheusmesmo.qlawkus.model;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.faulttolerance.api.TypedGuard;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Probes the two bulkhead behaviours the SmallRye documentation leaves unstated, because the design
 * of the batch workload guard depends on both. Written as a throwaway probe first, in the same way
 * the TypedGuard assumptions were validated before #294 rewrote the breaker.
 *
 * <p>{@code @QuarkusTest} is mandatory: {@code TypedGuard.create()} resolves its SPI through
 * {@code CDI.current()} and fails outside a running container with a message that blames a missing
 * dependency instead.
 */
@QuarkusTest
class BulkheadSemanticsProbeTest {

    /**
     * Question 1: does adding a bulkhead move the fallback off the calling thread? The whole
     * per-call fallback bridge in {@link PrimaryChatGuard} is a {@link ThreadLocal}, so an offload
     * would break it silently rather than loudly.
     */
    @Test
    void fallbackStillRunsOnTheCallingThreadWithABulkhead() {
        AtomicReference<Thread> fallbackThread = new AtomicReference<>();
        TypedGuard<String> guard = TypedGuard.create(String.class)
                .withBulkhead().limit(2).done()
                .withFallback().handler(cause -> {
                    fallbackThread.set(Thread.currentThread());
                    return "fallback";
                }).done()
                .build();

        String result = guard.get(() -> {
            throw new IllegalStateException("primary down");
        });

        assertEquals("fallback", result);
        assertSame(Thread.currentThread(), fallbackThread.get(),
                "a bulkhead must not offload the fallback, or the ThreadLocal bridge breaks");
    }

    /**
     * Question 2: does a synchronous bulkhead queue the excess caller or reject it? The docs say
     * queuing is asynchronous-only, which decides whether a throttled job waits or skips its run.
     */
    @Test
    void synchronousBulkheadRejectsRatherThanQueues() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TypedGuard<String> guard = TypedGuard.create(String.class)
                .withBulkhead().limit(1).done()
                .build();

        Thread holder = new Thread(() -> guard.get(() -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "held";
        }));
        holder.start();
        assertTrue(inside.await(2, TimeUnit.SECONDS), "the first call should have entered the bulkhead");

        try {
            boolean rejected = false;
            try {
                guard.get(() -> "second");
            } catch (BulkheadException e) {
                rejected = true;
            }
            assertTrue(rejected, "a full synchronous bulkhead must reject, not queue");
        } finally {
            release.countDown();
            holder.join(2000);
        }
    }

    /**
     * Question 3: what happens when two guards claim the same circuit breaker name. This was expected
     * to be rejected - an earlier finding in this project recorded {@code "Circuit breaker already
     * exists"} - but on smallrye-fault-tolerance 6.11.2 it is accepted silently.
     *
     * <p>That matters for the workload design: distinct breaker names per workload are not enforced
     * by the library, so nothing would fail loudly if two workloads collided. They would each keep
     * their own breaker state, while {@code CircuitBreakerMaintenance} and therefore the readiness
     * check and the metric gauge could only ever observe one of them. Uniqueness is our invariant to
     * hold, which is why {@code WorkloadGuards.breakerName} derives it from the workload name instead
     * of letting callers supply one.
     */
    @Test
    void duplicateCircuitBreakerNamesAreAcceptedSilently() {
        String name = "qlawkus-probe-duplicate";
        TypedGuard.create(String.class).withCircuitBreaker().name(name).done().build();

        assertDoesNotThrow(
                () -> TypedGuard.create(String.class).withCircuitBreaker().name(name).done().build(),
                "if this starts throwing, the library began enforcing uniqueness and the note in "
                        + "WorkloadGuards about it being our invariant can be relaxed");
    }
}
