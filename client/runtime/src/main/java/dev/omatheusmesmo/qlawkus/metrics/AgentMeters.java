package dev.omatheusmesmo.qlawkus.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/**
 * The one place every Qlawkus metric name is written down, and the only thing in the agent that
 * touches a {@link MeterRegistry} directly.
 *
 * <p>A metric name is a public contract: it becomes a time series, then a dashboard panel, then an
 * alert. Renaming one later breaks history silently, so the names live here as constants rather than
 * as string literals spread across call sites, the same single-source discipline that governs
 * {@code MemorySource} and {@code CompositionPaths}.
 *
 * <p>Telemetry is composable, so the registry may legitimately be absent: {@code micrometer-core} is
 * an always-present API, while the registry and exporter ship in the optional
 * {@code qlawkus-observability} extension. When no registry bean is resolvable every method here is
 * a no-op, which is what lets a locked-down distribution omit telemetry at module level instead of
 * by flag.
 */
@ApplicationScoped
public class AgentMeters {

    public static final String RETRIEVAL_QUERIES = "qlawkus.retrieval.queries";
    public static final String RETRIEVAL_FACTS = "qlawkus.retrieval.facts";
    public static final String RETRIEVAL_SCORE = "qlawkus.retrieval.score";
    public static final String RETRIEVAL_DURATION = "qlawkus.retrieval.duration";

    public static final String EMBEDDING_FACTS = "qlawkus.embedding.facts";
    public static final String EMBEDDING_SEGMENTS = "qlawkus.embedding.segments";
    public static final String EMBEDDING_OVERSIZED = "qlawkus.embedding.oversized";

    public static final String JOB_DURATION = "qlawkus.job.duration";
    public static final String JOB_ITEMS = "qlawkus.job.items";

    public static final String MODEL_FALLBACK = "qlawkus.model.fallback";
    public static final String MODEL_CIRCUIT_STATE = "qlawkus.model.circuit.state";

    /** Model surfaces. Separate breakers, because chat and embeddings can fail independently. */
    public static final String SURFACE_CHAT = "chat";
    /** Default workload tag, so the pre-workload gauge keeps a stable label set. */
    public static final String WORKLOAD_INTERACTIVE = "interactive";

    public static final String SURFACE_EMBEDDING = "embedding";

    public static final String TOOL_INVOCATIONS = "qlawkus.tool.invocations";
    public static final String TOOL_DURATION = "qlawkus.tool.duration";

    public static final String STORE_OPERATIONS = "qlawkus.store.operations";
    public static final String STORE_DURATION = "qlawkus.store.duration";

    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_JOB = "job";
    private static final String TAG_SURFACE = "surface";
    private static final String TAG_WORKLOAD = "workload";
    private static final String TAG_TOOL = "tool";
    private static final String TAG_STORE = "store";
    private static final String TAG_BACKEND = "backend";
    private static final String TAG_OPERATION = "operation";

    private static final String HIT = "hit";
    private static final String MISS = "miss";
    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";

    @Inject
    Instance<MeterRegistry> registryInstance;

    private MeterRegistry registry;

    /** Constructor used by CDI, which supplies the registry through {@link #registryInstance}. */
    public AgentMeters() {
    }

