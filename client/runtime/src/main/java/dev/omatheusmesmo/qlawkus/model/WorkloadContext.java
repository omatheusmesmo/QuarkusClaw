package dev.omatheusmesmo.qlawkus.model;

import java.util.function.Supplier;

/**
 * Which workload the current thread is doing work for.
 *
 * <p>A CDI qualifier would be the obvious way to pick a workload at the injection point, but a
 * qualifier member has to be known when beans are wired, which would fix the set of workloads at
 * build time and defeat the point of declaring them in configuration. A thread-scoped marker keeps
 * the set open: a caller names any configured workload, and the guard for it is resolved on the spot.
 *
 * <p>This is safe for the same reason the per-call fallback bridge in {@link PrimaryChatGuard} is:
 * SmallRye runs a synchronous guarded action, and its fallback handler, on the calling thread. Work
 * that hops threads must therefore set the marker on the thread that actually calls the model, which
 * is why {@link #runAs} wraps the call rather than the job's whole lifecycle.
 */
public final class WorkloadContext {

    /** The workload a thread belongs to unless it says otherwise: someone is waiting on it. */
    public static final String INTERACTIVE = "interactive";

    /** Scheduled jobs and the observers that mine a finished turn. Nobody is waiting on these. */
    public static final String BATCH = "batch";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private WorkloadContext() {
    }

    /** The workload for this thread, defaulting to {@link #INTERACTIVE}. */
    public static String current() {
        String workload = CURRENT.get();
        return workload == null ? INTERACTIVE : workload;
    }

    /** Runs {@code work} attributed to {@code workload}, restoring the previous attribution after. */
    public static void runAs(String workload, Runnable work) {
        callAs(workload, () -> {
            work.run();
            return null;
        });
    }

    /** Returns the result of {@code work}, attributed to {@code workload}. */
    public static <T> T callAs(String workload, Supplier<T> work) {
        String previous = CURRENT.get();
        CURRENT.set(workload);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
