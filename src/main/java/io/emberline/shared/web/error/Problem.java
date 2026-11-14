package io.emberline.shared.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 9457 problem body with the EMBERLINE members (contract schema {@code Problem}).
 * {@code detail} never carries file paths, stack frames, class names, SQL or keys.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String instance, String code,
                      int errorNo, String param, String requestId, String methodVersion, List<FieldProblem> errors) {

    public static final String MEDIA_TYPE = "application/problem+json";

    /** One validation failure when several parameters are wrong. */
    public record FieldProblem(String param, String detail) {
    }
}