    /**
     * Direct construction with an explicit registry, for tests and for the non-CDI constructors the
     * fact stores expose. A {@code null} registry yields inert telemetry, same as an absent one.
     */
    public AgentMeters(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Telemetry that records nothing, for the convenience store constructors used outside CDI. The
     * stores always hold a real instance, so no call site needs a null check.
     */
    public static AgentMeters disabled() {
        return new AgentMeters(null);
    }

    /**
     * Resolves the registry once rather than per call. Resolution is guarded because an ambiguous
     * or failed lookup must degrade to no-op telemetry, never take down the agent: nothing here is
     * worth failing a turn over.
     */
    @PostConstruct
    void resolveRegistry() {
        try {
            registry = registryInstance != null && registryInstance.isResolvable()
                    ? registryInstance.get()
                    : null;
        } catch (RuntimeException e) {
            Log.debugf(e, "No usable MeterRegistry; agent telemetry stays disabled");
            registry = null;
        }
    }

    /** Whether a registry is present. Call sites use this to skip building tags or timing at all. */
    public boolean enabled() {
        return registry != null;
    }

    /**
     * Records one active-memory retrieval: whether anything was injected, how many facts, and the
     * score of each. The scores are what make the configured {@code min-score} judgeable, since a
     * retrieval that consistently lands just under the threshold is indistinguishable from one that
     * finds nothing until the distribution is visible.
     */
    public void retrieval(int facts, double[] scores, Duration took) {
        if (registry == null) {
            return;
        }
        registry.counter(RETRIEVAL_QUERIES, Tags.of(TAG_OUTCOME, facts > 0 ? HIT : MISS)).increment();
        summary(RETRIEVAL_FACTS, Tags.empty()).record(facts);
        for (double score : scores) {
            summary(RETRIEVAL_SCORE, Tags.empty()).record(score);
        }
        registry.timer(RETRIEVAL_DURATION, Tags.empty()).record(took);
    }

    /**
     * Runs the embedding of one fact, already split into {@code segments}, and records its outcome.
     *
     * <p>The outcome is the point. Chunking cannot fail in any way worth measuring, but embedding
     * can, and a fact that never embeds is the failure that orphans a markdown file and aborts the
     * whole reconcile on the next boot. Measuring at chunk time instead would report a success
     * before anything had been sent to the model.
     *
     * <p>A thrown exception is recorded as a failure and rethrown unchanged: telemetry observes the
     * embed, it never swallows what it did.
     */
    public void embedFact(int segments, Runnable embed) {
        if (registry == null) {
            embed.run();
            return;
        }
        boolean succeeded = false;
        try {
            embed.run();
            succeeded = true;
        } finally {
            registry.counter(EMBEDDING_FACTS, Tags.of(TAG_OUTCOME, succeeded ? SUCCESS : FAILURE))
                    .increment();
            if (succeeded) {
                summary(EMBEDDING_SEGMENTS, Tags.empty()).record(segments);
                if (segments > 1) {
                    registry.counter(EMBEDDING_OVERSIZED, Tags.empty()).increment();
                }
            }
        }
    }

    /**
     * Records one scheduled job run. The console already shows cron and last run; this is the time
     * series that says whether a job is getting slower or quietly processing nothing.
     */
    public void job(String job, Duration took, long items, boolean succeeded) {
        if (registry == null) {
            return;
        }
        registry.timer(JOB_DURATION, Tags.of(TAG_JOB, job, TAG_OUTCOME, succeeded ? SUCCESS : FAILURE))
                .record(took);
        summary(JOB_ITEMS, Tags.of(TAG_JOB, job)).record(items);
    }

    /**
     * Times {@code work} and reports it as a run of {@code job}, where the returned value is the
     * number of items processed. Wrapping at the call site rather than intercepting keeps this honest
     * for scheduled methods, which Quarkus invokes directly on the bean.
     *
     * <p>A thrown exception is recorded as a failed run and rethrown unchanged: telemetry observes
     * the job, it never swallows what the job did.
     */
    public long timeJob(String job, LongSupplier work) {
        long startedAt = System.nanoTime();
        long items = 0;
        boolean succeeded = false;
        try {
            items = work.getAsLong();
            succeeded = true;
            return items;
        } finally {
            job(job, Duration.ofNanos(System.nanoTime() - startedAt), items, succeeded);
        }
    }

    /** Times a job that reports no item count. */
    public void timeJob(String job, Runnable work) {
        timeJob(job, () -> {
            work.run();
            return 0L;
        });
    }

    /** Counts one switch from the primary provider to the fallback, per model surface. */
    public void fallback(String surface) {
        if (registry != null) {
            registry.counter(MODEL_FALLBACK, Tags.of(TAG_SURFACE, surface)).increment();
        }
    }

    /**
     * Publishes a circuit breaker's state as a gauge, read from the supplier on every scrape. The
     * supplier is polled rather than pushed so the breaker is never mutated by being observed.
     */
    public <T> void circuitState(String surface, T source, ToDoubleFunction<T> state) {
        circuitState(surface, WORKLOAD_INTERACTIVE, source, state);
    }

    /**
     * Publishes one breaker gauge per workload. The workload tag is what makes a batch-induced open
     * breaker distinguishable from one the interactive path opened, which is the whole question the
     * isolation was built to answer.
     */
    public <T> void circuitState(String surface, String workload, T source, ToDoubleFunction<T> state) {
        if (registry != null) {
            registry.gauge(MODEL_CIRCUIT_STATE, Tags.of(TAG_SURFACE, surface, TAG_WORKLOAD, workload),
                    source, state);
        }
    }

    /** Records one tool invocation. Tagged per tool because the aggregate hides which one broke. */
    public void tool(String tool, Duration took, boolean succeeded) {
        if (registry == null) {
            return;
        }
        Tags tags = Tags.of(TAG_TOOL, tool, TAG_OUTCOME, succeeded ? SUCCESS : FAILURE);
        registry.counter(TOOL_INVOCATIONS, tags).increment();
        registry.timer(TOOL_DURATION, Tags.of(TAG_TOOL, tool)).record(took);
    }

    /**
     * Records one store operation. The {@code backend} tag is what gives the
     * {@code markdown | pgvector | hybrid} switch operational meaning, since the same call costs
     * very different amounts depending on which implementation answered.
     */
    public void store(String store, String backend, String operation, Duration took, boolean succeeded) {
        if (registry == null) {
            return;
        }
        Tags tags = Tags.of(TAG_STORE, store, TAG_BACKEND, backend, TAG_OPERATION, operation);
        registry.counter(STORE_OPERATIONS, tags.and(TAG_OUTCOME, succeeded ? SUCCESS : FAILURE)).increment();
        registry.timer(STORE_DURATION, tags).record(took);
    }

    private DistributionSummary summary(String name, Tags tags) {
        return DistributionSummary.builder(name).tags(tags).register(registry);
    }
}
