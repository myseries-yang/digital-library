package com.example.library;

import com.example.library.account.persistence.AccountRepository;
import com.example.library.book.persistence.BookRepository;
import com.example.library.book.model.Book;
import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.locking.LockCoordinator;
import com.example.library.infrastructure.locking.LockCoordinator.LockHandle;
import com.example.library.support.DatabaseTestMapper;
import com.example.library.loan.application.LoanService;
import com.example.library.loan.application.LoanView;
import com.example.library.loan.application.LoanQueries;
import com.example.library.loan.model.LoanScope;
import com.example.library.loan.persistence.LoanViewMapper;
import com.example.library.infrastructure.transaction.CommandTransactions;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LibraryIntegrationTest {
    @Autowired DatabaseTestMapper database;
    @Autowired LoanService service;
    @Autowired PolicyService policyService;
    @Autowired AccountRepository accounts;
    @Autowired LoanViewMapper loanViewMapper;
    @Autowired ObjectMapper mapper;
    @Value("${local.server.port}") int port;
    @MockitoSpyBean BookRepository books;
    @MockitoSpyBean LoanRepository loans;
    @MockitoSpyBean PolicyRepository policies;
    @MockitoSpyBean LockCoordinator locks;
    @MockitoSpyBean CommandTransactions transactions;
    private ApiClient api;

    @BeforeEach
    void reset() {
        DatabaseAssertions.reset(database);
        api = new ApiClient(port, mapper);
    }

    @AfterEach
    void invariants() { DatabaseAssertions.consistent(database); }

    @Test
    @DisplayName("浏览、搜索、分页、参数错误及请求编号")
    void browseAndValidateApiContract() throws Exception {
        ApiClient.Result page = api.request("GET", "/api/books?size=6", null, null);
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getBody().path("items").size()).isEqualTo(6);
        assertThat(page.getBody().path("hasNext").asBoolean()).isTrue();
        assertThat(page.getBody().path("items").get(0).path("id").asLong()).isEqualTo(1);
        assertThat(api.request("GET", "/api/books?keyword=%25", null, null).getBody().path("items").size()).isZero();
        assertThat(api.request("GET", "/api/books/14", null, null).getBody().path("totalLicenses").asInt()).isEqualTo(1);
        ApiClient.Result invalid = api.request("GET", "/api/books?size=101", null, null);
        assertThat(invalid.getStatus()).isEqualTo(400);
        assertThat(invalid.getBody().path("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(invalid.getBody().path("requestId").asString()).isEqualTo(invalid.getRequestId()).isNotBlank();
    }

    @Test
    @DisplayName("借阅、重复在借、幂等归还以及旧请求不误还新借阅")
    void borrowReturnAndReplayOldReturn() throws Exception {
        ApiClient.Result borrowed = api.request("POST", "/api/loans", 1L, Collections.singletonMap("bookId", 14));
        assertThat(borrowed.getStatus()).isEqualTo(201);
        long id = borrowed.getBody().path("id").asLong();
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(1);
        assertThat(books.require(14).getAvailableLicenses()).isZero();
        assertThat(api.request("POST", "/api/loans", 1L, Collections.singletonMap("bookId", 14)).getStatus()).isEqualTo(409);
        ApiClient.Result returned = api.request("POST", "/api/loans/" + id + "/return", 1L, null);
        ApiClient.Result repeated = api.request("POST", "/api/loans/" + id + "/return", 1L, null);
        assertThat(returned.getStatus()).isEqualTo(200);
        assertThat(repeated.getBody()).isEqualTo(returned.getBody());
        assertThat(accounts.require(1).getActiveLoanCount()).isZero();
        assertThat(books.require(14).getAvailableLicenses()).isEqualTo(1);
        long newId = api.request("POST", "/api/loans", 1L, Collections.singletonMap("bookId", 14)).getBody().path("id").asLong();
        assertThat(newId).isNotEqualTo(id);
        assertThat(api.request("POST", "/api/loans/" + id + "/return", 1L, null).getStatus()).isEqualTo(200);
        assertThat(loans.require(newId).getReturnedAt()).isNull();
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("账号头、资源存在及借阅归属校验")
    void validatesIdentityAndOwnership() throws Exception {
        assertThat(api.request("POST", "/api/loans", null, Collections.singletonMap("bookId", 14)).getStatus()).isEqualTo(400);
        assertThat(api.request("POST", "/api/loans", 999L, Collections.singletonMap("bookId", 14)).getStatus()).isEqualTo(404);
        assertThat(api.request("POST", "/api/loans", 1L, Collections.singletonMap("bookId", 999)).getStatus()).isEqualTo(404);
        assertThat(api.request("POST", "/api/loans", 1L, Collections.singletonMap("bookId", -1)).getStatus()).isEqualTo(400);
        LoanView loan = service.borrow(1, 14);
        assertThat(api.request("POST", "/api/loans/" + loan.getId() + "/return", 2L, null).getStatus()).isEqualTo(403);
        assertThat(loans.require(loan.getId()).getReturnedAt()).isNull();
    }

    @Test
    @DisplayName("逾期仍属当前借阅、占用额度，并允许额度内继续借阅")
    void overdueRemainsCurrentAndDoesNotBanBorrowing() throws Exception {
        LoanView loan = service.borrow(1, 12);
        Instant now = Instant.now();
        database.setLoanDates(loan.getId(), now.minus(16, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        ApiClient.Result current = api.request("GET", "/api/loans/current", 1L, null);
        assertThat(current.getBody().path("items").get(0).path("overdue").asBoolean()).isTrue();
        service.borrow(1, 14);
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(2);
        ApiClient.Result overdue = api.request("GET", "/api/admin/loans?accountId=1&scope=OVERDUE", null, null);
        assertThat(overdue.getBody().path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("规则版本冲突、已有期限保留、新借阅使用新期限")
    void policyChangesAreProspective() throws Exception {
        LoanView original = service.borrow(1, 10);
        ApiClient.Result updated = api.request("PUT", "/api/admin/borrowing-policy", null,
                policyRequest(1, 7, 1));
        assertThat(updated.getStatus()).isEqualTo(200);
        assertThat(updated.getBody().path("version").asLong()).isEqualTo(2);
        assertThat(loans.require(original.getId()).getDueAt()).isEqualTo(original.getDueAt());
        assertBusiness(ErrorCode.ACCOUNT_LIMIT_REACHED, () -> service.borrow(1, 11));
        service.returnLoan(1, original.getId());
        LoanView next = service.borrow(1, 11);
        assertThat(next.getDueAt()).isEqualTo(next.getBorrowedAt().plus(7, ChronoUnit.DAYS));
        assertThat(loans.require(next.getId()).getLoanDaysSnapshot()).isEqualTo(7);
        assertThat(api.request("PUT", "/api/admin/borrowing-policy", null,
                policyRequest(2, 8, 1)).getStatus()).isEqualTo(409);
        assertThat(api.request("PUT", "/api/admin/borrowing-policy", null,
                policyRequest(0, 8, 2)).getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("图书条件扣减失败，已占用账号额度回滚")
    void quotaRollsBackWhenStockReservationFails() {
        doReturn(0).when(books).reserveOne(eq(14L), any());
        assertBusiness(ErrorCode.BOOK_UNAVAILABLE, () -> service.borrow(1, 14));
        assertThat(accounts.require(1).getActiveLoanCount()).isZero();
        assertThat(database.countLoans()).isZero();
    }

    @Test
    @DisplayName("借阅明细插入失败，两个数量都回滚")
    void bothCountersRollBackWhenLoanInsertFails() {
        doThrow(new DataIntegrityViolationException("injected insert failure")).when(loans).insert(any(Loan.class));
        assertThatThrownBy(() -> service.borrow(1, 14)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(accounts.require(1).getActiveLoanCount()).isZero();
        assertThat(books.require(14).getAvailableLicenses()).isEqualTo(1);
    }

    @Test
    @DisplayName("程序拒绝账号、图书不匹配的当前关系，整笔借阅回滚")
    void logicalRelationshipMismatchRollsBack() {
        doAnswer(invocation -> {
            loans.insertCurrent(invocation.getArgument(0), 2L, invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(loans).insertCurrent(anyLong(), eq(1L), anyLong(), any());
        assertBusiness(ErrorCode.DATA_INCONSISTENCY, () -> service.borrow(1, 14));
        assertThat(database.countLoans()).isZero();
        assertThat(accounts.require(1).getActiveLoanCount()).isZero();
    }

    @Test
    @DisplayName("归还释放图书失败，归还时间及账号数量一起回滚")
    void failedReturnRestoresOriginalState() {
        LoanView borrowed = service.borrow(1, 14);
        doReturn(0).when(books).releaseOne(eq(14L), any());
        assertBusiness(ErrorCode.DATA_INCONSISTENCY, () -> service.returnLoan(1, borrowed.getId()));
        assertThat(loans.require(borrowed.getId()).getReturnedAt()).isNull();
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(1);
        assertThat(books.require(14).getAvailableLicenses()).isZero();
    }

    @Test
    @DisplayName("删除当前关系失败，归还状态及两个数量一起回滚")
    void failedRelationshipDeleteRollsBackWholeReturn() {
        LoanView borrowed = service.borrow(1, 14);
        doReturn(0).when(loans).deleteCurrent(borrowed.getId(), 1, 14);
        assertBusiness(ErrorCode.DATA_INCONSISTENCY, () -> service.returnLoan(1, borrowed.getId()));
        assertThat(loans.require(borrowed.getId()).getReturnedAt()).isNull();
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("获取锁失败没有业务写入")
    void lockFailureDoesNotEnterWriteProtocol() {
        doThrow(new BusinessException(ErrorCode.LOCK_ACQUIRE_TIMEOUT))
                .when(locks).acquire(1L, 14L);
        assertBusiness(ErrorCode.LOCK_ACQUIRE_TIMEOUT, () -> service.borrow(1, 14));
        assertThat(database.countLoans()).isZero();
    }

    @Test
    @DisplayName("锁失效时：最后一个名额仍最多借出一次")
    void databaseProtectsLastLicenseWithoutCoordination() throws Exception {
        bypassLocks();
        List<Outcome> outcomes = race(() -> service.borrow(1, 14), () -> service.borrow(2, 14));
        assertThat(outcomes.stream().filter(value -> value.getLoan() != null).count()).isEqualTo(1);
        assertThat(books.require(14).getAvailableLicenses()).isZero();
        assertThat(database.countCurrentLoansForBook(14)).isEqualTo(1);
    }

    @Test
    @DisplayName("锁失效时：九本并发借两本仍最多成功一本")
    void databaseProtectsAccountLimitWithoutCoordination() throws Exception {
        for (long book = 1; book <= 9; book++) service.borrow(1, book);
        bypassLocks();
        List<Outcome> outcomes = race(() -> service.borrow(1, 10), () -> service.borrow(1, 11));
        assertThat(outcomes.stream().filter(value -> value.getLoan() != null).count()).isEqualTo(1);
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("锁失效时：当前关系唯一约束阻止同书重复在借")
    void databaseProtectsCurrentRelationshipWithoutCoordination() throws Exception {
        bypassLocks();
        List<Outcome> outcomes = race(() -> service.borrow(1, 10), () -> service.borrow(1, 10));
        assertThat(outcomes.stream().filter(value -> value.getLoan() != null).count()).isEqualTo(1);
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(1);
        assertThat(database.countLoans()).isEqualTo(1);
    }

    @Test
    @DisplayName("锁失效时：并发归还两个请求都成功，额度只释放一次")
    void concurrentReturnsAreIdempotentWithoutCoordination() throws Exception {
        LoanView borrowed = service.borrow(1, 14);
        bypassLocks();
        List<Outcome> outcomes = race(() -> service.returnLoan(1, borrowed.getId()), () -> service.returnLoan(1, borrowed.getId()));
        assertThat(outcomes).allMatch(value -> value.getLoan() != null);
        assertThat(outcomes.get(0).getLoan().getReturnedAt()).isEqualTo(outcomes.get(1).getLoan().getReturnedAt());
        assertThat(accounts.require(1).getActiveLoanCount()).isZero();
        assertThat(books.require(14).getAvailableLicenses()).isEqualTo(1);
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
            assertThat(loans.require(result.getId()).getPolicyVersion()).isEqualTo(1);
            assertThat(result.getDueAt()).isEqualTo(result.getBorrowedAt().plus(15, ChronoUnit.DAYS));
            assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(10);
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
        service.returnLoan(1, first.getId());
        ApiClient.Result returned = api.request("GET", "/api/admin/loans?accountId=1&scope=RETURNED", null, null);
        assertThat(returned.getBody().path("items").size()).isEqualTo(1);
        assertThat(returned.getBody().path("items").get(0).path("id").asLong()).isEqualTo(first.getId());
        assertThat(api.request("GET", "/api/admin/loans?scope=INVALID", null, null).getStatus()).isEqualTo(400);
        ApiClient.Result openapi = api.request("GET", "/v3/api-docs", null, null);
        assertThat(openapi.getStatus()).isEqualTo(200);
        assertThat(openapi.getBody().path("paths").has("/api/loans/{loanId}/return")).isTrue();
        for (String path : Arrays.asList("/", "/admin")) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("阅界");
        }
    }

    @Test
    @DisplayName("XML 联表查询组合筛选、逾期边界与借阅分页保持一致")
    void xmlFiltersAndPaginationUseTheSameOverdueBoundary() {
        LoanView overdue = service.borrow(1, 10);
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.MICROS);
        database.setLoanDates(overdue.getId(), boundary.minus(15, ChronoUnit.DAYS), boundary);
        LoanView current = service.borrow(1, 11);
        LoanView returned = service.borrow(2, 10);
        service.returnLoan(2, returned.getId());
        LoanQueries queries = new LoanQueries(loanViewMapper, Clock.fixed(boundary, ZoneOffset.UTC));
        PageQuery page = new PageQuery(0, 20);

        SlicePage<LoanView> filtered = queries.search(1L, 10L, LoanScope.OVERDUE, page);
        assertThat(filtered.getItems()).extracting(LoanView::getId).containsExactly(overdue.getId());
        assertThat(filtered.getItems().get(0).isOverdue()).isTrue();
        assertThat(queries.search(null, 10L, LoanScope.CURRENT, page).getItems())
                .extracting(LoanView::getId).containsExactly(overdue.getId());
        assertThat(queries.search(null, null, LoanScope.RETURNED, page).getItems())
                .extracting(LoanView::getId).containsExactly(returned.getId());

        SlicePage<LoanView> first = queries.search(null, null, LoanScope.ALL, new PageQuery(0, 1));
        assertThat(first.getItems()).extracting(LoanView::getId).containsExactly(returned.getId());
        assertThat(first.isHasNext()).isTrue();
        assertThat(queries.search(null, null, LoanScope.ALL, new PageQuery(1, 1)).getItems())
                .extracting(LoanView::getId).containsExactly(current.getId());
        assertThat(queries.search(null, null, LoanScope.ALL, new PageQuery(2, 1)).isHasNext()).isFalse();
        assertThat(queries.search(null, null, LoanScope.ALL, new PageQuery(3, 1)).getItems()).isEmpty();
    }

    @Test
    @DisplayName("MyBatis 时间映射不受 JVM 时区影响，空归还时间和微秒精度保留")
    void utcMappingPreservesNullAndMicrosecondsInAnotherJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            LoanView borrowed = service.borrow(1, 14);
            Loan saved = loans.require(borrowed.getId());
            assertThat(saved.getBorrowedAt()).isEqualTo(borrowed.getBorrowedAt());
            assertThat(saved.getDueAt()).isEqualTo(borrowed.getDueAt());
            assertThat(saved.getBorrowedAt().getNano() % 1000).isZero();
            assertThat(saved.getReturnedAt()).isNull();
            assertThat(database.borrowedLocalDateTime(saved.getId()))
                    .isEqualTo(LocalDateTime.ofInstant(borrowed.getBorrowedAt(), ZoneOffset.UTC));
            LoanView returned = service.returnLoan(1, borrowed.getId());
            assertThat(loans.require(saved.getId()).getReturnedAt()).isEqualTo(returned.getReturnedAt());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("实际提交及回滚完成后才释放借阅业务锁")
    void businessLocksCloseAfterActualCommitOrRollback() {
        LockHandle handle = mock(LockHandle.class);
        doReturn(handle).when(locks).acquire(anyLong(), anyLong());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(handle).close();
        InOrder order = inOrder(locks, transactions, handle);

        service.borrow(1, 14);
        order.verify(locks).acquire(1, 14);
        order.verify(transactions).begin();
        order.verify(transactions).commit(any());
        order.verify(handle).close();

        doReturn(0).when(books).reserveOne(eq(13L), any());
        assertBusiness(ErrorCode.BOOK_UNAVAILABLE, () -> service.borrow(1, 13));
        order.verify(locks).acquire(1, 13);
        order.verify(transactions).begin();
        order.verify(transactions).rollback(any());
        order.verify(handle).close();
        assertThat(accounts.require(1).getActiveLoanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("XML 图书查询保留字面通配符、大小写及 Unicode 空白处理")
    void xmlBookSearchPreservesLiteralKeywordsAndWhitespace() throws Exception {
        Book original = books.require(15);
        try {
            database.updateBookText(15, "Readable %_! Title", "MixedCase Author");
            String keyword = URLEncoder.encode("\u2003 %_! \u2003", "UTF-8");
            ApiClient.Result literal = api.request("GET", "/api/books?keyword=" + keyword, null, null);
            assertThat(literal.getBody().path("items").size()).isEqualTo(1);
            assertThat(literal.getBody().path("items").get(0).path("id").asLong()).isEqualTo(15);
            ApiClient.Result author = api.request("GET", "/api/books?keyword=mixedcase", null, null);
            assertThat(author.getBody().path("items").get(0).path("id").asLong()).isEqualTo(15);
            String tooLong = String.join("", Collections.nCopies(256, "a"));
            assertThat(api.request("GET", "/api/books?keyword=" + tooLong, null, null).getStatus()).isEqualTo(400);
        } finally {
            database.updateBookText(15, original.getTitle(), original.getAuthor());
        }
    }

    private Map<String, Object> policyRequest(int limit, int days, long version) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("maxActiveLoans", limit);
        request.put("loanDays", days);
        request.put("expectedVersion", version);
        return request;
    }

    private void bypassLocks() {
        doReturn(mock(LockHandle.class)).when(locks).acquire(anyLong(), anyLong());
    }

    private static final class Outcome {
        private final LoanView loan;
        private final ErrorCode error;

        Outcome(LoanView loan, ErrorCode error) {
            this.loan = loan;
            this.error = error;
        }

        LoanView getLoan() {
            return loan;
        }

        ErrorCode getError() {
            return error;
        }
    }

    private List<Outcome> race(Supplier<LoanView> first, Supplier<LoanView> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<Future<Outcome>>();
            for (Supplier<LoanView> action : Arrays.asList(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("race gate timed out");
                    }
                    try {
                        return new Outcome(action.get(), null);
                    } catch (BusinessException ex) {
                        return new Outcome(null, ex.getCode());
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return Arrays.asList(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private void assertBusiness(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(expected);
    }
}
