package pt.isep.sidis.flightops.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the HTTP clients used for service-to-service calls. When the "tls" profile sets
 * {@code sidis.tls.client-bundle}, the clients trust only certificates signed by the AISafe CA.
 * The JDK HttpClient is used because it supports PATCH (HttpURLConnection does not).
 */
@Component
public class HttpClientFactory {

    private final SslBundle sslBundle;

    public HttpClientFactory(SslBundles sslBundles, @Value("${sidis.tls.client-bundle:}") String bundleName) {
        this.sslBundle = bundleName.isBlank() ? null : sslBundles.getBundle(bundleName);
    }

    public ClientHttpRequestFactory create(Duration timeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        if (sslBundle != null) {
            settings = settings.withSslBundle(sslBundle);
        }
        return ClientHttpRequestFactories.get(JdkClientHttpRequestFactory.class, settings);
    }
}
