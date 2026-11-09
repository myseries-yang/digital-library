INSERT INTO borrowing_policy (id, max_active_loans, loan_days, version) VALUES (1, 10, 15, 1);
INSERT INTO library_account (id, account_no, account_name) VALUES
(1, 'LIB001', '自由阅读账号'), (2, 'LIB002', '额度临界账号'),
(3, 'LIB003', '逾期示例账号'), (4, 'LIB004', '历史记录账号');

INSERT INTO book (id, title, author, description, total_licenses, available_licenses) VALUES
(1, 'Java 并发：从线程到协作', '技术编辑组', '理解线程安全、互斥与并发程序的边界。演示书目，不提供数字内容。', 3, 3),
(2, '数据库事务与一致性', '数据工程组', '从原子操作出发，认识事务与数据约束。', 3, 3),
(3, '可维护的应用架构', '软件设计组', '用清晰的职责划分组织服务与业务流程。', 3, 3),
(4, '领域建模入门', '系统设计组', '用业务语言理解账号、图书与借阅关系。', 3, 3),
(5, 'Redis 工程实践', '基础设施组', '理解分布式锁的使用边界和失效情形。', 3, 3),
(6, 'API 设计手册', '接口设计组', '请求、响应、错误语义与可测试的接口合约。', 3, 3),
(7, '测试驱动的后端开发', '质量工程组', '从模型测试到真实组件的集成验证。', 3, 3),
(8, 'Spring 应用开发', 'Java 编辑组', '依赖注入、数据访问和事务组织。', 3, 3),
(9, '只有一个名额的书', '图书馆编辑组', '该书的唯一授权已借出，可以验证名额不足场景。', 1, 1),
(10, '分布式系统的边界', '系统设计组', '协调、租约、网络失败与最终资源保护。', 3, 3),
(11, '代码整洁与工程取舍', '软件设计组', '围绕问题选择适度的抽象。', 3, 3),
(12, '时间、期限与状态', '图书馆编辑组', '已由 LIB003 借阅并逾期，逾期仍占用名额。', 2, 2),
(13, '借阅历史的设计', '图书馆编辑组', 'LIB004 已经归还，可在管理页面查看历史。', 2, 2),
(14, '最后一个借阅名额', '质量工程组', '仅一个可用授权，适合演示并发竞争。', 1, 1),
(15, '系统设计周刊 · 创刊号', '技术编辑组', '期刊和电子书统一作为可借阅条目。', 2, 2);

INSERT INTO loan (account_id, book_id, policy_id, policy_version, max_active_loans_snapshot, loan_days_snapshot, borrowed_at, due_at)
SELECT 2, id, 1, 1, 10, 15, DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('DAY', 14, CURRENT_TIMESTAMP) FROM book WHERE id BETWEEN 1 AND 9 ORDER BY id;

INSERT INTO loan (account_id, book_id, policy_id, policy_version, max_active_loans_snapshot, loan_days_snapshot, borrowed_at, due_at)
VALUES (3, 12, 1, 1, 10, 15, DATEADD('DAY', -16, CURRENT_TIMESTAMP), DATEADD('DAY', -1, CURRENT_TIMESTAMP));

INSERT INTO loan (account_id, book_id, policy_id, policy_version, max_active_loans_snapshot, loan_days_snapshot, borrowed_at, due_at, returned_at)
VALUES (4, 13, 1, 1, 10, 15, DATEADD('DAY', -20, CURRENT_TIMESTAMP), DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('DAY', -6, CURRENT_TIMESTAMP));

INSERT INTO current_loan (loan_id, account_id, book_id)
SELECT id, account_id, book_id FROM loan WHERE returned_at IS NULL ORDER BY id;

UPDATE library_account SET active_loan_count = (SELECT COUNT(*) FROM current_loan c WHERE c.account_id = library_account.id);
UPDATE book SET available_licenses = total_licenses - (SELECT COUNT(*) FROM current_loan c WHERE c.book_id = book.id);

ALTER TABLE library_account ALTER COLUMN id RESTART WITH 5;
ALTER TABLE book ALTER COLUMN id RESTART WITH 16;
