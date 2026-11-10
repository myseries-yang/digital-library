package com.example.library.infrastructure.web;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> business(BusinessException ex) {
        if (ex.getCode().getStatus() >= 500) {
            log.error("operation_failed code={}", ex.getCode(), ex);
        }
        return response(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException ex) {
        ErrorCode code;
        if (ex instanceof TransientDataAccessException) {
            code = ErrorCode.TRANSACTION_RETRYABLE;
        } else if (ex instanceof DataAccessResourceFailureException) {
            code = ErrorCode.DATABASE_UNAVAILABLE;
        } else if (ex instanceof DataIntegrityViolationException) {
            code = ErrorCode.DATA_INCONSISTENCY;
        } else {
            code = ErrorCode.INTERNAL_ERROR;
        }
        log.error("database_failed code={}", code, ex);
        return response(code, code.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("unexpected_request_failure", ex);
        return response(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
                                                              HttpHeaders headers, HttpStatusCode status,
                                                              WebRequest request) {
        String detail = "请求格式或参数不正确，请检查字段类型与取值";
        if (ex instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException validation = (MethodArgumentNotValidException) ex;
            detail = validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        ErrorCode code = status.value() == 404 ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.INVALID_ARGUMENT;
        ProblemDetail problem = problem(status.value(), code.name(), status.value() == 404 ? "资源不存在" : detail);
        return ResponseEntity.status(status).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private ResponseEntity<ProblemDetail> response(ErrorCode code, String detail) {
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(code.getStatus(), code.name(), detail));
    }

    private ProblemDetail problem(int status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(HttpStatusCode.valueOf(status).is5xxServerError() ? "操作暂未完成" : "请求未完成");
        problem.setType(URI.create("urn:library:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        problem.setProperty("code", code);
        problem.setProperty("requestId", MDC.get("requestId"));
        return problem;
    }
}
