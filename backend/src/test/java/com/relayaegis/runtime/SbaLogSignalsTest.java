package com.relayaegis.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.runtime.LogExcerpt.LogSignal.Kind;
import org.junit.jupiter.api.Test;

/** The real Spring Boot / PostgreSQL / Hikari log lines of the SBA backend produce the signals Level 1 diagnosis relies on. */
class SbaLogSignalsTest {
    @Test
    void postgresConnectionFailuresAreRecognisedWithTheirTarget() {
        LogExcerpt parsed = DockerRuntimeInspector.parseLogs("sba-backend", String.join("\n",
                "2026-10-20T08:00:01.000Z org.postgresql.util.PSQLException: Connection to postgres:5432 refused. Check that the hostname and port are correct",
                "2026-10-20T08:00:02.000Z java.net.UnknownHostException: postgres",
                "2026-10-20T08:00:03.000Z HikariPool-1 - Connection is not available, request timed out after 30000ms."));
        assertTrue(parsed.of(Kind.CONNECTION_REFUSED).stream().anyMatch(signal -> "postgres:5432".equals(signal.target())));
        assertTrue(parsed.has(Kind.DNS_FAILURE));
        assertTrue(parsed.has(Kind.TIMEOUT));
    }
}
