package dev.omatheusmesmo.qlawkus.model;

import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.omatheusmesmo.qlawkus.metrics.AgentMeters;
import dev.omatheusmesmo.qlawkus.metrics.CircuitStates;
import io.quarkus.runtime.Startup;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.quarkus.logging.Log;
import io.smallrye.faulttolerance.api.TypedGuard;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * The embedding counterpart of {@link PrimaryChatGuard}: retry + circuit breaker + fallback for
 * {@link FallbackEmbeddingModel}, kept as its own guard because embeddings and chat completions are
 * different upstream endpoints (see {@link ModelFallbackConfig#CIRCUIT_BREAKER_EMBEDDING}).
 *
 * <p>{@code embed}, {@code embed(segment)} and {@code embedAll} return different {@code Response<?>}
 * shapes, but a {@code TypedGuard} is fixed to one {@code T}. This guard is typed {@code <Object>};
 * {@link FallbackEmbeddingModel} casts at the one narrow boundary rather than building three guards
 * that would fragment the breaker three ways. Living in its own {@code @ApplicationScoped @Startup}
 * bean - the same shape as {@link PrimaryChatGuard} - also means a test can inject and drive the real,
 * already-registered guard directly instead of constructing a second one under the same circuit
 * breaker name, which {@code TypedGuard} rejects.
 */
@ApplicationScoped
@Startup
public class EmbeddingGuard {

    private static final List<Class<? extends Throwable>> NON_RETRYABLE =
            List.of(NonRetriableException.class, UnsupportedFeatureException.class);

    private final Map<String, TypedGuard<Object>> guards = new LinkedHashMap<>();
    private final WorkloadGuards workloads;
    private final AgentMeters meters;
    private final ThreadLocal<Supplier<Object>> currentFallback = new ThreadLocal<>();

    @Inject
    public EmbeddingGuard(ModelFallbackConfig config, AgentMeters meters, WorkloadGuards workloads) {
        this.meters = meters;
        this.workloads = workloads;
        for (String workload : workloads.names()) {
            guards.put(workload, buildGuard(config, workload));
        }
    }

    /** One guard per workload, each with its own breaker and bulkhead. See {@link PrimaryChatGuard}. */
    private TypedGuard<Object> buildGuard(ModelFallbackConfig config, String workload) {
        List<Class<? extends Throwable>> abortsOn = new ArrayList<>(NON_RETRYABLE);
        abortsOn.add(CircuitBreakerOpenException.class);

        TypedGuard.Builder<Object> builder = TypedGuard.create(Object.class)
                .withRetry()
                .maxRetries(config.retryMaxAttempts())
                .abortOn(List.copyOf(abortsOn))
                .delay(config.retryInitialDelay().toMillis(), ChronoUnit.MILLIS)
                .withExponentialBackoff()
                .maxDelay(config.retryMaxDelay().toMillis(), ChronoUnit.MILLIS)
                .done()
                .done()
                .withCircuitBreaker()
                .name(workloads.breakerName(WorkloadGuards.EMBEDDING, workload))
                .requestVolumeThreshold(config.retryMaxAttempts() + 1)
                .failureRatio(1.0)
                .delay(config.circuitBreakerResetTimeout().toMillis(), ChronoUnit.MILLIS)
                .skipOn(NON_RETRYABLE)
                .done()
                .withFallback()
                .skipOn(rejectionAborts(workload))
                .handler(cause -> onFallback())
                .done();
        workloads.applyBulkhead(builder, workload);
        return builder.build();
    }

    /** See {@link PrimaryChatGuard}: a throttled background embed skips rather than using Ollama. */
    private List<Class<? extends Throwable>> rejectionAborts(String workload) {
        if (workloads.policy(workload).fallbackOnReject()) {
            return NON_RETRYABLE;
        }
        List<Class<? extends Throwable>> aborts = new ArrayList<>(NON_RETRYABLE);
        aborts.add(BulkheadException.class);
        return List.copyOf(aborts);
    }

    /**
     * Registers the breaker state as a gauge, read on scrape through {@code CircuitBreakerMaintenance},
     * a read-only lookup, so polling can never promote a state as a side effect. The gauge is bound to
     * this bean because Micrometer holds only a weak reference to the gauged object, and this
     * {@code @ApplicationScoped} instance outlives the registry.
     */
    @PostConstruct
    void publishCircuitState() {
        for (String workload : guards.keySet()) {
            String breaker = workloads.breakerName(WorkloadGuards.EMBEDDING, workload);
            meters.circuitState(AgentMeters.SURFACE_EMBEDDING, workload, this,
                    guard -> CircuitStates.code(CircuitBreakerMaintenance.get().currentState(breaker)));
        }
    }

    /**
     * The fallback handler, and therefore the exact moment the primary provider is abandoned for this
     * call. Counting here rather than inspecting the breaker means a switch is recorded even when the
     * circuit never opens, which is the common case for a single transient failure.
     */
    private Object onFallback() {
        meters.fallback(AgentMeters.SURFACE_EMBEDDING);
        return currentFallback.get().get();
    }

    @SuppressWarnings("unchecked")
    public <T> T call(Supplier<T> primary, Supplier<T> fallback) {
        currentFallback.set((Supplier<Object>) fallback);
        try {
            return (T) guardFor(WorkloadContext.current()).get((Supplier<Object>) primary);
        } finally {
            currentFallback.remove();
        }
    }

    /** An unknown workload falls back to the interactive guard rather than failing the call. */
    private TypedGuard<Object> guardFor(String workload) {
        TypedGuard<Object> guard = guards.get(workload);
        if (guard == null) {
            Log.warnf("Unknown model workload '%s', using %s", workload, WorkloadContext.INTERACTIVE);
            return guards.get(WorkloadContext.INTERACTIVE);
        }
        return guard;
    }
}
