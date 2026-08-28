package dev.omatheusmesmo.qlawkus.model;

import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.response.ChatResponse;
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
 * The retry + circuit breaker + fallback pipeline shared by {@link FallbackChatModel} and {@link
 * FallbackStreamingChatModel}, both of which talk to the same primary completions endpoint over
 * different transports. A {@code TypedGuard} registers its circuit breaker under {@code name()}
 * exactly once - a second guard built with the same name throws "Circuit breaker already exists" -
 * so the two callers cannot each build their own guard and still share breaker state; they share
 * this single {@code @ApplicationScoped} instance instead.
 *
 * <p>The fallback destination differs per caller (a plain blocking call for {@link
 * FallbackChatModel}, a bridged streaming call for {@link FallbackStreamingChatModel}) and per call
 * (the in-flight request and handler), so it cannot be baked into the guard at construction time.
 * {@link #call} carries it through a {@link ThreadLocal}, safe because {@code TypedGuard} invokes
 * every attempt - including the fallback handler - on the calling thread; nothing here is offloaded
 * to another executor.
 *
 * <p>This is also why {@link dev.omatheusmesmo.qlawkus.agent.AgentService} carries no fault-tolerance
 * annotations of its own: protecting one model call here, rather than the whole tool-calling turn at
 * the AI service level, is what keeps a retry from replaying a tool call that already succeeded (see
 * the javadoc on {@code AgentService}).
 */
@ApplicationScoped
@Startup
public class PrimaryChatGuard {

    private static final List<Class<? extends Throwable>> NON_RETRYABLE =
            List.of(NonRetriableException.class, UnsupportedFeatureException.class);

    /**
     * {@code abortOn} also covers an already-open circuit: retrying into a breaker that just rejected
     * the call wastes the backoff budget on certain rejections, so this aborts straight to fallback.
     */
    private static final List<Class<? extends Throwable>> RETRY_ABORTS_ON = concat(NON_RETRYABLE, CircuitBreakerOpenException.class);

    private static List<Class<? extends Throwable>> concat(
            List<Class<? extends Throwable>> base, Class<? extends Throwable> extra) {
        List<Class<? extends Throwable>> combined = new ArrayList<>(base);
        combined.add(extra);
        return List.copyOf(combined);
    }

    private final Map<String, TypedGuard<ChatResponse>> guards = new LinkedHashMap<>();
    private final WorkloadGuards workloads;
    private final AgentMeters meters;
    private final ThreadLocal<Supplier<ChatResponse>> currentFallback = new ThreadLocal<>();

    @Inject
    public PrimaryChatGuard(ModelFallbackConfig config, AgentMeters meters, WorkloadGuards workloads) {
        this.meters = meters;
        this.workloads = workloads;
        for (String workload : workloads.names()) {
            guards.put(workload, buildGuard(config, workload));
        }
    }

    /**
     * Builds one guard per workload. Each guard object carries its own breaker and bulkhead instance,
     * which is what makes the isolation real: a batch job that trips its breaker cannot open the one
     * the owner's next message goes through. Guards are built once per workload at startup and never
     * on a call path, both because construction is not cheap and because breaker names must stay
     * unique - the library will not object if they collide.
     */
    private TypedGuard<ChatResponse> buildGuard(ModelFallbackConfig config, String workload) {
        TypedGuard.Builder<ChatResponse> builder = TypedGuard.create(ChatResponse.class)
                .withRetry()
                .maxRetries(config.retryMaxAttempts())
                .abortOn(RETRY_ABORTS_ON)
                .delay(config.retryInitialDelay().toMillis(), ChronoUnit.MILLIS)
                .withExponentialBackoff()
                .maxDelay(config.retryMaxDelay().toMillis(), ChronoUnit.MILLIS)
                .done()
                .done()
                .withCircuitBreaker()
                .name(workloads.breakerName(WorkloadGuards.CHAT, workload))
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

    /**
     * What the fallback refuses to mask for this workload. A workload that does not fall back on
     * rejection adds {@code BulkheadException} here, so a throttled background call abandons the run
     * instead of spending the secondary provider on work nobody is waiting for. A synchronous
     * bulkhead rejects rather than queues, so without this every throttled job would quietly
     * re-target the fallback.
     */
    private List<Class<? extends Throwable>> rejectionAborts(String workload) {
        if (workloads.policy(workload).fallbackOnReject()) {
            return NON_RETRYABLE;
        }
        return concat(NON_RETRYABLE, BulkheadException.class);
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
            String breaker = workloads.breakerName(WorkloadGuards.CHAT, workload);
            meters.circuitState(AgentMeters.SURFACE_CHAT, workload, this,
                    guard -> CircuitStates.code(CircuitBreakerMaintenance.get().currentState(breaker)));
        }
    }

    /**
     * The fallback handler, and therefore the exact moment the primary provider is abandoned for this
     * call. Counting here rather than inspecting the breaker means a switch is recorded even when the
     * circuit never opens, which is the common case for a single transient failure.
     */
    private ChatResponse onFallback() {
        meters.fallback(AgentMeters.SURFACE_CHAT);
        return currentFallback.get().get();
    }

    /**
     * Runs {@code primary}, retrying and eventually falling back to {@code fallback} per the policy
     * above. Exceptions in {@link #NON_RETRYABLE} skip both retry and fallback and propagate as-is -
     * an auth or config error should surface, not be masked by a silent switch to Ollama.
     */
    public ChatResponse call(Supplier<ChatResponse> primary, Supplier<ChatResponse> fallback) {
        currentFallback.set(fallback);
        try {
            return guardFor(WorkloadContext.current()).get(primary);
        } finally {
            currentFallback.remove();
        }
    }

    /**
     * An unknown workload resolves to the interactive guard rather than failing. A name that reaches
     * here without a guard is a configuration mistake, and refusing the call would turn a typo into
     * an outage on the path a person is waiting on.
     */
    private TypedGuard<ChatResponse> guardFor(String workload) {
        TypedGuard<ChatResponse> guard = guards.get(workload);
        if (guard == null) {
            Log.warnf("Unknown model workload '%s', using %s", workload, WorkloadContext.INTERACTIVE);
            return guards.get(WorkloadContext.INTERACTIVE);
        }
        return guard;
    }
}
