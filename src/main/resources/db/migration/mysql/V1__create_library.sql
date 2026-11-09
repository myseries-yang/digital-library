CREATE TABLE library_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '账号主键',
    account_no VARCHAR(64) NOT NULL COMMENT '唯一账号编号',
    account_name VARCHAR(100) NOT NULL COMMENT '账号展示名称，不代表自然人姓名',
    active_loan_count INT NOT NULL DEFAULT 0 COMMENT '当前未归还借阅数量，包含逾期',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近更新时间，UTC',
    CONSTRAINT uk_account_no UNIQUE (account_no),
    CONSTRAINT ck_account_count CHECK (active_loan_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图书馆账号：独立借阅主体，不关联自然人';

CREATE TABLE book (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '图书主键',
    title VARCHAR(255) NOT NULL COMMENT '书名',
    author VARCHAR(255) NOT NULL COMMENT '作者展示信息',
    description TEXT COMMENT '图书简介',
    total_licenses INT NOT NULL COMMENT '总授权名额',
    available_licenses INT NOT NULL COMMENT '当前可借授权名额',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近更新时间，UTC',
    CONSTRAINT ck_book_licenses CHECK (total_licenses >= 0 AND available_licenses >= 0 AND available_licenses <= total_licenses)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图书信息与授权名额';

CREATE TABLE borrowing_policy (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '规则主键，固定为1',
    max_active_loans INT NOT NULL COMMENT '每账号最大同时在借数量',
    loan_days INT NOT NULL COMMENT '借阅期限天数，按24小时一天计算',
    version BIGINT NOT NULL COMMENT '乐观更新版本号',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近更新时间，UTC',
    CONSTRAINT ck_single_policy CHECK (id = 1),
    CONSTRAINT ck_policy_values CHECK (max_active_loans > 0 AND loan_days > 0 AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局借阅规则：仅维护 id=1';

CREATE TABLE loan (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '借阅记录主键，归还操作使用此ID',
    account_id BIGINT NOT NULL COMMENT '逻辑关联 library_account.id，由程序事务校验，无数据库外键',
    book_id BIGINT NOT NULL COMMENT '逻辑关联 book.id，由程序事务校验，无数据库外键',
    policy_id BIGINT NOT NULL COMMENT '逻辑关联 borrowing_policy.id，仅表示规则来源，无数据库外键',
    policy_version BIGINT NOT NULL COMMENT '借阅时规则版本快照',
    max_active_loans_snapshot INT NOT NULL COMMENT '借阅时账号数量上限快照',
    loan_days_snapshot INT NOT NULL COMMENT '借阅时借期天数快照',
    borrowed_at DATETIME(6) NOT NULL COMMENT '借出时间，UTC',
    due_at DATETIME(6) NOT NULL COMMENT '应还时间，UTC，规则修改不追溯',
    returned_at DATETIME(6) COMMENT '实际归还时间，UTC；为空表示尚未归还',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近更新时间，UTC',
    CONSTRAINT ck_loan_terms CHECK (policy_version > 0 AND max_active_loans_snapshot > 0 AND loan_days_snapshot > 0 AND due_at > borrowed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='完整借阅记录：归还后保留历史';

CREATE TABLE current_loan (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '当前关系独立主键',
    loan_id BIGINT NOT NULL COMMENT '逻辑关联 loan.id，程序校验该借阅尚未归还且账号图书匹配，无数据库外键',
    account_id BIGINT NOT NULL COMMENT '逻辑关联 library_account.id，无数据库外键',
    book_id BIGINT NOT NULL COMMENT '逻辑关联 book.id，无数据库外键',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近更新时间，UTC',
    CONSTRAINT uk_current_account_book UNIQUE (account_id, book_id),
    CONSTRAINT uk_current_loan UNIQUE (loan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当前借阅关系：归还时删除，仅保存未归还关系';

CREATE INDEX ix_loan_account_history ON loan(account_id, borrowed_at, id);
CREATE INDEX ix_loan_book_history ON loan(book_id, borrowed_at, id);
CREATE INDEX ix_current_book ON current_loan(book_id, loan_id);
