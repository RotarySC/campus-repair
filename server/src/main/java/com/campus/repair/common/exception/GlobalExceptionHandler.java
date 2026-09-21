package com.campus.repair.common.exception;

import com.campus.repair.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验不通过";
        log.warn("参数校验异常: {}", message);
        return R.error(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定异常";
        return R.error(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限拒绝: {}", e.getMessage());
        return R.error(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleBadCredentials(BadCredentialsException e) {
        return R.error(ErrorCode.PASSWORD_ERROR.getCode(), e.getMessage());
    }

    /**
     * 未匹配到任何处理器/静态资源时（Spring Boot 3.2 的 ResourceHttpRequestHandler 会抛出
     * NoResourceFoundException），应返回 404 而不是落入通用兜底变成 500。
     */
    /**
     * 请求体不是 JSON（例如 `application/x-www-form-urlencoded`）时 Spring 抛本异常。
     *
     * <p>此前它落进下面的 `Exception` 兜底变成 **HTTP 500「系统处理异常」**——客户端发错 Content-Type
     * 却拿到「服务端崩了」的结论，既误导排查又污染 ERROR 日志（本轮用 `Invoke-WebRequest -Method POST`
     * 发无 body 的登出请求时实测踩到）。这里按 HTTP 语义正确回 415。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public R<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求 Content-Type 不受支持: {}", e.getContentType());
        return R.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(), ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage());
    }

    /**
     * 请求体是 JSON 但无法反序列化（语法错误、字段类型不匹配）→ 400 而不是 500。
     * 同样是为了不把「客户端传错」报成「服务端异常」。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return R.error(ErrorCode.PARAM_INVALID.getCode(), "请求体格式错误，无法解析为 JSON");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("请求资源不存在: {}", e.getResourcePath());
        return R.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("系统全局未捕获异常: ", e);
        return R.error(ErrorCode.INTERNAL_ERROR.getCode(), "系统处理异常，请稍后重试");
    }
}
