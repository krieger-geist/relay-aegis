package com.relayaegis.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Outgoing HTTP for notifications: short timeouts, no redirects, nothing read from the response but its status. */
@Component
public class Outbound {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** Returns the HTTP status, or -1 when the request could not be sent. Never throws. */
    public int post(URI uri, String contentType, Map<String, String> headers, byte[] body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(request::header);
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception ex) {
            return -1;
        }
    }
}
