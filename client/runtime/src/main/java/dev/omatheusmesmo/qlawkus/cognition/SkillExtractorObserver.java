package dev.omatheusmesmo.qlawkus.cognition;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.omatheusmesmo.qlawkus.model.WorkloadContext;
import dev.omatheusmesmo.qlawkus.config.SkillsConfig;
import dev.omatheusmesmo.qlawkus.skill.Skill;
import dev.omatheusmesmo.qlawkus.skill.SkillStore;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Passive distillation of procedural memory: after each completed turn, asks the model whether a
 * reusable procedure emerged and, if so, saves it as a skill. Mirrors
 * {@link SemanticExtractorObserver} (which mines declarative facts); this mines actionable how-to
 * knowledge. Runs channel-agnostically on {@link ChatCompletedEvent}.
 */
@ApplicationScoped
public class SkillExtractorObserver {

  @Inject
  ChatModel chatModel;

  @Inject
  SkillStore skillStore;

  @Inject
  SkillsConfig config;

  void onChatCompleted(@ObservesAsync ChatCompletedEvent event) {
    if (!config.extractor().enabled()) {
      return;
    }
    if (event.messages().isEmpty()) {
      return;
    }
    extractAndStore(event.messages());
  }

  public Optional<Skill> extractAndStore(Iterable<ChatMessage> messages) {
    try {
      String conversation = ConversationFormatter.format(messages);
      String prompt = """
          You curate an agent's reusable skills (procedural how-to knowledge).
          Review the conversation and decide whether it established a REUSABLE procedure: a way to
          accomplish a recurring task that would be worth following again. Ignore one-off chatter,
          facts about the user, and anything ephemeral.

          If a reusable procedure emerged, respond EXACTLY in this format:
          SKILL: <short-kebab-case-name>
          DESCRIPTION: <one concise line>
          ---
          <the procedure as ordered Markdown steps>

          Otherwise respond with exactly: NONE

          Conversation:
          %s""".formatted(conversation);

      Optional<Skill> skill = parse(WorkloadContext.callAs(WorkloadContext.BATCH,
          () -> chatModel.chat(prompt)));
      skill.ifPresent(s -> {
        skillStore.save(s);
        Log.infof("Skill distilled: %s", s.name());
      });
      return skill;
    } catch (Exception e) {
      Log.errorf(e, "Failed to distill skill from conversation");
      return Optional.empty();
    }
  }

  static Optional<Skill> parse(String response) {
    if (response == null) {
      return Optional.empty();
    }
    String trimmed = response.strip();
    if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("NONE")
        || !trimmed.regionMatches(true, 0, "SKILL:", 0, "SKILL:".length())) {
      return Optional.empty();
    }

    String name = null;
    String description = null;
    StringBuilder body = new StringBuilder();
    boolean inBody = false;
    for (String line : trimmed.split("\n")) {
      if (!inBody && line.strip().equals("---")) {
        inBody = true;
        continue;
      }
      if (inBody) {
        body.append(line).append('\n');
      } else if (line.regionMatches(true, 0, "SKILL:", 0, "SKILL:".length())) {
        name = line.substring(line.indexOf(':') + 1).strip();
      } else if (line.regionMatches(true, 0, "DESCRIPTION:", 0, "DESCRIPTION:".length())) {
        description = line.substring(line.indexOf(':') + 1).strip();
      }
    }
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new Skill(name, description == null ? "" : description, body.toString().strip()));
  }
}
