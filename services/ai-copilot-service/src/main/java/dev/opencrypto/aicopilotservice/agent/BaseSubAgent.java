package dev.opencrypto.aicopilotservice.agent;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import dev.opencrypto.aicopilotservice.engine.Models;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.SpringAiTools;
import dev.opencrypto.aicopilotservice.tools.ToolCall;

public abstract class BaseSubAgent implements SubAgent {
 private static final Logger log = LoggerFactory.getLogger(BaseSubAgent.class);
 protected final Models models;
 protected final SpringAiTools springAiTools;

 protected BaseSubAgent(Models models, SpringAiTools springAiTools) {
  this.models = models;
  this.springAiTools = springAiTools;
 }

 protected abstract String systemPrompt();

 @Override public String execute(Caller caller, String subGoal, List<Message> history, List<ToolCall> trace) {
  ChatClient.Builder builder = models.localClient().or(models::reasoningClient)
   .orElseThrow(() -> new IllegalStateException("Neither local LLM nor reasoning LLM is configured."));

  log.info("Agent '{}' executing sub-goal: {}", name(), subGoal);
  long start = System.currentTimeMillis();
  ChatClient client = builder.build();
  String content = client.prompt()
   .system(systemPrompt())
   .messages(history)
   .user(subGoal)
   .toolCallbacks(springAiTools.forCaller(caller, trace, tools()))
   .call()
   .content();
  log.info("Agent '{}' finished in {} ms", name(), System.currentTimeMillis() - start);
  return content;
 }
}

