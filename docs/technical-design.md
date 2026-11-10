# 电子图书馆技术方案

## 1. 目标与实现选型

围绕账号独立借阅、有限授权、规则快照和原子归还实现模块化单体。覆盖五项核心能力及简易管理查询和规则设置，不建自然人、认证和权限模型。

| 技术 | 实现 |
| --- | --- |
| 运行框架 | JDK 17、Spring Boot 4.1.2、Spring MVC |
| 构建 | Maven Wrapper 3.9.14，单模块 |
| 数据访问 | MyBatis Spring Boot Starter 4.0.0、Mapper XML、HikariCP |
| 数据库 | 默认 H2 文件；完整模式 MySQL 8.4 |
| 迁移 | Flyway，分别维护 H2、MySQL 脚本 |
| 并发协调 | 演示模式 ReentrantLock；完整模式 Redisson 4.8.0 RLock |
| 页面 | Thymeleaf、原生 JavaScript、CSS，无前端构建 |
| 合约 | springdoc-openapi 3.2.0、运行时 OpenAPI、API 请求示例 |
| 验证 | Spring Boot Test、JUnit、AssertJ、Testcontainers |

Redis 仅用于锁，不做数据缓存。基础库由成熟开源组件承担，项目只组织业务顺序。其他依赖优先使用 Spring Boot BOM 管理。MyBatis 使用适配 Spring Boot 4 / Java 17 的 [4.0 系列 Starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

编译与运行保持 JDK 17；业务源码按 Java 8 常见语法组织，使用普通类、getter/setter、明确的局部变量类型和传统分支。新增业务逻辑使用有名称的方法，按执行顺序展开。分页容器、集合及框架约定保留必要泛型。

## 2. 模型与表

| 英文模型 | 中文模型 | 持久化与职责 |
| --- | --- | --- |
| LibraryAccount | 图书馆账号 | library_account：账号资料及当前数量 |
| Book | 图书信息 | book：书目、总名额、可借名额 |
| Loan | 借阅记录 | loan：借出、应还、归还及规则快照 |
| BorrowingPolicy | 借阅规则 | borrowing_policy：全局额度、借期、版本 |
| 当前关系辅助记录 | 当前借阅关系 | current_loan：账号图书组合及 loan_id 唯一 |

五张业务表均有独立 BIGINT 主键和创建、更新时间。除固定规则 id=1 外由数据库生成。字段及中文注释见迁移脚本，详细含义见 system-analysis.md。

按最新约定不建立数据库外键。事务保证原子提交，关联有效性由程序显式校验：

- 借阅事务读取账号、图书和完整规则；数量条件更新同时确认资源行存在。
- current_loan 通过 INSERT SELECT，从真实、未归还且账号图书匹配的 loan 建立，要求影响一行。
- 归还从不可变借阅记录获取关联标识；数量释放和当前关系删除都必须影响一行。
- 本次没有账号、图书、规则删除入口。未来加入删除需扩展统一关联维护协议。

保留当前关系唯一、账号数量非负、授权数量范围及规则正值等数据库约束。Flyway 内部元数据表不属于业务模型。

~~~mermaid
flowchart LR
    A["library_account / 图书馆账号"]
    B["book / 图书信息"]
    P["borrowing_policy / 全局规则"]
    L["loan / 借阅记录"]
    C["current_loan / 当前关系"]
    A -->|"逻辑1:N，account_id"| L
    B -->|"逻辑1:N，book_id"| L
    P -->|"来源1:N，policy_id"| L
    L -->|"逻辑1:0..1，loan_id"| C
    A -->|"逻辑1:N，account_id"| C
    B -->|"逻辑1:N，book_id"| C
~~~

账号借阅信息和图书借阅信息是同一份明细的查询视角，current_loan 不重复保存时间或规则明细。

## 3. 应用与接口

基础包 com.example.library，按 account、book、loan、policy 分功能，infrastructure 提供锁、事务、配置与错误处理。模型不依赖 Spring/Redis，应用服务组织业务协议，具体 Repository 负责资源检查、搜索参数和数据访问结果处理，MyBatis Mapper 执行 XML SQL，LoanQueries 组织查询视图。

### 代码阅读顺序与数据访问约定

| 入口 | 阅读内容 |
| --- | --- |
| LoanController → LoanService | 请求参数、身份与借还流程 |
| LoanService.borrowInTransaction / returnInTransaction | 一次借阅或归还的完整业务步骤 |
| 各业务 persistence 包 | 具体 Repository 与 MyBatis Mapper 接口 |
| resources/mapper 下各模块 XML | 查询、条件更新、主键回填及动态联表 SQL |
| CommandTransactions / LockCoordinator | 真实事务边界与账号、图书锁顺序 |

Mapper 接口由 MyBatis 生成代理，声明具体业务方法；多参数使用 `@Param` 命名，XML 使用 `#{...}` 绑定。SQL 不在 Java 中拼接。新增查询时，在现有模块的 Mapper 与对应 XML 中添加同名方法和语句即可。复杂筛选使用 `<where>`、`<if>`、`<choose>`，相同的列或联表片段使用 `<sql>` 与 `<include>` 复用。

LoanMapper.insert 使用 `useGeneratedKeys` 将主键回填到 Loan.id；当前关系仍通过 INSERT SELECT 校验借阅归属。账号额度、图书名额和归还标记继续使用带条件的原子 SQL，并检查影响行数。LoanViewMapper 负责联表，LoanQueries 使用同一次 Clock 取值计算逾期，保证筛选与显示的边界一致。

MyBatis 关闭二级缓存，一级缓存仅保留单条语句范围，事务中重复读取直接查询数据库。UtcInstantTypeHandler 将 Instant 与 UTC LocalDateTime 相互转换，统一处理微秒时间和空归还时间。

### API 行为

接口清单见 README 和 OpenAPI。借还及当前借阅使用 X-Library-Account-Id。缺失/非法参数400，不存在404，归属错误403，业务/版本冲突409，暂时不可用503，一致性异常500。

分页为 items/page/size/hasNext，默认20、最多100；page从零开始，多取一条判断后续页。图书按ID升序、借阅按ID降序。CURRENT 包含逾期，OVERDUE 仅含逾期未还。

ProblemDetail 返回 code、requestId 与标准问题字段，请求编号与日志及响应头对应。COMMIT_OUTCOME_UNKNOWN 单独表达提交未确认，不自动重放借阅。

时间使用 Instant 和 UTC，MySQL DATETIME(6)、H2 TIMESTAMP(6) 经 UTC LocalDateTime 映射，不依赖 JVM 时区。接口 ISO 8601，网页北京时间，借期按24小时一天。

读者页面切换账号、搜索、详情、借还、查看当前借阅。管理页面设置规则与筛选记录；不实现代还或真实权限。写入成功后刷新失败，不把已经成功的操作描述成失败。

## 4. 锁与事务

~~~text
digital-library:lock:account:{accountId}
digital-library:lock:book:{bookId}
~~~

按账号、图书顺序获取，反向释放；默认等待1秒、事务5秒、watchdog30秒。Redisson 不传固定租约，采用懒连接，网络连接和命令均设置超时。完整模式获取锁失败不切换本地锁。

本地锁只对已验证的预置资源建立注册表；本次无CRUD，资源数量有界，不清除可能仍被使用的锁。

~~~mermaid
sequenceDiagram
    participant A as 应用服务
    participant L as 锁协调器
    participant D as 数据库
    A->>L: 账号锁，再图书锁
    A->>D: CommandTransactions.begin 开始
    A->>D: 条件变更数量与借阅关系
    alt 全部成功
        A->>D: 提交
        D-->>A: 提交完成
    else 拒绝或失败
        A->>D: 回滚
        D-->>A: 回滚完成
    end
    A->>L: 释放图书锁，再账号锁
~~~

外层用例不得被未提交事务包裹；CommandTransactions 显式检查并拥有真实提交边界。写事务 READ COMMITTED，不自动重试。LoanService 使用 try-with-resources 持有 LockHandle；内部显式 begin，业务步骤失败则 rollback，成功则 commit，最后才关闭 LockHandle。提交阶段错误在业务回滚分支之外处理，避免把提交结果未知误报成已回滚。

借阅：一次完整规则快照 → 检查重复 → 账号条件加一 → 图书条件减一 → 插入 loan → 校验并插入 current_loan → 提交。任何失败整体回滚。

归还：存在与归属校验 → 锁 → 条件写入 returned_at。零行时按已归还成功处理；一行时账号减一、图书加一，最后删除当前关系。后续失败连同 returned_at 一起回滚。

锁失效后的重叠执行仍由数量条件更新、当前唯一关系和归还状态转换保护。确认提交后的解锁异常只记录，不覆盖成功结果。提交阶段失败按结果未知处理，业务执行阶段异常回滚后转换响应。

## 5. 规则、运行与验证

逾期通过 due_at 与 returned_at 派生，不写状态、不触发自动归还。重借生成新 loanId，旧请求不误还新借阅。

规则修改使用期望版本条件更新；历史应还日期不变，旧快照在途请求允许完成，新请求使用新版本。降低上限不强制归还。

demo 使用 H2 文件与本地锁；full 使用 MySQL8.4/Redis8.4；full-it 通过 Testcontainers 或明确指定的独立测试实例运行两个 HTTP 应用。

Flyway 只在首次执行种子，包含四账号十五书；H2 重置手工种子后的账号及图书主键序列。重启不覆盖业务变更。

数据库与锁健康分开；readiness 仅依赖应用和数据库，Redis 故障保留查询。初始 Redis 不可用也可启动，首次锁操作失败才拒绝对应写入。

生产和测试都通过 MyBatis 操作数据库。测试专用 DatabaseTestMapper 与 XML 负责数据准备、独立一致性查询、元数据检查和行锁故障注入。验证包含模型、迁移与重启、每阶段回滚、并发、租约失效、页面及 API 合约，并覆盖 XML 组合筛选、分页、字面关键词、UTC 映射和真实事务完成后解锁。实际环境、结果和未验证项见 verification.md，不将 H2 或其他 MySQL 版本等同于目标 MySQL8.4验证。
