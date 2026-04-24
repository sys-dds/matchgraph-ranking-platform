package com.matchgraph.api.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

import com.matchgraph.api.jooq.tables.SchemaVersionProbe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("garapadev/postgres-postgis-pgvector:16-optimized")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
        .withDatabaseName("matchgraph")
        .withUsername("matchgraph")
        .withPassword("matchgraph");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @Container
    static final GenericContainer<?> kafka = new GenericContainer<>(DockerImageName.parse("apache/kafka:3.9.0"))
        .withExposedPorts(9092)
        .withEnv("KAFKA_NODE_ID", "1")
        .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
        .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
        .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:9092")
        .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final GenericContainer<?> clickHouse = new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:25.3-alpine"))
        .withExposedPorts(8123)
        .withEnv("CLICKHOUSE_DB", "matchgraph")
        .withEnv("CLICKHOUSE_USER", "matchgraph")
        .withEnv("CLICKHOUSE_PASSWORD", "matchgraph")
        .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    @DynamicPropertySource
    static void foundationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getHost() + ":" + kafka.getMappedPort(9092));
        registry.add("matchgraph.kafka.bootstrap-servers", () -> kafka.getHost() + ":" + kafka.getMappedPort(9092));
        registry.add("matchgraph.redis.host", redis::getHost);
        registry.add("matchgraph.redis.port", () -> redis.getMappedPort(6379));
        registry.add("matchgraph.clickhouse.url", FoundationIntegrationTest::clickHouseJdbcUrl);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void springAppContextStarts() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void pingEndpointWorks() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/system/ping", Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("service", "matchgraph-api");
        assertThat(response.getBody()).containsEntry("status", "ok");
    }

    @Test
    void flywayMigrationRunsAndExtensionsExist() {
        assertThat(new ClassPathResource("db/migration/V1__foundation_schema.sql").exists()).isTrue();
        assertThat(extensionExists("vector")).isTrue();
        assertThat(extensionExists("postgis")).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from schema_version_probe", Long.class)).isZero();
    }

    @Test
    void redisConnectionWorks() {
        try (var connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void kafkaContainerStarts() {
        assertThat(kafka.isRunning()).isTrue();
        assertThat(kafka.getMappedPort(9092)).isPositive();
    }

    @Test
    void clickHouseContainerStartsAndAcceptsQueries() throws Exception {
        assertThat(clickHouse.isRunning()).isTrue();
        try (var connection = DriverManager.getConnection(clickHouseJdbcUrl());
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void jooqGeneratedClassesCompileAndDescribeProbeTable() {
        assertThat(SchemaVersionProbe.SCHEMA_VERSION_PROBE.getName()).isEqualTo("schema_version_probe");
        assertThat(dsl.selectCount().from(SchemaVersionProbe.SCHEMA_VERSION_PROBE).fetchOne(0, int.class)).isZero();
    }

    @Test
    void dockerComposeConfigIsValid() throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        Process process = new ProcessBuilder(
            "docker",
            "compose",
            "-f",
            repoRoot.resolve("infra/docker-compose/docker-compose.yml").toString(),
            "config"
        )
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start();

        assertThat(process.waitFor()).isZero();
    }

    private boolean extensionExists(String name) {
        Boolean exists = jdbcTemplate.queryForObject(
            "select exists (select 1 from pg_extension where extname = ?)",
            Boolean.class,
            name
        );
        return Boolean.TRUE.equals(exists);
    }

    private static String clickHouseJdbcUrl() {
        return "jdbc:clickhouse://" + clickHouse.getHost() + ":" + clickHouse.getMappedPort(8123)
            + "/matchgraph?user=matchgraph&password=matchgraph";
    }
}
