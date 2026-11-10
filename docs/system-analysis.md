# 电子图书馆服务：业务、模型与一致性设计

分析基线：2026-11-08。本文件描述业务设计与验收目标。实现细节见 technical-design.md，实际验证结果见 verification.md。

## 1. 目标与范围

系统以 LibraryAccount（图书馆账号）作为借阅主体，不识别自然人身份，也不关联同一自然人持有的多个账号。所有数字内容统一作为 Book（图书）处理。

| 项目 | 已确认的范围 |
| --- | --- |
| 读者能力 | 浏览、详情、借阅、归还、查看当前借阅；提供简易网页和 API |
| 管理能力 | 内置简易网页；设置全局规则，按账号和图书查询当前、历史借阅 |
| 借阅规则 | 同一账号、同一本书最多一笔未归还借阅；默认每账号最多十本；默认借期十五天 |
| 期限处理 | 十五天按 15×24 小时计算；到期不自动归还；逾期继续占用图书名额和账号额度 |
| 逾期影响 | 剩余额度允许时仍可借阅其他图书，不另设逾期禁借或罚金 |
| 规则修改 | 全局统一；账号额度上限和借期可修改；已有应还时间不变 |
| 在途请求 | 每次借阅读取一个完整规则快照；已读取旧规则的请求允许按旧规则完成 |
| 身份处理 | 预置账号并模拟当前账号；管理员入口重功能，不实现完整认证和权限体系 |
| Redis | 仅用于分布式锁，不实现数据缓存 |
| 默认演示模式 | JDK 17、Spring Boot、H2 文件数据库、进程内锁；无需外部数据库或 Redis，重启保留数据 |
| 完整模式 | MySQL、Redis、Redisson；通过 Docker Compose 启动依赖，验证真实组件行为 |
| 范围外 | 内容阅读与分发、账号及图书 CRUD、续借预约、管理员代还、完整权限系统 |

默认演示模式与完整模式共用业务规则、数据结构和事务协议。进程内锁只能说明单进程协调，不能作为分布式锁的验证证据。

## 2. 用例覆盖

~~~mermaid
flowchart LR
    R["Account Holder<br/>账号使用者"]
    M["Administrator<br/>管理员"]
    subgraph Library["Digital Library / 电子图书馆"]
        U1(["Browse Books<br/>浏览与关键词查询"])
        U2(["View Book<br/>查看图书详情"])
        U3(["Borrow Book<br/>借阅图书"])
        U4(["Return Loan<br/>归还指定借阅"])
        U5(["View Current Loans<br/>查看当前借阅"])
        U6(["Manage Policy<br/>查看与修改全局规则"])
        U7(["Query Loan History<br/>按账号或图书查询借阅"])
    end
    R --> U1
    R --> U2
    R --> U3
    R --> U4
    R --> U5
    M --> U6
    M --> U7
~~~

| 场景 | 前提与行为 | 结果与边界 |
| --- | --- | --- |
| 分页浏览 | 输入页码、页大小，可附书名或作者关键词 | 展示书目与读取时的可借名额；空列表正常返回 |
| 查询详情 | 图书 ID 存在 | 展示资料和授权名额；不存在时返回明确错误 |
| 正常借阅 | 账号和图书存在、无重复在借、额度和名额充足 | 创建一笔借阅和当前关系，保存规则快照 |
| 个人额度耗尽 | 当前数量已达到本次规则上限 | 拒绝借阅；不新增记录、不占用图书名额 |
| 图书名额耗尽 | 当前可借名额为零 | 拒绝借阅；账号额度保持原值 |
| 重复在借 | 同账号、同图书已有当前关系 | 拒绝新借阅；既有借阅保持不变 |
| 正常归还 | 借阅存在且属于当前账号 | 标记归还、删除当前关系、恢复两个数量 |
| 重复归还 | 同一 loanId 已经归还 | 返回成功，数量不再变化 |
| 归还后再借 | 上次借阅已归还，当前条件允许 | 创建新的 loanId，保留上次历史 |
| 旧归还请求重放 | 同书已经产生新的借阅 | 仅检查旧 loanId，新借阅不受影响 |
| 查看当前借阅 | 查询当前账号 | 包括逾期未归还；不包括已归还记录 |
| 逾期后继续借阅 | 存在逾期，仍有额度与图书名额 | 允许借阅；逾期记录继续占用原额度 |
| 管理查询 | 按账号、图书或状态过滤 | 展示当前或历史记录；仅查询不加 Redis 锁 |
| 修改规则 | 数值合法，版本号匹配 | 更新全局规则并递增版本；历史快照不变 |
| 并发修改规则 | 两次编辑使用同一旧版本 | 一个成功，另一个得到版本冲突 |
| Redis 获取锁失败 | Redis 不可用或等待超时 | 借还快速失败；查询继续使用数据库 |

