package dev.omatheusmesmo.qlawkus.cognition;

import dev.omatheusmesmo.qlawkus.config.SkillsConfig;
import dev.omatheusmesmo.qlawkus.metrics.AgentMeters;
import dev.omatheusmesmo.qlawkus.model.WorkloadContext;
import dev.omatheusmesmo.qlawkus.skill.SkillStore;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Ages skills out of the injected index by recency of use: unused skills become stale, then
 * archived (excluded from the prompt but still loadable). Using a skill revives it. Keeps the
 * injected index compact as the library grows. Config-gated; also triggerable via
 * {@code POST /api/admin/skills/lifecycle}.
 */
@ApplicationScoped
public class SkillLifecycleJob {
  @Inject
  AgentMeters meters;


  @Inject
  SkillStore skillStore;

  @Inject
  SkillsConfig config;

  @Scheduled(identity = "skill-lifecycle", concurrentExecution = ConcurrentExecution.SKIP, cron = "{qlawkus.skills.lifecycle.cron:0 40 3 * * ?}")
  void sweep() {
    if (config.lifecycle().enabled()) {
      WorkloadContext.runAs(WorkloadContext.BATCH,
          () -> meters.timeJob("skill-lifecycle", this::sweepNow));
    }
  }

  public int sweepNow() {
    int transitioned = skillStore.sweepLifecycle(
        config.lifecycle().staleAfterDays(), config.lifecycle().archiveAfterDays());
    if (transitioned > 0) {
      Log.infof("SkillLifecycleJob: transitioned %d skills", transitioned);
    }
    return transitioned;
  }
}
