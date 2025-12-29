package com.qicesun.sreagent.config;

import com.qicesun.sreagent.tools.jira.JiraClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JiraConfig {

    @Bean
    public JiraClient jiraClient(
            @Value("${jira.url:}") String baseUrl,
            @Value("${jira.token:}") String token,
            @Value("${jira.email:}") String email,
            @Value("${jira.timeout-seconds:30}") long timeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            return JiraClient.builder()
                    .baseUrl("http://localhost")
                    .authentication(JiraClient.Authentication.bearerToken("disabled-token"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
        }
        JiraClient.Authentication authentication;
        if (email != null && !email.isBlank()) {
            authentication = JiraClient.Authentication.basic(email, token);
        } else {
            authentication = JiraClient.Authentication.bearerToken(token);
        }
        return JiraClient.builder()
                .baseUrl(baseUrl)
                .authentication(authentication)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