页面展示的名额是查询时的状态，不构成预留承诺。实际借阅在写入阶段再次判定。

## 3. 四个业务模型与五张表

### 3.1 模型职责

| 英文模型 | 中文模型 | 信息与行为 | 对应持久化 |
| --- | --- | --- | --- |
| LibraryAccount | 图书馆账号 | 账号编号、名称、当前数量；判断额度、占用与释放额度 | library_account |
| Book | 图书信息 | 书目、总授权名额、可用名额；判断可借、占用与释放名额 | book |
| Loan | 借阅记录 | 账号、图书、借出及归还时间、规则快照；判断逾期、执行归还 | loan，并维护 current_loan |
| BorrowingPolicy | 借阅规则 | 全局数量上限、借期、版本；验证修改、提供借阅快照 | borrowing_policy |

AccountBorrowingView（账号借阅信息）和 BookBorrowingView（图书借阅信息）是同一份借阅明细的两个查询视角，不分别保存两套明细。

~~~mermaid
flowchart LR
    A["LibraryAccount<br/>图书馆账号"]
    B["Book<br/>图书信息"]
    L["Loan<br/>借阅记录"]
    P["BorrowingPolicy<br/>借阅规则"]
    A -->|"accountId：借阅主体"| L
    B -->|"bookId：借阅对象"| L
    P -.->|"借阅时保存规则快照"| L
~~~

模型表达业务规则，应用服务协调借还流程，仓储执行持久化条件写入。模型不依赖 Redis、数据库连接或 Spring 事务注解。当前不引入聚合根、领域事件总线或跨服务补偿流程。

### 3.2 表结构

每张表都有独立的 BIGINT 主键 id。另包含 created_at（创建时间）与 updated_at（更新时间），业务时间统一按 UTC 保存。

| 英文表名 | 中文表名 | 业务字段及含义 |
| --- | --- | --- |
| library_account | 图书馆账号表 | id（账号主键）；account_no（唯一账号编号）；account_name（展示名称）；active_loan_count（当前借阅数量，含逾期） |
| book | 图书信息表 | id（图书主键）；title（书名）；author（作者）；description（简介）；total_licenses（总授权名额）；available_licenses（可借名额） |
| loan | 借阅记录表 | id（借阅主键）；account_id（账号关联ID）；book_id（图书关联ID）；policy_id（规则来源关联ID）；policy_version（规则版本快照）；max_active_loans_snapshot（数量上限快照）；loan_days_snapshot（借期快照）；borrowed_at（借出时间）；due_at（应还时间）；returned_at（归还时间，可空） |
| current_loan | 当前借阅关系表 | id（关系主键）；loan_id（借阅关联ID）；account_id（账号关联ID）；book_id（图书关联ID） |
| borrowing_policy | 借阅规则表 | id（规则主键，全局固定为 1）；max_active_loans（默认十本）；loan_days（默认十五天）；version（规则版本） |

主键、逻辑关联与约束（不建立数据库外键）：

| 表 | 约束 |
| --- | --- |
| library_account | 主键 id；account_no 唯一；active_loan_count 不小于零 |
| book | 主键 id；total_licenses 不小于零；available_licenses 位于零与总名额之间 |
| borrowing_policy | 主键 id，全局仅维护 id=1；数量上限和借期均为正数 |
| loan | 主键 id；账号、图书、规则来源关联ID；增加 UNIQUE(id, account_id, book_id) 供组合外键引用 |
| current_loan | 主键 id；UNIQUE(account_id, book_id)；UNIQUE(loan_id)；账号、图书、借阅关联ID |
| current_loan 的关联校验 | INSERT SELECT 仅从真实、尚未归还且账号图书匹配的 loan 建立关系，要求影响一行；字段注释说明来源 |

