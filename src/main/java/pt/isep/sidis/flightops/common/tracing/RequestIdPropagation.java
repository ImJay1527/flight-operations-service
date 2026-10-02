package pt.isep.sidis.flightops.common.tracing;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Adds the current request id to every outgoing call, so the called instance logs the same id. */
public final class RequestIdPropagation implements ClientHttpRequestInterceptor {

    public static final RequestIdPropagation INSTANCE = new RequestIdPropagation();

    private RequestIdPropagation() {
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestTracingFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            request.getHeaders().set(RequestTracingFilter.REQUEST_ID_HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
