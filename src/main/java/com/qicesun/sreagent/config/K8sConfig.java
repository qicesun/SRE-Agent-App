package com.qicesun.sreagent.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class K8sConfig {

    private KubernetesClient client;

    @Bean
    public KubernetesClient kubernetesClient() {
        this.client = new KubernetesClientBuilder().build();
        return this.client;
    }

    @PreDestroy
    public void close() {
        if (this.client != null) {
            this.client.close();
        }
    }
}
