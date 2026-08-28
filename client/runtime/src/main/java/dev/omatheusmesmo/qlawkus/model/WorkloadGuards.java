package dev.omatheusmesmo.qlawkus.model;

import io.quarkus.logging.Log;
import io.smallrye.faulttolerance.api.TypedGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The naming and policy rules shared by every workload guard. The guards themselves are built by
 * {@link PrimaryChatGuard} and {@link EmbeddingGuard}, which own their response types; this holds
 * only what both must agree on, so a breaker name cannot drift between the guard that registers it
 * and the health check that reads it.
 */
@ApplicationScoped
public class WorkloadGuards {

    /** The chat surface, matching the {@code surface} tag already used by the model meters. */
    public static final String CHAT = "chat";

    /** The embedding surface. Separate from chat because a failure on one implies nothing about the other. */
    public static final String EMBEDDING = "embedding";

    @Inject
    WorkloadConfig config;

    /**
     * Every workload to build a guard for. The two built-in names are always present even when
     * nothing is configured, so the default distribution isolates background work without anyone
     * having to write configuration to get it.
     */
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        names.add(WorkloadContext.INTERACTIVE);
        names.add(WorkloadContext.BATCH);
        names.addAll(config.workloads().keySet());
        return names;
    }

    /**
     * The circuit breaker name for a workload and surface. The interactive workload keeps the
     * original names because they are already published as metric labels and read by
     * {@code ModelReadinessCheck}; a metric name is a contract, and renaming one silently breaks the
     * history behind any panel built on it.
     *
     * <p>Uniqueness is derived here rather than supplied by callers because the library does not
     * enforce it: smallrye-fault-tolerance 6.11.2 accepts two guards registering the same breaker
     * name without complaint, and the collision would only show up as a readiness check and a gauge
     * reporting one workload's breaker while another silently kept its own.
     */
    public String breakerName(String surface, String workload) {
        if (WorkloadContext.INTERACTIVE.equals(workload)) {
            return CHAT.equals(surface)
                    ? ModelFallbackConfig.CIRCUIT_BREAKER_CHAT
                    : ModelFallbackConfig.CIRCUIT_BREAKER_EMBEDDING;
        }
        return "qlawkus-" + workload + "-" + surface;
    }

    /**
     * Applies the workload's admission policy to a guard under construction. A limit of zero adds no
     * bulkhead at all rather than a bulkhead of unlimited size, so the interactive path keeps exactly
     * the behaviour it had before workloads existed.
     */
    public <T> void applyBulkhead(TypedGuard.Builder<T> builder, String workload) {
        int limit = policy(workload).maxConcurrent();
        if (limit > 0) {
            builder.withBulkhead().limit(limit).done();
            Log.debugf("Workload %s limited to %d concurrent model calls", workload, limit);
        }
    }

    /** The declared policy for a workload, or the defaults when it was never configured. */
    public WorkloadConfig.Workload policy(String workload) {
        WorkloadConfig.Workload declared = config.workloads().get(workload);
        return declared == null ? Defaults.forName(workload) : declared;
    }

    /**
     * Built-in policies, used when configuration declares nothing. Background work gets one call at a
     * time and no fallback on rejection: being throttled means skipping the run, and the schedule
     * brings it back. That is only safe because the jobs are re-runnable, which was established when
     * shutdown started abandoning them mid-flight.
     */
    private static final class Defaults implements WorkloadConfig.Workload {

        private final int maxConcurrent;
        private final boolean fallbackOnReject;

        private Defaults(int maxConcurrent, boolean fallbackOnReject) {
            this.maxConcurrent = maxConcurrent;
            this.fallbackOnReject = fallbackOnReject;
        }

        static WorkloadConfig.Workload forName(String workload) {
            return WorkloadContext.INTERACTIVE.equals(workload)
                    ? new Defaults(0, true)
                    : new Defaults(1, false);
        }

        @Override
        public int maxConcurrent() {
            return maxConcurrent;
        }

        @Override
        public boolean fallbackOnReject() {
            return fallbackOnReject;
        }
    }
}
