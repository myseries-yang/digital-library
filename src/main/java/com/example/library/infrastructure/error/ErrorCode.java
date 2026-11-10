package com.example.library.infrastructure.error;

/** Stable API error codes / 稳定业务错误码。 */
public enum ErrorCode {
    INVALID_ARGUMENT(400, "请求参数不正确"),
    ACCOUNT_NOT_FOUND(404, "图书馆账号不存在"),
    BOOK_NOT_FOUND(404, "图书不存在"),
    LOAN_NOT_FOUND(404, "借阅记录不存在"),
    RESOURCE_NOT_FOUND(404, "资源不存在"),
    LOAN_OWNERSHIP_MISMATCH(403, "不能操作其他账号的借阅"),
    BOOK_ALREADY_BORROWED(409, "当前账号已经借阅这本图书"),
    ACCOUNT_LIMIT_REACHED(409, "当前账号的借阅额度已用完"),
    BOOK_UNAVAILABLE(409, "该图书暂无可借名额"),
    POLICY_VERSION_CONFLICT(409, "规则已被修改，请刷新后重试"),
    LOCK_ACQUIRE_TIMEOUT(503, "操作繁忙，请稍后重试"),
    LOCK_SERVICE_UNAVAILABLE(503, "锁服务暂时不可用，请稍后重试"),
    TRANSACTION_RETRYABLE(503, "本次操作已回滚，请稍后重试"),
    DATABASE_UNAVAILABLE(503, "数据库暂时不可用"),
    COMMIT_OUTCOME_UNKNOWN(503, "提交结果尚未确认，请先刷新数据确认，不要直接重复提交"),
    DATA_INCONSISTENCY(500, "数据状态异常，本次操作未完成"),
    INTERNAL_ERROR(500, "服务暂时无法完成请求");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }
    public String getMessage() {
        return message;
    }
}
