package dev.omatheusmesmo.qlawkus.cognition;

import dev.langchain4j.model.chat.ChatModel;
import dev.omatheusmesmo.qlawkus.config.SkillsConfig;
import dev.omatheusmesmo.qlawkus.metrics.AgentMeters;
import dev.omatheusmesmo.qlawkus.model.WorkloadContext;
import dev.omatheusmesmo.qlawkus.skill.SkillStore;
import dev.omatheusmesmo.qlawkus.skill.SkillSummary;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Background curation of procedural memory (inspired by Hermes' self-improvement loop). Distilled
 * and hand-authored skills accumulate near-duplicates over time; this job asks the model which
 * skills are redundant (the same procedure under another name) and removes them, keeping the
 * injected index compact. Conservative by design: only true duplicates are dropped.
 */
@ApplicationScoped
public class SkillCurationJob {
  @Inject
  AgentMeters meters;


  @Inject
  ChatModel chatModel;

  @Inject
  SkillStore skillStore;

  @Inject
  SkillsConfig config;

  @Scheduled(identity = "skill-curation", concurrentExecution = ConcurrentExecution.SKIP, cron = "{qlawkus.skills.curation.cron:0 50 3 * * ?}")
  void curate() {
    if (config.curation().enabled()) {
      WorkloadContext.runAs(WorkloadContext.BATCH,
          () -> meters.timeJob("skill-curation", this::curateNow));
    }
  }

  public long curateNow() {
    List<SkillSummary> skills = skillStore.index();
    if (skills.size() < 2) {
      return 0;
    }

    StringBuilder list = new StringBuilder();
    for (SkillSummary skill : skills) {
      list.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
    }
    String prompt = """
        Below is the agent's list of skills (name: description). Identify skills that are
        REDUNDANT: a duplicate or a strict subset of another skill in the list. Be conservative,
        only flag genuine duplicates, never skills that merely share a topic.

        Respond with the exact names to remove, one per line, and nothing else. If none are
        redundant, respond with exactly: NONE

        Skills:
        %s""".formatted(list);

    long removed = 0;
    try {
      for (String name : parseNames(chatModel.chat(prompt), skills)) {
        if (skillStore.delete(name)) {
          removed++;
        }
      }
      if (removed > 0) {
        Log.infof("SkillCurationJob: removed %d redundant skills", removed);
      }
    } catch (Exception e) {
      Log.warnf(e, "SkillCurationJob: curation failed");
    }
    return removed;
  }

  static List<String> parseNames(String response, List<SkillSummary> known) {
    List<String> names = new ArrayList<>();
    if (response == null || response.strip().equalsIgnoreCase("NONE")) {
      return names;
    }
    for (String line : response.split("\n")) {
      String name = line.strip().replaceFirst("^[-*]\\s*", "");
      if (name.isEmpty()) {
        continue;
      }
      for (SkillSummary skill : known) {
        if (skill.name().equals(name)) {
          names.add(name);
          break;
        }
      }
    }
    return names;
  }
}
