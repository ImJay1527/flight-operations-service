package pt.isep.sidis.flightops.peers;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Flags incomplete answers (P1 p.12): if peers could not be reached while building a response, it still gets 200
 * with what the reachable instances had, plus {@code X-Partial-Result: true} and {@code X-Unreachable-Peers: n}.
 */
@RestControllerAdvice
public class PartialResults implements ResponseBodyAdvice<Object> {

    public static final String PARTIAL_HEADER = "X-Partial-Result";
    public static final String UNREACHABLE_HEADER = "X-Unreachable-Peers";
    private static final String ATTRIBUTE = PartialResults.class.getName() + ".unreachable";

    public static void record(int unreachable) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (unreachable <= 0 || request == null) {
            return;
        }
        Integer before = (Integer) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        // several peer queries in one request: report the worst one
        request.setAttribute(ATTRIBUTE, Math.max(before == null ? 0 : before, unreachable), RequestAttributes.SCOPE_REQUEST);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Integer unreachable = attributes == null ? null
                : (Integer) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (unreachable != null && unreachable > 0) {
            response.getHeaders().set(PARTIAL_HEADER, "true");
            response.getHeaders().set(UNREACHABLE_HEADER, String.valueOf(unreachable));
        }
        return body;
    }
}
