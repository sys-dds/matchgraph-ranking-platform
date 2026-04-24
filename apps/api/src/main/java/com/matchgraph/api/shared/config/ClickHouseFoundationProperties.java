package com.matchgraph.api.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matchgraph.clickhouse")
public record ClickHouseFoundationProperties(String url) {
}
