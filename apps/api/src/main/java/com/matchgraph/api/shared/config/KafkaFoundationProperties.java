package com.matchgraph.api.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matchgraph.kafka")
public record KafkaFoundationProperties(String bootstrapServers) {
}
