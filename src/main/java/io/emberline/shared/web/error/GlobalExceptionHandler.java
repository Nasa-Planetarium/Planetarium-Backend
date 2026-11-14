package io.emberline.shared.web.error;

import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves as application/problem+json: EMBERLINE exceptions by their {@link ErrorCode}, MVC built-in
 * exceptions through a class → mapper registry (no switch), anything else as 500 internal with the request id only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** What a built-in exception means for the client. */
    record Mapped(ErrorCode code, String param, String detail, List<Problem.FieldProblem> errors) {
        static Mapped of(ErrorCode code, String detail) {
            return new Mapped(code, null, detail, List.of());
        }
    }

    private final Map<Class<? extends Exception>, Function<Exception, Mapped>> builtIns = new LinkedHashMap<>();
    private final Map<Integer, ErrorCode> byStatus = Map.of(
            400, ErrorCode.INVALID_PARAM, 404, ErrorCode.ROUTE_NOT_FOUND, 405, ErrorCode.METHOD_NOT_ALLOWED,
            406, ErrorCode.NOT_ACCEPTABLE, 413, ErrorCode.BODY_TOO_LARGE, 414, ErrorCode.QUERY_TOO_LONG,
            503, ErrorCode.OVERLOADED);
    private final ProblemFactory factory;

    public GlobalExceptionHandler(ProblemFactory factory) {
        this.factory = factory;
        builtIns.put(NoHandlerFoundException.class, ex -> Mapped.of(ErrorCode.ROUTE_NOT_FOUND, "no route for this path"));
        builtIns.put(NoResourceFoundException.class, ex -> Mapped.of(ErrorCode.ROUTE_NOT_FOUND, "no route for this path"));
        builtIns.put(HttpRequestMethodNotSupportedException.class, ex -> Mapped.of(ErrorCode.METHOD_NOT_ALLOWED,
                "method " + safe(((HttpRequestMethodNotSupportedException) ex).getMethod()) + " is not supported here; see the Allow header"));
        builtIns.put(HttpMediaTypeNotAcceptableException.class, ex -> Mapped.of(ErrorCode.NOT_ACCEPTABLE,
                "supported media types: application/json, application/geo+json, text/csv"));
        builtIns.put(HttpMediaTypeNotSupportedException.class, ex -> Mapped.of(ErrorCode.INVALID_PARAM,
                "request body must be application/json"));
        builtIns.put(MaxUploadSizeExceededException.class, ex -> Mapped.of(ErrorCode.BODY_TOO_LARGE,
                "request body is limited to 256 KB"));
        builtIns.put(AsyncRequestTimeoutException.class, ex -> Mapped.of(ErrorCode.OVERLOADED, "request took too long"));
        builtIns.put(MissingServletRequestParameterException.class, ex -> {
            String p = snake(((MissingServletRequestParameterException) ex).getParameterName());
            return new Mapped(ErrorCode.INVALID_PARAM, p, p + " is required", List.of());
        });
        builtIns.put(TypeMismatchException.class, ex -> {
            String p = snake(((TypeMismatchException) ex).getPropertyName());
            return new Mapped(ErrorCode.INVALID_PARAM, p, p + " has an invalid value", List.of());
        });
        builtIns.put(BindException.class, ex -> fromFieldErrors(((BindException) ex).getFieldErrors()));
        builtIns.put(HandlerMethodValidationException.class, ex -> fromMethodValidation((HandlerMethodValidationException) ex));
    }

    @ExceptionHandler(EmberlineException.class)
    public ResponseEntity<Object> handleEmberline(EmberlineException ex, HttpServletRequest request) {
        log.info("{} {} param={}", ex.errorCode().number(), ex.errorCode().code(), ex.param());
        return respond(request, ex.errorCode(), ex.param(), ex.detail(), List.of(), new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected error", ex);
        return respond(request, ErrorCode.INTERNAL, null, "internal error; quote the request_id when reporting it",
                List.of(), new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        Mapped mapped = mapBuiltIn(ex, statusCode);
        HttpServletRequest servletRequest = ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class);
        log.info("{} {} param={}", mapped.code().number(), mapped.code().code(), mapped.param());
        return respond(servletRequest, mapped.code(), mapped.param(), mapped.detail(), mapped.errors(), headers);
    }

    private Mapped mapBuiltIn(Exception ex, HttpStatusCode statusCode) {
        for (Class<?> type = ex.getClass(); type != null && Exception.class.isAssignableFrom(type); type = type.getSuperclass()) {
            Function<Exception, Mapped> mapper = builtIns.get(type);
            if (mapper != null) {
                return mapper.apply(ex);
            }
        }
        ErrorCode code = byStatus.getOrDefault(statusCode.value(), ErrorCode.INTERNAL);
        return Mapped.of(code, code == ErrorCode.INTERNAL ? "internal error; quote the request_id when reporting it"
                : "request cannot be served as sent");
    }

    private ResponseEntity<Object> respond(HttpServletRequest request, ErrorCode code, String param, String detail,
                                           List<Problem.FieldProblem> errors, HttpHeaders extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(extra);
        headers.remove(HttpHeaders.CONTENT_TYPE);
        factory.headers(request, code).forEach(headers::put);
        Problem problem = factory.build(request, code, param, detail, errors);
        return ResponseEntity.status(code.httpStatus()).headers(headers).body(problem);
    }

    private static Mapped fromFieldErrors(List<FieldError> fieldErrors) {
        List<Problem.FieldProblem> all = new ArrayList<>();
        for (FieldError fe : fieldErrors) {
            String p = snake(fe.getField());
            String what = fe.isBindingFailure() ? "has an invalid value" : safe(fe.getDefaultMessage());
            all.add(new Problem.FieldProblem(p, p + " " + what));
        }
        all.sort((a, b) -> a.param().compareTo(b.param()));
        Problem.FieldProblem first = all.isEmpty() ? new Problem.FieldProblem(null, "invalid request") : all.get(0);
        return new Mapped(ErrorCode.INVALID_PARAM, first.param(), first.detail(), all.size() > 1 ? all : List.of());
    }

    private static Mapped fromMethodValidation(HandlerMethodValidationException ex) {
        List<Problem.FieldProblem> all = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String p = snake(parameterName(result.getMethodParameter()));
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                all.add(new Problem.FieldProblem(p, p + " " + safe(error.getDefaultMessage())));
            }
        });
        all.sort((a, b) -> a.param().compareTo(b.param()));
        Problem.FieldProblem first = all.isEmpty() ? new Problem.FieldProblem(null, "invalid request") : all.get(0);
        return new Mapped(ErrorCode.INVALID_PARAM, first.param(), first.detail(), all.size() > 1 ? all : List.of());
    }

    private static String parameterName(MethodParameter parameter) {
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.value().isEmpty()) {
            return path.value();
        }
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null && !query.value().isEmpty()) {
            return query.value();
        }
        String name = parameter.getParameterName();
        return name == null ? "param" : name;
    }

    /** camelCase field or parameter name → the snake_case name the client sent. */
    static String snake(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** Messages reach the client only if they contain no class names, paths or line breaks. */
    private static String safe(String message) {
        if (message == null || message.matches("(?s).*([A-Za-z_$][\\w$]*\\.){2,}[A-Za-z_$].*|.*[/\\\\].*|.*[\\r\\n].*")) {
            return "is invalid";
        }
        return message;
    }
}
