package io.emberline.shared.web.release;

import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.PinnedRelease;
import io.emberline.shared.release.ReleaseRef;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Gives controllers the pinned release. A {@link ReleaseRef} parameter means "this route needs data": without an
 * active release it answers 503 data_not_loaded (Retry-After 60). {@link PinnedRelease} may be empty.
 */
public class ReleaseRefArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == ReleaseRef.class || type == PinnedRelease.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        ReleaseRef release = request == null ? null : ReleaseRefFilter.pinned(request).orElse(null);
        if (parameter.getParameterType() == PinnedRelease.class) {
            return new PinnedRelease(release);
        }
        if (release == null) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED,
                    "no release is active yet; retry after the release is activated");
        }
        return release;
    }
}
