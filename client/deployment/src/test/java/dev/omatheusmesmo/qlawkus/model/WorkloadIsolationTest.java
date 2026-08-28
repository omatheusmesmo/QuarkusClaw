package dev.omatheusmesmo.qlawkus.model;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The isolation contract. What matters is not that a bulkhead exists but that batch and interactive
 * work cannot share fault-tolerance state, since a shared breaker is what let a nightly job degrade
 * the owner's next message.
 */
@QuarkusTest
class WorkloadIsolationTest {

    @Inject
    WorkloadGuards workloads;

    /** Both built-in workloads exist without anyone configuring them, so isolation is the default. */
    @Test
    void bothBuiltInWorkloadsArePresentWithoutConfiguration() {
        Set<String> names = workloads.names();

        assertTrue(names.contains(WorkloadContext.INTERACTIVE));
        assertTrue(names.contains(WorkloadContext.BATCH));
    }

    /** Distinct breaker names are the mechanism: one name registers one breaker, never two guards. */
    @Test
    void workloadsGetDistinctCircuitBreakerNames() {
        assertNotEquals(
                workloads.breakerName(WorkloadGuards.CHAT, WorkloadContext.INTERACTIVE),
                workloads.breakerName(WorkloadGuards.CHAT, WorkloadContext.BATCH));
        assertNotEquals(
                workloads.breakerName(WorkloadGuards.EMBEDDING, WorkloadContext.INTERACTIVE),
                workloads.breakerName(WorkloadGuards.EMBEDDING, WorkloadContext.BATCH));
    }

    /**
     * The interactive breaker keeps its original name. It is already a published metric label and the
     * key the readiness check reads, and renaming it would break every panel built on the history.
     */
    @Test
    void interactiveKeepsTheEstablishedBreakerNames() {
        assertEquals(ModelFallbackConfig.CIRCUIT_BREAKER_CHAT,
                workloads.breakerName(WorkloadGuards.CHAT, WorkloadContext.INTERACTIVE));
        assertEquals(ModelFallbackConfig.CIRCUIT_BREAKER_EMBEDDING,
                workloads.breakerName(WorkloadGuards.EMBEDDING, WorkloadContext.INTERACTIVE));
    }

    /** Every workload's breaker is registered at startup, or the health check reads an unknown name. */
    @Test
    void everyWorkloadBreakerIsRegisteredBeforeFirstUse() {
        for (String workload : workloads.names()) {
            for (String surface : Set.of(WorkloadGuards.CHAT, WorkloadGuards.EMBEDDING)) {
                String name = workloads.breakerName(surface, workload);
                assertNotEquals(null, CircuitBreakerMaintenance.get().currentState(name),
                        "breaker " + name + " should be registered at startup");
            }
        }
    }

    /** Background work is throttled to one call and refuses the fallback; interactive is neither. */
    @Test
    void defaultPoliciesThrottleBatchAndLeaveInteractiveAlone() {
        assertEquals(0, workloads.policy(WorkloadContext.INTERACTIVE).maxConcurrent());
        assertTrue(workloads.policy(WorkloadContext.INTERACTIVE).fallbackOnReject());

        assertEquals(1, workloads.policy(WorkloadContext.BATCH).maxConcurrent());
        assertEquals(false, workloads.policy(WorkloadContext.BATCH).fallbackOnReject());
    }

    /** The marker is thread-scoped and restores what it replaced, so nesting cannot leak. */
    @Test
    void workloadMarkerIsRestoredAfterTheCall() {
        assertEquals(WorkloadContext.INTERACTIVE, WorkloadContext.current());

        String inside = WorkloadContext.callAs(WorkloadContext.BATCH, WorkloadContext::current);

        assertEquals(WorkloadContext.BATCH, inside);
        assertEquals(WorkloadContext.INTERACTIVE, WorkloadContext.current());
    }

    /** A thread that never set the marker is interactive, so an unwrapped path is never throttled. */
    @Test
    void anotherThreadDoesNotInheritTheBatchMarker() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        WorkloadContext.runAs(WorkloadContext.BATCH, () -> {
            Thread other = new Thread(() -> seen.set(WorkloadContext.current()));
            other.start();
            try {
                other.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertEquals(WorkloadContext.INTERACTIVE, seen.get());
    }
}
