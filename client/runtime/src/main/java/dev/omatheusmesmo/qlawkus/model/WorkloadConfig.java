package dev.omatheusmesmo.qlawkus.model;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithParentName;

import java.util.Map;

/**
 * Workloads that reach the model, each with its own fault-tolerance state.
 *
 * <p>The agent talks to one provider for two very different reasons: answering the owner, and doing
 * background work about the owner. Sharing one circuit breaker between them means a nightly job that
 * trips the provider's rate limit opens the breaker for the next interactive turn, so the agent
 * answers from the fallback because it was busy talking to itself. Separate workloads exist to stop
 * that, and the isolation is structural: SmallRye gives each guard object its own instance of every
 * strategy, so two guards cannot share breaker state even by accident.
 *
 * <p>Keyed by name rather than fixed to two entries so a distribution can split further - isolating
 * one expensive job from the rest of the batch, for instance - without a code change. Adding an entry
 * here creates a guard with its own breaker; referencing an undeclared name falls back to
 * {@value WorkloadContext#INTERACTIVE} rather than failing a turn.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "qlawkus.model.workload")
public interface WorkloadConfig {

    /**
     * The declared workloads, keyed by name. Defaults supply {@code interactive} and {@code batch};
     * the names are ordinary config keys, so more can be added.
     */
    @WithParentName
    Map<String, Workload> workloads();

    interface Workload {

        /**
         * How many calls of this workload may reach the model at once. {@code 0} disables the
         * bulkhead entirely, which is what the interactive workload wants: the owner's turn should
         * never be refused because another turn is in flight.
         *
         * <p>A synchronous bulkhead does not queue. SmallRye queues excess callers only for
         * asynchronous actions, and a chat completion is synchronous here, so exceeding this limit
         * raises {@code BulkheadException} immediately. For background work that is the intended
         * behaviour - the run is abandoned and the schedule retries it - but it is the reason this
         * must stay {@code 0} for anything a person is waiting on.
         */
        @WithDefault("0")
        int maxConcurrent();

        /**
         * Whether a rejected call may fall back to the secondary provider. Background work sets this
         * false: a job that was throttled on purpose should skip its run, not spend the fallback
         * provider doing work nobody is waiting for.
         */
        @WithDefault("true")
        boolean fallbackOnReject();
    }
}
