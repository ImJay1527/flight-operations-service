package pt.isep.sidis.flightops.clients;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the HTTP clients used for service-to-service calls. When the "tls" profile sets
 * {@code sidis.tls.client-bundle}, the clients trust only certificates signed by the AISafe CA
 * (hostname verification stays on).
 *
 * <p>Uses Apache HttpClient 5: pooled keep-alive connections and PATCH support (HttpURLConnection has no PATCH).
 * Not the JDK HttpClient: it completes responses asynchronously, and on a 1-CPU container the JDK runs those
 * completions on a new thread every time, which made each peer call far more expensive (found by load testing).
 * The pool is sized explicitly because the default (5 connections per destination) throttles peer calls under load.
 */
@Component
public class HttpClientFactory {

    private final SslBundle sslBundle;
    private final int maxConnectionsPerDestination;

    public HttpClientFactory(SslBundles sslBundles,
                             @Value("${sidis.tls.client-bundle:}") String bundleName,
                             @Value("${sidis.http.max-connections-per-destination:100}") int maxConnectionsPerDestination) {
        this.sslBundle = bundleName.isBlank() ? null : sslBundles.getBundle(bundleName);
        this.maxConnectionsPerDestination = maxConnectionsPerDestination;
    }

    public ClientHttpRequestFactory create(Duration timeout) {
        Timeout t = Timeout.of(timeout);
        PoolingHttpClientConnectionManagerBuilder pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(maxConnectionsPerDestination)
                .setMaxConnTotal(maxConnectionsPerDestination * 4)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(t).setSocketTimeout(t).build());
        if (sslBundle != null) {
            SSLConnectionSocketFactoryBuilder tls = SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(sslBundle.createSslContext());
            String[] protocols = sslBundle.getOptions().getEnabledProtocols();
            if (protocols != null) {
                tls.setTlsVersions(protocols);   // TLS 1.3 only, from the "tls" profile
            }
            pool.setSSLSocketFactory(tls.build());
        }
        return new HttpComponentsClientHttpRequestFactory(HttpClients.custom()
                .setConnectionManager(pool.build())
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(t).setResponseTimeout(t).build())
                .build());
    }
}
