package com.qicesun.sreagent.config;

import com.qicesun.sreagent.agent.DevOpsAssistant;
import com.qicesun.sreagent.agent.DevOpsSystemMessageProvider;
import com.qicesun.sreagent.agent.SessionMemoryStore;
import com.qicesun.sreagent.tools.gitlab.GitLabTool;
import com.qicesun.sreagent.tools.jira.JiraTool;
import com.qicesun.sreagent.tools.k8s.KubernetesTool;
import com.qicesun.sreagent.tools.webscraper.WebScraperTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${langchain4j.open-ai.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.model:gpt-5-nano}") String modelName,
            @Value("${langchain4j.open-ai.temperature:1.0}") Double temperature) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }

    @Bean
    public DevOpsAssistant devOpsAssistant(
            ChatLanguageModel chatLanguageModel,
            KubernetesTool k8sTool,
            GitLabTool gitLabTool,
            WebScraperTool webScraperTool,
            JiraTool jiraTool,
            DevOpsSystemMessageProvider systemMessageProvider,
            SessionMemoryStore memoryStore) {
        return AiServices.builder(DevOpsAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .systemMessageProvider(systemMessageProvider::systemMessageFor)
                .chatMemoryProvider(memoryStore::get)
                .tools(k8sTool, gitLabTool, webScraperTool, jiraTool)
                .build();
    }
}
