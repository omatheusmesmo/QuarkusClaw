package dev.omatheusmesmo.qlawkus.cognition;

import dev.omatheusmesmo.qlawkus.config.MemoryReviewConfig;
import dev.omatheusmesmo.qlawkus.metrics.AgentMeters;
import dev.omatheusmesmo.qlawkus.model.WorkloadContext;
import dev.omatheusmesmo.qlawkus.store.FactStore;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Background self-review of long-term memory (inspired by Hermes' self-improvement loop). The
 * write-time MD5 dedup only catches byte-identical facts, so reworded variants of the same fact
 * accumulate over time. This job periodically removes semantic near-duplicates from the fact store,
 * keeping memory compact and reducing noise injected by active memory.
 */
@ApplicationScoped
public class MemoryReviewJob {
  @Inject
  AgentMeters meters;


  @Inject
  FactStore factStore;

  @Inject
  MemoryReviewConfig config;

  @Scheduled(identity = "memory-review", concurrentExecution = ConcurrentExecution.SKIP, cron = "{qlawkus.memory-review.cron:0 30 3 * * ?}")
  void review() {
    WorkloadContext.runAs(WorkloadContext.BATCH,
        () -> meters.timeJob("memory-review", this::reviewNow));
  }

  public long reviewNow() {
    double similarityThreshold = config.similarityThreshold();
    double maxCosineDistance = 1.0 - similarityThreshold;
    long removed = factStore.purgeNearDuplicates(maxCosineDistance);
    if (removed > 0) {
      Log.infof("MemoryReviewJob: removed %d near-duplicate memories (>= %.2f cosine similarity)",
          removed, similarityThreshold);
    }
    return removed;
  }
}
