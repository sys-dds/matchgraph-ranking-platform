package com.matchgraph.api.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matchgraph.redis")
public record RedisFoundationProperties(String host, int port) {
}
