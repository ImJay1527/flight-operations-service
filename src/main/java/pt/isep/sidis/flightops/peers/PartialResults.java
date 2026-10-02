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
 * Flags answers built without some peers (P1 p.12 "partial response scenarios"). The response is still 200 with what
 * the reachable instances had, plus {@code X-Unreachable-Peers: n}. {@code X-Partial-Result: true} is added only if
 * data may really be missing, i.e. if every copy of some flight could be on the unreachable peers.
 */
@RestControllerAdvice
public class PartialResults implements ResponseBodyAdvice<Object> {

    public static final String PARTIAL_HEADER = "X-Partial-Result";
    public static final String UNREACHABLE_HEADER = "X-Unreachable-Peers";
    private static final String ATTRIBUTE = PartialResults.class.getName() + ".unreachable";
    private static final String INCOMPLETE = PartialResults.class.getName() + ".incomplete";

    public static void record(int unreachable, boolean mayBeIncomplete) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (unreachable <= 0 || request == null) {
            return;
        }
        Integer before = (Integer) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        // several peer queries in one request: report the worst one
        request.setAttribute(ATTRIBUTE, Math.max(before == null ? 0 : before, unreachable), RequestAttributes.SCOPE_REQUEST);
        if (mayBeIncomplete) {
            request.setAttribute(INCOMPLETE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
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
            response.getHeaders().set(UNREACHABLE_HEADER, String.valueOf(unreachable));
            if (attributes.getAttribute(INCOMPLETE, RequestAttributes.SCOPE_REQUEST) != null) {
                response.getHeaders().set(PARTIAL_HEADER, "true");
            }
        }
        return body;
    }
}
