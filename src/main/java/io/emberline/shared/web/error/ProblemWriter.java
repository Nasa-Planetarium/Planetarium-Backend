package io.emberline.shared.web.error;

import io.emberline.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes a problem from filters (before or outside the DispatcherServlet), same shape as the advice. */
@Component
public class ProblemWriter {

    private final ProblemFactory factory;
    private final JsonMapper jsonMapper;

    public ProblemWriter(ProblemFactory factory, JsonMapper jsonMapper) {
        this.factory = factory;
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String param,
                      String detail) throws IOException {
        Problem problem = factory.build(request, code, param, detail, null);
        response.resetBuffer();
        response.setStatus(code.httpStatus());
        factory.headers(request, code).forEach((name, values) -> values.forEach(v -> response.setHeader(name, v)));
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
