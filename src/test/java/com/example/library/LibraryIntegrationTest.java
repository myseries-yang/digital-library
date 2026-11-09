package com.example.library;

import com.example.library.account.persistence.AccountRepository;
import com.example.library.book.persistence.BookRepository;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.locking.LocalLockCoordinator;
import com.example.library.infrastructure.persistence.JdbcTime;
import com.example.library.loan.application.LoanService;
import com.example.library.loan.application.LoanView;
import com.example.library.loan.model.Loan;
import com.example.library.loan.persistence.LoanRepository;
import com.example.library.policy.application.PolicyService;
import com.example.library.policy.model.BorrowingPolicy;
import com.example.library.policy.persistence.PolicyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LibraryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanService service;
    @Autowired PolicyService policyService;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper mapper;
    @Value("${local.server.port}") int port;
    @MockitoSpyBean BookRepository books;
    @MockitoSpyBean LoanRepository loans;
    @MockitoSpyBean PolicyRepository policies;
    @MockitoSpyBean LocalLockCoordinator locks;
    private ApiClient api;

    @BeforeEach
    void reset() {
        DatabaseAssertions.reset(jdbc);
        api = new ApiClient(port, mapper);
    }

    @AfterEach
    void invariants() { DatabaseAssertions.consistent(jdbc); }

    @Test
    @DisplayName("浏览、搜索、分页、参数错误及请求编号")
    void browseAndValidateApiContract() throws Exception {
        var page = api.request("GET", "/api/books?size=6", null, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("items").size()).isEqualTo(6);
        assertThat(page.body().path("hasNext").asBoolean()).isTrue();
        assertThat(page.body().path("items").get(0).path("id").asLong()).isEqualTo(1);
        assertThat(api.request("GET", "/api/books?keyword=%25", null, null).body().path("items").size()).isZero();
        assertThat(api.request("GET", "/api/books/14", null, null).body().path("totalLicenses").asInt()).isEqualTo(1);
        var invalid = api.request("GET", "/api/books?size=101", null, null);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().path("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(invalid.body().path("requestId").asString()).isEqualTo(invalid.requestId()).isNotBlank();
    }

    @Test
    @DisplayName("借阅、重复在借、幂等归还以及旧请求不误还新借阅")
    void borrowReturnAndReplayOldReturn() throws Exception {
        var borrowed = api.request("POST", "/api/loans", 1L, Map.of("bookId", 14));
        assertThat(borrowed.status()).isEqualTo(201);
        long id = borrowed.body().path("id").asLong();
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(1);
        assertThat(books.require(14).availableLicenses()).isZero();
        assertThat(api.request("POST", "/api/loans", 1L, Map.of("bookId", 14)).status()).isEqualTo(409);
        var returned = api.request("POST", "/api/loans/" + id + "/return", 1L, null);
        var repeated = api.request("POST", "/api/loans/" + id + "/return", 1L, null);
        assertThat(returned.status()).isEqualTo(200);
        assertThat(repeated.body()).isEqualTo(returned.body());
        assertThat(accounts.require(1).activeLoanCount()).isZero();
        assertThat(books.require(14).availableLicenses()).isEqualTo(1);
        long newId = api.request("POST", "/api/loans", 1L, Map.of("bookId", 14)).body().path("id").asLong();
        assertThat(newId).isNotEqualTo(id);
        assertThat(api.request("POST", "/api/loans/" + id + "/return", 1L, null).status()).isEqualTo(200);
        assertThat(loans.require(newId).returnedAt()).isNull();
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("账号头、资源存在及借阅归属校验")
    void validatesIdentityAndOwnership() throws Exception {
        assertThat(api.request("POST", "/api/loans", null, Map.of("bookId", 14)).status()).isEqualTo(400);
        assertThat(api.request("POST", "/api/loans", 999L, Map.of("bookId", 14)).status()).isEqualTo(404);
        assertThat(api.request("POST", "/api/loans", 1L, Map.of("bookId", 999)).status()).isEqualTo(404);
        assertThat(api.request("POST", "/api/loans", 1L, Map.of("bookId", -1)).status()).isEqualTo(400);
        LoanView loan = service.borrow(1, 14);
        assertThat(api.request("POST", "/api/loans/" + loan.id() + "/return", 2L, null).status()).isEqualTo(403);
        assertThat(loans.require(loan.id()).returnedAt()).isNull();
    }

    @Test
    @DisplayName("逾期仍属当前借阅、占用额度，并允许额度内继续借阅")
    void overdueRemainsCurrentAndDoesNotBanBorrowing() throws Exception {
        LoanView loan = service.borrow(1, 12);
        Instant now = Instant.now();
        jdbc.update("UPDATE loan SET borrowed_at=?, due_at=? WHERE id=?",
                JdbcTime.write(now.minus(16, ChronoUnit.DAYS)), JdbcTime.write(now.minus(1, ChronoUnit.DAYS)), loan.id());
        var current = api.request("GET", "/api/loans/current", 1L, null);
        assertThat(current.body().path("items").get(0).path("overdue").asBoolean()).isTrue();
        service.borrow(1, 14);
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(2);
        var overdue = api.request("GET", "/api/admin/loans?accountId=1&scope=OVERDUE", null, null);
        assertThat(overdue.body().path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("规则版本冲突、已有期限保留、新借阅使用新期限")
    void policyChangesAreProspective() throws Exception {
        LoanView original = service.borrow(1, 10);
        var updated = api.request("PUT", "/api/admin/borrowing-policy", null,
                Map.of("maxActiveLoans", 1, "loanDays", 7, "expectedVersion", 1));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("version").asLong()).isEqualTo(2);
        assertThat(loans.require(original.id()).dueAt()).isEqualTo(original.dueAt());
        assertBusiness(ErrorCode.ACCOUNT_LIMIT_REACHED, () -> service.borrow(1, 11));
        service.returnLoan(1, original.id());
        LoanView next = service.borrow(1, 11);
        assertThat(next.dueAt()).isEqualTo(next.borrowedAt().plus(7, ChronoUnit.DAYS));
        assertThat(loans.require(next.id()).loanDaysSnapshot()).isEqualTo(7);
        assertThat(api.request("PUT", "/api/admin/borrowing-policy", null,
                Map.of("maxActiveLoans", 2, "loanDays", 8, "expectedVersion", 1)).status()).isEqualTo(409);
        assertThat(api.request("PUT", "/api/admin/borrowing-policy", null,
                Map.of("maxActiveLoans", 0, "loanDays", 8, "expectedVersion", 2)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("图书条件扣减失败，已占用账号额度回滚")
    void quotaRollsBackWhenStockReservationFails() {
        doReturn(0).when(books).reserveOne(eq(14L), any());
        assertBusiness(ErrorCode.BOOK_UNAVAILABLE, () -> service.borrow(1, 14));
        assertThat(accounts.require(1).activeLoanCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isZero();
    }

    @Test
    @DisplayName("借阅明细插入失败，两个数量都回滚")
    void bothCountersRollBackWhenLoanInsertFails() {
        doThrow(new DataIntegrityViolationException("injected insert failure")).when(loans).insert(any(Loan.class));
        assertThatThrownBy(() -> service.borrow(1, 14)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(accounts.require(1).activeLoanCount()).isZero();
        assertThat(books.require(14).availableLicenses()).isEqualTo(1);
    }

    @Test
    @DisplayName("程序拒绝账号、图书不匹配的当前关系，整笔借阅回滚")
    void logicalRelationshipMismatchRollsBack() {
        doAnswer(invocation -> {
            loans.insertCurrent(invocation.getArgument(0), 2L, invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(loans).insertCurrent(anyLong(), eq(1L), anyLong(), any());
        assertBusiness(ErrorCode.DATA_INCONSISTENCY, () -> service.borrow(1, 14));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isZero();
        assertThat(accounts.require(1).activeLoanCount()).isZero();
    }

    @Test
    @DisplayName("归还释放图书失败，归还时间及账号数量一起回滚")
    void failedReturnRestoresOriginalState() {
        LoanView borrowed = service.borrow(1, 14);
        doReturn(0).when(books).releaseOne(eq(14L), any());
        assertBusiness(ErrorCode.DATA_INCONSISTENCY, () -> service.returnLoan(1, borrowed.id()));
        assertThat(loans.require(borrowed.id()).returnedAt()).isNull();
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(1);
        assertThat(books.require(14).availableLicenses()).isZero();
    }

    @Test
    @DisplayName("删除当前关系失败，归还状态及两个数量一起回滚")
    void failedRelationshipDeleteRollsBackWholeReturn() {
        LoanView borrowed = service.borrow(1, 14);
        doReturn(0).when(loans).deleteCurrent(borrowed.id(), 1, 14);
        assertBusiness(ErrorCode.DATA_INCONSISTENCY, () -> service.returnLoan(1, borrowed.id()));
        assertThat(loans.require(borrowed.id()).returnedAt()).isNull();
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("获取锁失败没有业务写入")
    void lockFailureDoesNotEnterWriteProtocol() {
        doThrow(new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT))
                .when(locks).withLocks(eq(1L), eq(14L), any());
        assertBusiness(ErrorCode.LOCK_ACQUIRE_TIMEOUT, () -> service.borrow(1, 14));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isZero();
    }

    @Test
    @DisplayName("锁失效时：最后一个名额仍最多借出一次")
    void databaseProtectsLastLicenseWithoutCoordination() throws Exception {
        bypassLocks();
        var outcomes = race(() -> service.borrow(1, 14), () -> service.borrow(2, 14));
        assertThat(outcomes.stream().filter(value -> value.loan() != null).count()).isEqualTo(1);
        assertThat(books.require(14).availableLicenses()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM current_loan WHERE book_id=14", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("锁失效时：九本并发借两本仍最多成功一本")
    void databaseProtectsAccountLimitWithoutCoordination() throws Exception {
        for (long book = 1; book <= 9; book++) service.borrow(1, book);
        bypassLocks();
        var outcomes = race(() -> service.borrow(1, 10), () -> service.borrow(1, 11));
        assertThat(outcomes.stream().filter(value -> value.loan() != null).count()).isEqualTo(1);
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("锁失效时：当前关系唯一约束阻止同书重复在借")
    void databaseProtectsCurrentRelationshipWithoutCoordination() throws Exception {
        bypassLocks();
        var outcomes = race(() -> service.borrow(1, 10), () -> service.borrow(1, 10));
        assertThat(outcomes.stream().filter(value -> value.loan() != null).count()).isEqualTo(1);
        assertThat(accounts.require(1).activeLoanCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("锁失效时：并发归还两个请求都成功，额度只释放一次")
    void concurrentReturnsAreIdempotentWithoutCoordination() throws Exception {
        LoanView borrowed = service.borrow(1, 14);
        bypassLocks();
        var outcomes = race(() -> service.returnLoan(1, borrowed.id()), () -> service.returnLoan(1, borrowed.id()));
        assertThat(outcomes).allMatch(value -> value.loan() != null);
        assertThat(outcomes.get(0).loan().returnedAt()).isEqualTo(outcomes.get(1).loan().returnedAt());
        assertThat(accounts.require(1).activeLoanCount()).isZero();
        assertThat(books.require(14).availableLicenses()).isEqualTo(1);
    }

    @Test
    @DisplayName("在途借阅允许按旧规则快照完成，保存后新请求使用新规则")
    void inFlightBorrowingKeepsItsPolicySnapshot() throws Exception {
        for (long book = 1; book <= 9; book++) service.borrow(1, book);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            BorrowingPolicy snapshot = (BorrowingPolicy) invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("old-policy-request")) {
                snapshotRead.countDown();
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new AssertionError("policy test gate timed out");
            }
            return snapshot;
        }).when(policies).current();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "old-policy-request"));
        try {
            Future<LoanView> pending = executor.submit(() -> service.borrow(1, 10));
            assertThat(snapshotRead.await(5, TimeUnit.SECONDS)).isTrue();
            policyService.update(5, 7, 1);
            proceed.countDown();
            LoanView result = pending.get(10, TimeUnit.SECONDS);
            assertThat(loans.require(result.id()).policyVersion()).isEqualTo(1);
            assertThat(result.dueAt()).isEqualTo(result.borrowedAt().plus(15, ChronoUnit.DAYS));
            assertThat(accounts.require(1).activeLoanCount()).isEqualTo(10);
            assertBusiness(ErrorCode.ACCOUNT_LIMIT_REACHED, () -> service.borrow(1, 11));
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("管理筛选、页面和 OpenAPI 可以访问")
    void queryViewsPagesAndOpenApiAreAvailable() throws Exception {
        LoanView first = service.borrow(1, 10);
        service.borrow(2, 11);
        service.returnLoan(1, first.id());
        var returned = api.request("GET", "/api/admin/loans?accountId=1&scope=RETURNED", null, null);
        assertThat(returned.body().path("items").size()).isEqualTo(1);
        assertThat(returned.body().path("items").get(0).path("id").asLong()).isEqualTo(first.id());
        assertThat(api.request("GET", "/api/admin/loans?scope=INVALID", null, null).status()).isEqualTo(400);
        var openapi = api.request("GET", "/v3/api-docs", null, null);
        assertThat(openapi.status()).isEqualTo(200);
        assertThat(openapi.body().path("paths").has("/api/loans/{loanId}/return")).isTrue();
        for (String path : List.of("/", "/admin")) {
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("阅界");
        }
    }

    private void bypassLocks() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get())
                .when(locks).withLocks(anyLong(), anyLong(), any());
    }

    private record Outcome(LoanView loan, ErrorCode error) {}

    private List<Outcome> race(Supplier<LoanView> first, Supplier<LoanView> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("race gate timed out");
                try { return new Outcome(action.get(), null); }
                catch (BusinessException ex) { return new Outcome(null, ex.code()); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private void assertBusiness(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo(expected);
    }
}
