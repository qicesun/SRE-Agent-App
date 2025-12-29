package agent;

import com.qicesun.sreagent.agent.DevOpsAssistant;
import com.qicesun.sreagent.agent.DevOpsSystemMessageProvider;
import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptInjectionTest {

    @Test
    void systemMessageProvider_injectsLatestSessionConfigEveryTurn() {
        SessionConfigStore store = new SessionConfigStore();
        SessionConfig config = new SessionConfig();
        config.setK8sEnabled(true);
        config.setK8sNamespace("production");
        config.setK8sWorkloadKind("Deployment");
        config.setK8sWorkloadName("payment-service");
        config.setGitlabEnabled(true);
        config.setGitlabProjectId("group/project");
        config.setJiraEnabled(true);
        config.setJiraProjectKey("OPS");
        store.save("s1", config);

        DevOpsSystemMessageProvider provider =
                new DevOpsSystemMessageProvider(store, "https://gitlab.example.com/", "https://jira.example.com/");

        CapturingChatModel model = new CapturingChatModel();
        DevOpsAssistant assistant = AiServices.builder(DevOpsAssistant.class)
                .chatLanguageModel(model)
                .systemMessageProvider(provider::systemMessageFor)
                .chatMemoryProvider(ignored -> dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(20))
                .build();

        assistant.chat("s1", "hello");

        assertThat(model.calls).hasSize(1);
        List<ChatMessage> messages = model.calls.get(0);
        SystemMessage systemMessage = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a SystemMessage in the prompt"));
        String systemText = systemMessage.text();
        assertThat(systemText).contains("<EXTERNAL_CONFIG>");
        assertThat(systemText).contains("k8s.namespace=production");
        assertThat(systemText).contains("gitlab.projectId=group/project");
        assertThat(systemText).contains("jira.projectKey=OPS");

        config.setJiraProjectKey("PAY");
        store.save("s1", config);

        assistant.chat("s1", "hello again");

        assertThat(model.calls).hasSize(2);
        SystemMessage updatedSystemMessage = model.calls.get(1).stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a SystemMessage in the prompt"));
        String updatedSystemText = updatedSystemMessage.text();
        assertThat(updatedSystemText).contains("jira.projectKey=PAY");
    }

    private static final class CapturingChatModel implements ChatLanguageModel {
        private final List<List<ChatMessage>> calls = new ArrayList<>();

        @Override
        public Response<dev.langchain4j.data.message.AiMessage> generate(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            return Response.from(dev.langchain4j.data.message.AiMessage.from("ok"));
        }
    }
}