policy_id 关联规则来源记录。policy_version 和其他快照字段记录历史取值，不引用当前规则版本，也不在查询时被当前配置覆盖。

~~~mermaid
flowchart LR
    A["library_account<br/>图书馆账号表<br/>PK：id"]
    B["book<br/>图书信息表<br/>PK：id"]
    P["borrowing_policy<br/>借阅规则表<br/>PK：id"]
    L["loan<br/>借阅记录表<br/>PK：id"]
    C["current_loan<br/>当前借阅关系表<br/>PK：id"]
    A -->|"1:N / account_id"| L
    B -->|"1:N / book_id"| L
    P -->|"1:N / policy_id"| L
    L -->|"1:0..1 / loan_id"| C
    A -->|"1:N / account_id"| C
    B -->|"1:N / book_id"| C
~~~

loan 保留完整历史；current_loan 仅保存未归还关系。后者提供当前关系唯一约束，不是缓存或异步副本。对外归还使用 loan.id，不使用 current_loan.id。

查询索引：loan(account_id, borrowed_at, id)、loan(book_id, borrowed_at, id)、current_loan(book_id, loan_id)。current_loan 的账号与图书唯一索引同时支持账号当前借阅查询。

### 3.3 必须维护的数据不变量

| 不变量 | 维护方式 |
| --- | --- |
| 账号当前数量 = 该账号 current_loan 行数 | 借还写入协议在一个事务内同步变更 |
| 图书总名额 − 可借名额 = 该书 current_loan 行数 | 同上 |
| loan 未归还，当且仅当存在对应 current_loan | 同上；借阅插入、归还删除 |
| 同账号、同图书最多一条当前关系 | 数据库唯一约束 |
| 一个 loanId 最多对应一条当前关系 | 数据库唯一约束 |
| 同书归还后可以再次借阅 | 唯一约束放在当前关系表，历史表保留多次记录 |
| 当前可借名额不小于零且不超过总名额 | 条件更新与 CHECK 约束 |
| 每次成功借阅不突破所采用规则的上限 | 账号数量条件更新 |
| 一笔借阅只释放一次额度 | loan 的未归还到已归还条件更新 |

跨表等式由统一的事务写入协议维护，唯一与 CHECK 约束不能独立证明这些等式。所有写入口必须遵循同一协议，并通过集成测试中的校验查询验证。

账号数量不能固定设置为 CHECK <= 10，也不能要求它始终不超过当前全局上限；降低上限后，保留已有借阅是合法状态。

## 4. 借阅状态流转

~~~mermaid
stateDiagram-v2
    state "未归还且未逾期" as Active
    state "未归还且已逾期" as Overdue
    state "已归还" as Returned
    [*] --> Active: 借阅成功
    Active --> Overdue: 到达应还时间仍未归还
    Active --> Returned: 归还
    Overdue --> Returned: 归还
    Returned --> Returned: 重复归还，结果成功且数量不变
    Returned --> [*]
~~~

这三种状态是业务视图，不新增数据库状态列：

- returned_at 为空：尚未归还。
- returned_at 为空且 now >= due_at：逾期。
- returned_at 不为空：已经归还。

时间到期不产生归还写入，不删除 current_loan，不释放任何数量，因此无需为逾期判断增加定时归还任务。归还后再借会创建新的 loanId，不把旧记录改回未归还。

## 5. Redis 并发协调

### 5.1 锁粒度

完整模式使用两把独立锁：

~~~text
digital-library:lock:account:{accountId}
digital-library:lock:book:{bookId}
~~~

| 竞争场景 | 共享资源 | 对应 Redis 锁 | 数据库最终保护 |
| --- | --- | --- | --- |
| 同账号并发借不同图书 | 账号总额度 | account 锁 | 账号数量条件更新 |
| 不同账号并发借同一本图书 | 图书授权名额 | book 锁 | 图书可借名额条件更新 |
| 同账号并发借同一本图书 | 账号额度、图书名额、当前关系 | 两把锁 | 数量条件更新与当前关系唯一约束 |
| 同账号同时借阅和归还 | 账号数量 | 两个用例均使用 account 锁 | 同一账号行的原子更新 |
| 不同账号对同书进行借还 | 图书名额 | 两个用例均使用 book 锁 | 同一图书行的原子更新 |
| 同一 loanId 重复归还 | 借阅状态及两个数量 | 两把锁 | 借阅状态条件转换，只允许一个事务释放 |
| 不同账号操作不同图书 | 无共享账号或图书 | 不同锁，可以并发 | 各自短事务 |
| 浏览、详情、借阅查询 | 只读 | 不加 Redis 锁 | 普通数据库查询 |
| 修改全局规则 | 同一条配置记录 | 不加 Redis 锁 | 版本号条件更新 |

