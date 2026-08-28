package dev.omatheusmesmo.qlawkus.cognition;

import dev.langchain4j.model.chat.ChatModel;
import dev.omatheusmesmo.qlawkus.config.MemoryCurationConfig;
import dev.omatheusmesmo.qlawkus.metrics.AgentMeters;
import dev.omatheusmesmo.qlawkus.model.WorkloadContext;
import dev.omatheusmesmo.qlawkus.store.FactStore;
import dev.omatheusmesmo.qlawkus.store.UserProfileStore;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Background curation of the owner profile (Hermes' self-improvement / background review). Scattered
 * facts accumulate over time and can drift or contradict; this job periodically folds them into the
 * coherent {@link UserProfile} that is injected on every turn. Facts in the vector store are left
 * untouched (still the source of truth), so the LLM pass can only improve the profile, never lose
 * data. Config-gated.
 */
@ApplicationScoped
public class MemoryCurationJob {
  @Inject
  AgentMeters meters;


  @Inject
  ChatModel chatModel;

  @Inject
  FactStore factStore;

  @Inject
  MemoryCurationConfig config;

  @Inject
  UserProfileStore userProfileStore;

  @Scheduled(identity = "memory-curation", concurrentExecution = ConcurrentExecution.SKIP, cron = "{qlawkus.memory-curation.cron:0 45 3 * * ?}")
  void curate() {
    if (config.enabled()) {
      WorkloadContext.runAs(WorkloadContext.BATCH,
          () -> meters.timeJob("memory-curation", () -> curateProfile() ? 1L : 0L));
    }
  }

  public boolean curateProfile() {
    List<String> facts = factStore.listFactTexts(config.maxFacts());
    if (facts.isEmpty()) {
      return false;
    }

    UserProfile profile = userProfileStore.load();
    if (profile == null) {
      return false;
    }

    String prompt = """
        You maintain a concise profile of your owner (the single person this agent serves).
        Below is the current profile and the durable facts remembered about them. Produce an
        updated owner profile in markdown that:
        - merges everything into one coherent, compact profile
        - removes duplicates and resolves contradictions (prefer the most specific / most recent)
        - keeps only durable facts: name, role, location, stack, preferences, projects, conventions
        - omits ephemeral details (task progress, IDs, anything stale within a week)
        Return ONLY the profile markdown, with no preamble.

        Current profile:
        %s

        Remembered facts:
        %s""".formatted(
            profile.profile == null ? "(empty)" : profile.profile,
            String.join("\n", facts.stream().map(f -> "- " + f).toList()));

    try {
      String updated = chatModel.chat(prompt);
      if (updated == null || updated.isBlank()) {
        return false;
      }
      profile.rewriteProfile(updated.trim());
      userProfileStore.save(profile);
      Log.infof("MemoryCurationJob: refreshed owner profile from %d facts", facts.size());
      return true;
    } catch (Exception e) {
      Log.warnf(e, "MemoryCurationJob: profile curation failed");
      return false;
    }
  }
}
