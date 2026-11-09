package com.example.library.infrastructure.error;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code) { this(code, code.message(), null); }
    public BusinessException(ErrorCode code, String message) { this(code, message, null); }
    public BusinessException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new BusinessException(ErrorCode.DATA_INCONSISTENCY,
                    "数据一致性检查失败：" + operation);
        }
    }
}