将 accountId 与 bookId 拼成一个组合锁只能协调同一组合，无法覆盖同账号不同书、同书不同账号的竞争，因此本方案使用两个独立维度。

获取顺序固定为 account → book，释放顺序为 book → account。只获得账号锁、未获得图书锁时，尝试释放已获锁并结束请求。锁等待必须有界，不能无限等待。

未来增加管理员代还时，仍按目标借阅账号与图书加锁，不按管理员身份加锁；本次不提供该功能。

### 5.2 Redis 与数据库的责任

| 机制 | 承担的责任 | 单独使用的局限 |
| --- | --- | --- |
| Redis 分布式锁 | 正常运行时协调相同资源的业务请求 | 租约、进程暂停或故障切换可能导致重叠执行 |
| 数据库事务 | 多张表整体提交或回滚 | 普通查询后插入本身不能保证额度不被并发突破 |
| 数据库条件更新 | 对数量边界做原子判断与变更 | 不能单独保证整笔借还的跨表一致性 |
| 唯一约束与程序关联校验 | 当前关系唯一、逻辑关联有效且匹配 | 仍须在同一事务中维护计数 |
| 借阅状态条件更新 | 同一笔借阅只产生一次归还效果 | 后续释放步骤仍必须与它处于同一事务 |

Redis 锁的有效性有租约边界。看门狗续期能降低过期概率，但不能成为数据库安全性的前提。[Redis 官方说明](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)

