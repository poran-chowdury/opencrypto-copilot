package dev.opencrypto.aicopilotservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The whole service starts with Spring AI on the classpath and no keys, and reports what it has. */
@SpringBootTest(properties = {"copilot.knowledge.path=../../knowledge", "copilot.llm.provider=", "copilot.llm.api-key=", "copilot.local-llm.model="})
@AutoConfigureMockMvc
class AiStatusTest {
 @Autowired MockMvc mvc;

 @Test void statusReportsBothModelClientsWithoutLeakingSecrets() throws Exception {
  mvc.perform(get("/api/v1/ai/status"))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.engine").value("deterministic"))
   .andExpect(jsonPath("$.models.reasoning.provider").value("anthropic"))
   .andExpect(jsonPath("$.models.reasoning.model").value("claude-opus-5-5"))
   .andExpect(jsonPath("$.models.reasoning.configured").value(false))
   .andExpect(jsonPath("$.models.local.provider").value("ollama"))
   .andExpect(jsonPath("$.models.local.configured").value(false))
   .andExpect(jsonPath("$..apiKey").doesNotExist());
 }
}
