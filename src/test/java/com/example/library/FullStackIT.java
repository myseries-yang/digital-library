package com.example.library;

import com.example.library.loan.application.LoanService;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/**
 * Real MySQL + Redis, two independent HTTP application instances.
 * Deliberately fails instead of skipping when Docker is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullStackIT {
    private MySQLContainer mysql;
    private GenericContainer<?> redis;
    private ConfigurableApplicationContext first;
    private ConfigurableApplicationContext second;
    private JdbcTemplate jdbc;
    private ApiClient one;
    private ApiClient two;
    private String databaseUrl;
    private String username;
    private String password;
    private String redisAddress;

    @BeforeAll
    void startRealComponentsAndTwoApplications() {
        databaseUrl = System.getenv("LIBRARY_IT_DB_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                throw new IllegalStateException("full-it requires a running Docker engine. No integration tests were skipped. "
                        + "Alternatively supply explicitly disposable external MySQL/Redis test instances; see README.");
            }
            mysql = new MySQLContainer("mysql:8.4").withDatabaseName("library_it")
                    .withUsername("library").withPassword("library");
            redis = new GenericContainer<>(DockerImageName.parse("redis:8.4")).withExposedPorts(6379);
            mysql.start();
            redis.start();
            databaseUrl = mysql.getJdbcUrl();
            username = mysql.getUsername();
            password = mysql.getPassword();
            redisAddress = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        } else {
            if (!"true".equals(System.getenv("LIBRARY_IT_EXTERNAL_IS_DISPOSABLE"))) {
                throw new IllegalStateException("External full-it database must be explicitly disposable: "
                        + "set LIBRARY_IT_EXTERNAL_IS_DISPOSABLE=true. Tests clear the five business tables.");
            }
            username = System.getenv().getOrDefault("LIBRARY_IT_DB_USERNAME", "library");
            password = System.getenv().getOrDefault("LIBRARY_IT_DB_PASSWORD", "library");
            redisAddress = System.getenv("LIBRARY_IT_REDIS_ADDRESS");
            if (redisAddress == null || redisAddress.isBlank()) throw new IllegalStateException("Missing LIBRARY_IT_REDIS_ADDRESS");
        }
        databaseUrl += (databaseUrl.contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        first = startApplication("library-it-one", redisAddress);
        second = startApplication("library-it-two", redisAddress);
        jdbc = first.getBean(JdbcTemplate.class);
        one = client(first);
        two = client(second);
    }

    private ConfigurableApplicationContext startApplication(String name, String address) {
        return new SpringApplicationBuilder(LibraryApplication.class).profiles("full").run(
                "--spring.application.name=" + name, "--server.port=0",
                "--spring.datasource.url=" + databaseUrl, "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password, "--library.redis.address=" + address,
                "--library.lock.wait-time=5s", "--library.transaction-timeout=15s",
                "--logging.level.com.example.library=WARN");
    }

    private ApiClient client(ConfigurableApplicationContext context) {
        return new ApiClient(Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port")),
                context.getBean(ObjectMapper.class));
    }

    @BeforeEach
    void reset() { DatabaseAssertions.reset(jdbc); }

    @AfterEach
    void invariants() { if (jdbc != null) DatabaseAssertions.consistent(jdbc); }

    @AfterAll
    void stopOwnedResources() {
        if (second != null) second.close();
        if (first != null) first.close();
        if (redis != null) redis.stop();
        if (mysql != null) mysql.stop();
    }

    @Test @Order(1)
    @DisplayName("真实 MySQL 迁移、注释、逻辑关联及跨实例可见性")
    void mysqlMigrationsAndCrossInstanceVisibility() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT column_comment FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='current_loan' AND column_name='loan_id'
                """, String.class)).contains("逻辑关联");
        var borrowed = one.request("POST", "/api/loans", 1L, Map.of("bookId", 14));
        assertThat(borrowed.status()).isEqualTo(201);
        assertThat(two.request("GET", "/api/loans/current", 1L, null).body().path("items").size()).isEqualTo(1);
    }

    @Test @Order(2)
    @DisplayName("两个应用争抢最后一个名额，最多成功一次")
    void distributedBookLockProtectsLastLicense() throws Exception {
        var results = parallel(
                () -> one.request("POST", "/api/loans", 1L, Map.of("bookId", 14)),
                () -> two.request("POST", "/api/loans", 2L, Map.of("bookId", 14)));
        assertThat(results.stream().map(ApiClient.Result::status)).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT available_licenses FROM book WHERE id=14", Integer.class)).isZero();
    }

    @Test @Order(3)
    @DisplayName("两个应用处理同账号不同图书，数量不能超过十")
    void distributedAccountLockProtectsQuota() throws Exception {
        seedNineLoans();
        var results = parallel(
                () -> one.request("POST", "/api/loans", 1L, Map.of("bookId", 10)),
                () -> two.request("POST", "/api/loans", 1L, Map.of("bookId", 11)));
        assertThat(results.stream().map(ApiClient.Result::status)).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT active_loan_count FROM library_account WHERE id=1", Integer.class)).isEqualTo(10);
    }

    @Test @Order(4)
    @DisplayName("跨实例归还同一笔借阅，归还结果与时间一致")
    void concurrentReturnsAcrossApplicationsAreIdempotent() throws Exception {
        long id = one.request("POST", "/api/loans", 1L, Map.of("bookId", 14)).body().path("id").asLong();
        var results = parallel(
                () -> one.request("POST", "/api/loans/" + id + "/return", 1L, null),
                () -> two.request("POST", "/api/loans/" + id + "/return", 1L, null));
        assertThat(results).allMatch(result -> result.status() == 200);
        assertThat(results.get(0).body().path("returnedAt")).isEqualTo(results.get(1).body().path("returnedAt"));
        assertThat(jdbc.queryForObject("SELECT active_loan_count FROM library_account WHERE id=1", Integer.class)).isZero();
    }

    @Test @Order(5)
    @DisplayName("实际删除已持有的 Redis 锁制造重叠执行，数据库仍保护额度")
    void lostRedisLeaseDoesNotBreakDatabaseInvariants() throws Exception {
        seedNineLoans();
        RedissonClient observer = first.getBean(RedissonClient.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = first.getBean(javax.sql.DataSource.class).getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement();
                 var row = statement.executeQuery("SELECT id FROM library_account WHERE id=1 FOR UPDATE")) {
                assertThat(row.next()).isTrue();
            }
            try {
                Future<ApiClient.Result> a = executor.submit(() -> one.request("POST", "/api/loans", 1L, Map.of("bookId", 10)));
                await(() -> observer.getLock("digital-library:lock:book:10").isLocked());
                observer.getKeys().delete("digital-library:lock:account:1");
                Future<ApiClient.Result> b = executor.submit(() -> two.request("POST", "/api/loans", 1L, Map.of("bookId", 11)));
                await(() -> observer.getLock("digital-library:lock:book:11").isLocked());
                blocker.rollback();
                var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
                assertThat(results.stream().map(ApiClient.Result::status)).containsExactlyInAnyOrder(201, 409);
            } finally { blocker.rollback(); }
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT active_loan_count FROM library_account WHERE id=1", Integer.class)).isEqualTo(10);
    }

    @Test @Order(99)
    @DisplayName("锁服务故障时读取可用；锁服务启动即不可用时应用仍可启动")
    void redisFailurePreservesReadsAndRejectsWrites() throws Exception {
        assertThat(one.request("POST", "/api/loans", 1L, Map.of("bookId", 14)).status()).isEqualTo(201);
        if (redis != null) {
            redis.stop(); // Owned disposable container only; never stop a caller-supplied Redis server.
        } else {
            first.getBean(RedissonClient.class).shutdown();
            second.getBean(RedissonClient.class).shutdown();
        }
        assertThat(one.request("GET", "/api/books", null, null).status()).isEqualTo(200);
        var rejected = two.request("POST", "/api/loans", 2L, Map.of("bookId", 10));
        assertThat(rejected.status()).isEqualTo(503);
        assertThat(rejected.body().path("code").asString()).isEqualTo("LOCK_SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isEqualTo(1);
        try (ServerSocket reserved = new ServerSocket(0)) {
            // A socket that does not speak Redis gives a deterministic unavailable endpoint.
            try (ConfigurableApplicationContext offline = startApplication("library-it-offline",
                    "redis://127.0.0.1:" + reserved.getLocalPort())) {
                ApiClient client = client(offline);
                assertThat(client.request("GET", "/api/books", null, null).status()).isEqualTo(200);
                assertThat(client.request("POST", "/api/loans", 2L, Map.of("bookId", 10)).status()).isEqualTo(503);
                assertThat(client.request("GET", "/actuator/health/readiness", null, null).status()).isEqualTo(200);
            }
        }
    }

    private void seedNineLoans() {
        LoanService service = first.getBean(LoanService.class);
        for (long id = 1; id <= 9; id++) service.borrow(1, id);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Redis coordination condition timed out");
            Thread.sleep(10);
        }
    }

    @FunctionalInterface private interface Request { ApiClient.Result run() throws Exception; }

    private List<ApiClient.Result> parallel(Request a, Request b) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = List.of(a, b).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("parallel gate timed out");
                return action.run();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