推荐 Redisson RLock，有界等待并采用 watchdog 模式；不要在使用固定 leaseTime 的同时假定 watchdog 仍会续期。只释放本请求实际获得的锁。[Redisson 配置说明](https://redisson.pro/docs/configuration/)

书籍锁使同一本书的借还串行，即使名额充足；账号锁也使同账号操作串行。这是本次选择的清晰协调方式，存在额外 Redis 往返和热点排队成本，不能未经压测宣称吞吐量更高。

## 6. 借阅事务与业务流程

### 6.1 锁必须覆盖实际提交

~~~mermaid
sequenceDiagram
    participant C as Client / 账号使用者
    participant A as Application / 应用服务
    participant R as Redis / 分布式锁
    participant D as Database / 数据库
    C->>A: 借阅 bookId
    A->>R: 获取 account 锁
    R-->>A: 获取成功
    A->>R: 获取 book 锁
    R-->>A: 获取成功
    A->>D: BEGIN
    A->>D: 读取完整规则快照
    A->>D: 账号额度条件占用
    A->>D: 图书名额条件占用
    A->>D: 插入 loan 与 current_loan
    A->>D: COMMIT
    D-->>A: 提交完成
    A->>R: 释放 book 锁
    A->>R: 释放 account 锁
    A-->>C: 借阅成功及 loanId
~~~

该图展示成功路径。锁获取失败不进入修改数据的事务；事务内步骤失败则整体回滚，回滚完成后释放锁。

外层应用入口负责锁且不被外部数据库事务包裹；内部通过 CommandTransactions 调用 Spring PlatformTransactionManager，显式开启、提交或回滚事务。不能加入一个尚未提交的外层事务，然后因内部方法返回而提前解锁。[Spring 编程式事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)

### 6.2 事务步骤

| 顺序 | 步骤 | 失败处理 |
| --- | --- | --- |
| 1 | 验证参数、账号和图书存在 | 明确错误，无数据变更 |
| 2 | 获取账号与图书锁 | 超时或 Redis 不可用，快速失败 |
| 3 | 开启事务，读取一行完整全局规则 | 读取失败则回滚 |
| 4 | 检查是否已有当前关系 | 已在借则拒绝；唯一约束仍做最终保护 |
| 5 | 原子增加账号当前数量 | 影响零行表示未取得额度，回滚 |
| 6 | 原子减少图书可借名额 | 影响零行表示未取得名额，回滚账号数量变化 |
| 7 | 插入 loan，保存借出、应还与规则快照 | 失败则回滚全部变更 |
| 8 | 插入 current_loan | 唯一冲突或程序关联校验失败则回滚全部变更 |
| 9 | 提交完成后释放锁 | 已提交结果不因解锁失败而变成已回滚 |

SQL 仅用于说明设计语义，不是已经执行的迁移或实现：

~~~sql
UPDATE library_account
SET active_loan_count = active_loan_count + 1,
    updated_at = :now
WHERE id = :accountId
  AND active_loan_count < :maxActiveLoansSnapshot;

UPDATE book
SET available_licenses = available_licenses - 1,
    updated_at = :now
WHERE id = :bookId
  AND available_licenses > 0;
~~~

每条更新都必须影响一行。数据库在执行条件更新时协调同一记录上的并发写入；不能用 Java 中读到的旧计数计算新值后直接覆盖。[MySQL 写入锁说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)

建议命令事务使用 READ COMMITTED，关键判断依靠条件更新与约束。隔离级别本身不代替额度协议，也不需要将所有查询改成 SERIALIZABLE。

不能在事务内部吞掉唯一约束异常后正常返回，否则前面已经修改的数量可能被提交。业务拒绝和数据异常都必须导致当前修改事务回滚，再在外层转换成响应。

## 7. 归还事务与幂等

~~~mermaid
flowchart TD
    A["输入 loanId<br/>读取借阅的账号与图书标识"] --> B{"记录存在且属于当前账号？"}
    B -->|"否"| X["返回不存在或归属错误"]
    B -->|"是"| C["获取账号锁，再获取图书锁"]
    C --> D{"锁获取成功？"}
    D -->|"否"| Y["尝试释放已获锁<br/>快速失败，数据库不变"]
    D -->|"是"| E["开启数据库事务"]
    E --> F["条件更新 loan：<br/>仅未归还记录可写入 returned_at"]
    F --> G{"影响一行？"}
    G -->|"否：已归还"| H["不修改数量和当前关系"]
    G -->|"是：本事务取得归还处理权"| I["账号当前数量减一"]
    I --> J["图书可借名额加一"]
    J --> K["删除该 loanId 的 current_loan"]
    H --> M["提交完成"]
    K --> M
    I -.->|"失败"| R["整体回滚<br/>包括 returned_at 的更新"]
    J -.->|"失败"| R
    K -.->|"失败"| R
    M --> N["释放图书锁，再释放账号锁"]
    N --> O["归还成功"]
    R --> S["释放已获锁并返回失败"]
~~~

账号与图书标识从借阅记录读取，不由调用方任意指定；它们在借阅建立后不可修改。记录不允许通过本次接口删除。

~~~sql
UPDATE loan
SET returned_at = :now,
    updated_at = :now
WHERE id = :loanId
  AND account_id = :accountId
  AND returned_at IS NULL;
~~~

已确认记录存在且归属正确时：

- 影响一行：当前事务取得归还效果的处理权，继续释放数量。
- 影响零行：已归还，返回成功，不再释放任何数量。

释放数量也使用条件更新：

~~~sql
UPDATE library_account
SET active_loan_count = active_loan_count - 1,
    updated_at = :now
WHERE id = :accountId
  AND active_loan_count > 0;

UPDATE book
SET available_licenses = available_licenses + 1,
    updated_at = :now
WHERE id = :bookId
  AND available_licenses < total_licenses;

DELETE FROM current_loan
WHERE loan_id = :loanId
  AND account_id = :accountId
  AND book_id = :bookId;
~~~

取得归还处理权后，这三步都必须影响一行。否则是数据一致性异常，应整体回滚，不能当作重复归还成功。

共享数量行的更新顺序保持账号 → 图书，当前关系最后删除。不要先删除当前关系，再等待账号数量行，增加与并发借阅的交叉等待。

数据库仍可能因记录与唯一索引竞争等发生死锁。本次确认回滚后返回暂时不可用，不做应用自动重试；未来若增加有界重试，必须重试整笔事务，不能只重试一条 SQL。[MySQL 死锁说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks.html)

## 8. 规则变更场景

| 场景 | 结果 |
| --- | --- |
| 借期从十五天改为七天 | 已有 due_at 不变；新借阅按七天计算 |
| 上限从十本改为五本，账号已借八本 | 保留八本；归还到四本后才能再借一本 |
| 账号已借四本，新上限为五本 | 可以再借一本 |
| 旧请求已读取十本规则，随后上限降低 | 旧请求可按自身快照完成 |
| 管理员保存完成后新发起借阅 | 读取并使用新规则 |
| 两个管理员从同一版本修改 | 版本条件更新只允许一个成功，另一方重新读取 |

~~~mermaid
sequenceDiagram
    participant B as Borrow / 在途借阅
    participant D as Policy / 数据库规则
    participant M as Admin / 管理员
    B->>D: 读取版本 7，借期 15 天
    D-->>B: 完整规则快照
    M->>D: 版本匹配则更新为版本 8，借期 7 天
    D-->>M: 保存成功
    B->>D: 按版本 7 创建借阅，保存旧快照
    Note over B,D: 旧请求允许完成，已有 due_at 不变
    B->>D: 新请求读取规则
    D-->>B: 版本 8，借期 7 天
~~~

规则更新采用 WHERE id=1 AND version=:expectedVersion 的条件写入，并同时递增版本。无需为每个借阅请求增加全局 Redis 规则锁。

## 9. 并发与故障验收矩阵

| 场景 | 预期结果 | 必须检查的数据 |
| --- | --- | --- |
| 某书仅剩一个名额，多账号竞争 | 最多一个借阅成功 | 名额不为负，成功记录与当前关系对应 |
| 同账号已有九本，同时借两本不同书 | 最多新增一本 | 账号数量最多达到十，失败请求未占用图书名额 |
| 同账号、同图书并发借阅 | 最多一条当前关系 | 唯一约束成立，失败事务全部回滚 |
| 不同账号、不同图书并发 | 可并行 | 不存在全局业务锁 |
| 重复或并发归还同一 loanId | 都可得到成功，归还效果一次 | 账号减一、图书加一、当前关系删除一次 |
| 借还同账号并发 | 符合某一种合法执行顺序 | 两个数量不丢失，关系与历史一致 |
| 旧 loanId 归还请求重放 | 新借阅保持有效 | 新 loanId、current_loan 与数量均不受影响 |
| 到达应还时刻 | 显示逾期但数量不变 | 原 current_loan 保留，returned_at 仍为空 |
| 账号增加后、图书扣减失败 | 整体回滚 | 账号数量恢复，没有新增借阅 |
| 两个数量变化后、关系插入失败 | 整体回滚 | 数量、loan、current_loan 均无半成品 |
| 归还状态已改、后续释放失败 | 整体回滚 | returned_at 恢复为空，原关系与数量保留 |
| 获取 Redis 锁前不可用 | 借还快速失败，查询可用 | 没有进入修改事务 |
| 获取第二把锁失败 | 请求结束，尝试释放第一把锁 | 没有业务数据变更 |
| 持锁中途租约失效，另一个请求进入 | 数据库保护仍维持业务约束 | 条件更新、唯一约束、归还状态转换均有效 |
| COMMIT 已确认成功，解锁失败 | 保留已经成功的业务结果，记录解锁异常 | 不做反向补偿或谎称回滚 |
| 提交时连接断开，结果未知 | 明确结果未确认，查询后判定 | 不能假定已经回滚，也不盲目重复借阅 |
| 规则更新与借阅同时发生 | 按完整快照处理 | 不混用两个版本的额度和借期 |
| 默认模式重启 | 数据与规则保留 | H2 文件数据不重置 |

借阅重复提交采用“当前关系是否已存在”的业务规则，不承诺任意时间跨度内的请求级 exactly-once。归还则按具体 loanId 幂等；提交结果未知时，可以查询当前借阅或原借阅记录确认。

Redis 完整模式获取锁失败时，不自动降级到进程内锁。已经进入事务后若锁失效，依靠数据库写入协议维持安全；这与获取失败时拒绝新写入并不矛盾。

## 10. 应用组织与错误处理

~~~mermaid
flowchart TD
    W["Reader UI / Admin UI / API<br/>读者、管理网页与接口"]
    A["Application Services<br/>借阅、归还、规则设置"]
    Q["Query Service<br/>书籍与借阅查询"]
    M["Business Models<br/>账号、图书、借阅、规则"]
    L["Lock Coordinator<br/>锁协调接口"]
    R["Redis Locks / Local Locks<br/>完整模式与演示模式"]
    P["Repositories + Transaction Runner<br/>仓储与事务执行"]
    D["MySQL / H2<br/>关系数据库"]
    W --> A
    W --> Q
    A --> M
    A --> L
    L --> R
    A --> P
    P --> D
    Q --> D
~~~

| 层 | 职责 |
| --- | --- |
| 接入层 | 参数解析、当前账号上下文、返回一致错误结构；不编写数量扣减规则 |
| 应用服务 | 组织锁、事务、模型和仓储；决定流程顺序 |
| 业务模型 | 表达可借条件、借期计算、归还和逾期语义 |
| 持久化 | 实现条件 SQL、影响行数校验、唯一约束及程序逻辑关联校验 |
| 查询服务 | 通过关联查询返回账号或图书借阅视图；无需加载完整历史对象集合 |

| 错误类别 | 例子 | 建议响应语义 |
| --- | --- | --- |
| 参数错误 | 非法 ID、分页参数、非正规则数值 | 请求无效，说明具体字段 |
| 不存在 | 账号、图书、借阅记录不存在 | 资源不存在 |
| 归属错误 | 当前账号归还其他账号借阅 | 拒绝操作 |
| 业务冲突 | 已在借、额度不足、名额不足 | 明确业务原因，不自动重试 |
| 版本冲突 | 管理员提交旧版本 | 重新读取后再修改 |
| 暂时不可用 | Redis 无法获取锁、等待超时 | 稍后重试，业务数据不变 |
| 数据一致性异常 | 归还释放数量或删除关系未达到预期 | 整体回滚并记录诊断信息 |
| 提交结果未知 | 提交期间连接断开 | 明确未确认，提供查询确认路径 |

查询不加 Redis 锁。若一个查询页面需要汇总与明细来自同一快照，应使用单条查询或一致读快照；不会通过持有写锁来保证用户浏览期间名额不变。

## 11. 本次工程重点与验证证据

| 重点 | 需要展示的工程判断 |
| --- | --- |
| 边界清楚 | 按图书馆账号计算额度，不引入自然人和完整权限模型 |
| 模型简单 | 四个业务模型；账号与图书借阅信息是同一明细的两个视角 |
| 数据约束 | 当前关系唯一、历史可重复、所有表独立主键、逻辑关联、字段注释和程序维护协议明确 |
| 并发正确 | 两个资源维度的锁；数据库条件写入仍保护最终数据 |
| 事务完整 | 所有相关表整体提交；锁覆盖实际提交与回滚边界 |
| 生命周期正确 | 逾期不归还、旧归还请求不误伤新借阅、规则快照不被追溯修改 |
| 错误可理解 | 区分业务冲突、暂时失败、异常和结果未知 |
| 易于评审 | 默认模式直接运行；完整模式独立证明真实 MySQL 与 Redis 行为 |
| 可维护 | 规则在业务模型、流程在应用层、SQL 在持久化；避免多入口各写一套借还逻辑 |

后续验证分为：

1. 业务模型测试：借期计算、逾期边界、额度判断、规则验证；使用可控时钟。
2. 数据库集成测试：唯一约束与程序关联校验、每个失败阶段的回滚、数量与当前关系的校验等式。
3. 并发测试：使用同步起跑屏障制造竞争，不能用顺序请求代替；检查成功数与最终数据。
4. 完整模式测试：至少两个应用实例连接同一个 MySQL、Redis；检查 Redis 故障与锁失效时的数据安全。
5. 演示验收：预置图书及账号，包含可借、无名额、临近额度、逾期与已归还样例；检查网页与重启保留数据。

H2 的兼容模式仅覆盖部分数据库差异，因此 H2 通过不能证明 MySQL 的并发、锁和 SQL 行为已通过。[H2 兼容性说明](https://h2database.com/html/features.html)

图示为设计表达。代码、SQL、页面与并发的实际验证范围分别记录在 verification.md，不将图语法检查等同于运行验证。

README 提供启动方式、演示账号、业务假设和取舍，verification.md 记录实际执行的环境、结果及未验证项。
