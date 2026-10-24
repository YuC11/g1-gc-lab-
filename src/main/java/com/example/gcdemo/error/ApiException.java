package com.example.gcdemo.error;

import org.springframework.http.HttpStatus;

/**
 * 带 HTTP 状态和教学提示的受控业务异常。
 *
 * <p>由 {@code ApiExceptionHandler} 转换为 RFC 7807 {@code ProblemDetail}。
 * {@code hint} 面向学习者，给出下一步排查或参数调整建议，不暴露堆栈细节。</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String hint;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String hint) {
        super(message);
        this.status = status;
        this.hint = hint;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getHint() {
        return hint;
    }
}
