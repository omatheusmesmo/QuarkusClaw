package dev.omatheusmesmo.qlawkus.cognition;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.omatheusmesmo.qlawkus.metrics.AgentMeters;
import dev.omatheusmesmo.qlawkus.model.WorkloadContext;
import dev.omatheusmesmo.qlawkus.store.EpisodicStore;
import dev.omatheusmesmo.qlawkus.store.FactStore;
import dev.omatheusmesmo.qlawkus.store.MemorySource;
import dev.omatheusmesmo.qlawkus.store.WorkingMemoryStore;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EpisodicConsolidatorJob {
  @Inject
  AgentMeters meters;


  @Inject
  ChatModel chatModel;

  @Inject
  FactStore factStore;

  @Inject
  WorkingMemoryStore workingMemoryStore;

  @Inject
  EpisodicStore episodicStore;

  @Scheduled(identity = "episodic-consolidator", concurrentExecution = ConcurrentExecution.SKIP, cron = "{qlawkus.consolidator.cron:0 0 3 * * ?}")
  void consolidate() {
    WorkloadContext.runAs(WorkloadContext.BATCH,
        () -> meters.timeJob("episodic-consolidator", this::consolidateNow));
  }

  public void consolidateNow() {
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    consolidateDate(yesterday);
  }

  public void consolidateDate(LocalDate date) {
    if (episodicStore.existsForDate(date)) {
      Log.debugf("Journal already exists for %s, re-asserting its embedding", date);
      embedExistingJournal(date);
      return;
    }

    List<ChatMessage> messages = workingMemoryStore.findByDateRange(date);
    if (messages.isEmpty()) {
      Log.debugf("No messages found for %s, skipping", date);
      return;
    }

    String summary = summarizeMessages(messages, date);
    episodicStore.storeEpisode(date, summary, messages.size());

    embedSummary(date, summary);
  }

  /**
   * Re-embeds the journal already stored for a date. The journal is written before it is embedded,
   * so anything landing between the two steps - a SIGTERM during a rollout, or an embed failure that
   * only warned - leaves an entry no retrieval can reach, and {@code existsForDate} would then skip
   * that day for good. The fact store dedups by content hash, so this costs a hash lookup on a day
   * that was already embedded and repairs the day that was not. It never re-summarizes: the stored
   * text is the summary, so rebuilding it would spend a model call to recreate what already exists.
   */
  void embedExistingJournal(LocalDate date) {
    episodicStore.listJournals().stream()
        .filter(journal -> date.equals(journal.date()))
        .findFirst()
        .ifPresent(journal -> embedSummary(date, journal.summary()));
  }

  void embedSummary(LocalDate date, String summary) {
    try {
      factStore.store(summary, Map.of("source", MemorySource.EPISODIC_CONSOLIDATOR.value(), "date", date.toString()));
    } catch (Exception e) {
      Log.warnf(e, "Failed to embed journal summary for %s", date);
    }
  }

  public String summarizeMessages(List<ChatMessage> messages, LocalDate date) {
    String conversation = ConversationFormatter.format(messages);

    String prompt = """
      Summarize this day's conversation into a concise journal entry.
      Focus on: key topics discussed, decisions made, user preferences revealed, and notable interactions.
      Write in third person, past tense. Be factual and brief.

      Date: %s
      Messages (%d):
      %s""".formatted(date, messages.size(), conversation);

    try {
      return chatModel.chat(prompt);
    } catch (Exception e) {
      Log.warnf(e, "Failed to summarize messages for %s", date);
      return "Consolidation failed for " + date + ": " + e.getMessage();
    }
  }
}
