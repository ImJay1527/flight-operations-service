package pt.isep.sidis.flightops.readmodel;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Shows which side answered a query (evidence of CQRS, used by the Postman tests):
 * <ul>
 *   <li>{@code X-Read-Side: read-model} - answered from this instance's read database; then
 *       {@code X-Read-Model-Updated} is the time of the last change applied to it (how fresh it is);</li>
 *   <li>{@code X-Read-Side: write-side} - answered as in Assignment 1 (from the instances holding the data): the
 *       read model was not usable - {@code X-Read-Model} says why (DISABLED, BUILDING, STALE: reason) - or the
 *       client asked for it by sending {@code X-Read-Side: write-side} (a read that must see every change).</li>
 * </ul>
 */
@RestControllerAdvice
public class ReadModelHeaders implements ResponseBodyAdvice<Object> {

    public static final String SIDE = "X-Read-Side";
    public static final String STATE = "X-Read-Model";
    public static final String UPDATED = "X-Read-Model-Updated";
    public static final String READ_MODEL = "read-model";
    public static final String WRITE_SIDE = "write-side";
    private static final String ATTRIBUTE = ReadModelHeaders.class.getName();

    private record Answer(boolean fromReadModel, ReadModelState state) {
    }

    /**
     * True if the client asked for the write side ({@code X-Read-Side: write-side} on the request): a read that must
     * see every change already made, at the cost of asking the instances that hold the data.
     */
    public static boolean writeSideRequested() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && WRITE_SIDE.equalsIgnoreCase(attributes.getRequest().getHeader(SIDE));
    }

    /** Called by the query side once it knows where the answer comes from. */
    public static void record(boolean fromReadModel, ReadModelState state) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(ATTRIBUTE, new Answer(fromReadModel, state), RequestAttributes.SCOPE_REQUEST);
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
        Answer answer = attributes == null ? null : (Answer) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (answer != null) {
            ReadModelState.Status status = answer.state().current();
            response.getHeaders().set(SIDE, answer.fromReadModel() ? READ_MODEL : WRITE_SIDE);
            response.getHeaders().set(STATE, status.reason() == null
                    ? status.state().name() : status.state() + ": " + status.reason());
            if (answer.fromReadModel() && answer.state().lastUpdate() != null) {
                response.getHeaders().set(UPDATED, answer.state().lastUpdate().toString());
            }
        }
        return body;
    }
}
