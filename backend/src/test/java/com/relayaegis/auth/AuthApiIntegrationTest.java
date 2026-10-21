package com.relayaegis.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** End-to-end HTTP behaviour of registration, login, JWT validation, refresh rotation, logout and authorization. */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long",
        "relay.auth.admin-emails=admin@relay.test"})
@AutoConfigureMockMvc
@Import(AuthApiIntegrationTest.AdminProbeController.class)
class AuthApiIntegrationTest {
    private static final String PASSWORD = "Correct-Horse-42";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired AuthProperties properties;

    /** Test-only endpoint proving that method security uses the role stored for the user. */
    @RestController
    static class AdminProbeController {
        @GetMapping("/api/test/admin-probe")
        @PreAuthorize("hasRole('ADMIN')")
        Map<String, String> probe(@AuthenticationPrincipal AuthenticatedUser user) { return Map.of("id", user.id()); }
    }

    // ---- registration ----

    @Test
    void registerCreatesAccountReturnsSessionAndSetsHardenedRefreshCookie() throws Exception {
        String email = uniqueEmail();
        MvcResult result = mvc.perform(authPost("/api/auth/register", Map.of("name", "  Ada Lovelace ", "email", email, "password", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.role").value("USER"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(RefreshCookies.NAME + "=").contains("HttpOnly", "SameSite=Strict", "Path=/api/auth", "Max-Age=");
        assertThat(setCookie).doesNotContain("Secure"); // plain-HTTP request with cookie-secure=auto

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContain(PASSWORD).doesNotContain("$2a$");
        UserEntity stored = users.findByEmail(email).orElseThrow();
        assertThat(stored.passwordHash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(stored.enabled).isTrue();
    }

    @Test
    void registerNormalizesEmailAndRejectsDuplicatesCaseInsensitively() throws Exception {
        String local = "dup-" + UUID.randomUUID();
        mvc.perform(authPost("/api/auth/register", Map.of("name", "First", "email", "  " + local.toUpperCase() + "@Relay.Test ", "password", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.email").value(local + "@relay.test"));
        mvc.perform(authPost("/api/auth/register", Map.of("name", "Second", "email", local + "@relay.test", "password", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists."))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void registerValidatesEveryFieldAndReportsFieldErrors() throws Exception {
        mvc.perform(authPost("/api/auth/register", Map.of("name", " ", "email", "not-an-email", "password", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("is required"))
                .andExpect(jsonPath("$.fieldErrors.email").value("must be a valid email address"))
                .andExpect(jsonPath("$.fieldErrors.password").value("must be at least 10 characters"));
        mvc.perform(authPost("/api/auth/register", Map.of("name", "Weak", "email", uniqueEmail(), "password", "onlyletterspassword")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("must include a letter and a number or symbol"));
        mvc.perform(authPost("/api/auth/register", "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or is not valid JSON."));
    }

    @Test
    void authPostsRequireClientHeaderAndAllowedOrigin() throws Exception {
        String json = mapper.writeValueAsString(Map.of("name", "X", "email", uniqueEmail(), "password", PASSWORD));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden());
        mvc.perform(authPost("/api/auth/register", json).header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(authPost("/api/auth/register", json).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isCreated());
    }

    // ---- login ----

    @Test
    void loginSucceedsWithCorrectCredentialsAndTokenAuthenticatesMe() throws Exception {
        String email = register("Grace Hopper");
        MvcResult login = mvc.perform(authPost("/api/auth/login", Map.of("email", email.toUpperCase(), "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andReturn();
        assertThat(login.getResponse().getCookie(RefreshCookies.NAME)).isNotNull();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.name").value("Grace Hopper"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameGenericMessage() throws Exception {
        String email = register("Wrong Password");
        mvc.perform(authPost("/api/auth/login", Map.of("email", email, "password", "Not-The-Password-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
        mvc.perform(authPost("/api/auth/login", Map.of("email", uniqueEmail(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
        mvc.perform(authPost("/api/auth/login", Map.of("email", "", "password", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledAccountCannotSignInAndItsTokensStopWorking() throws Exception {
        String email = register("Disabled User");
        String token = login(email);
        UserEntity user = users.findByEmail(email).orElseThrow();
        user.enabled = false;
        users.save(user);
        mvc.perform(authPost("/api/auth/login", Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ---- /me and token validation ----

    @Test
    void meReturnsOnlySafeFields() throws Exception {
        String email = register("Safe Fields");
        MvcResult me = mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email)))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(me.getResponse().getContentAsString()).get("data");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "email", "role", "createdAt");
        assertThat(me.getResponse().getContentAsString()).doesNotContain("$2a$").doesNotContainIgnoringCase("hash");
    }

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredTokenIsRejectedWithExpiryMessage() throws Exception {
        String email = register("Expired Token");
        UserEntity user = users.findByEmail(email).orElseThrow();
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String expired = new JwtService(properties, past).issue(user).token();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.message").value("Your session has expired. Sign in again."));
    }

    @Test
    void tamperedForeignOrMalformedTokensAreRejected() throws Exception {
        String email = register("Tampered Token");
        String token = login(email);
        UserEntity user = users.findByEmail(email).orElseThrow();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
        AuthProperties otherKey = new AuthProperties("a-completely-different-signing-secret-0123456789", properties.issuer(),
                properties.audience(), properties.accessTokenTtl(), properties.refreshTokenTtl(), "auto", "");
        AuthProperties otherAudience = new AuthProperties(properties.jwtSecret(), properties.issuer(), "another-app",
                properties.accessTokenTtl(), properties.refreshTokenTtl(), "auto", "");
        AuthProperties otherIssuer = new AuthProperties(properties.jwtSecret(), "someone-else", properties.audience(),
                properties.accessTokenTtl(), properties.refreshTokenTtl(), "auto", "");
        String unsigned = parts[0].replace(parts[0], java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes())) + "." + parts[1] + ".";
        for (String bad : new String[]{tampered, new JwtService(otherKey).issue(user).token(), new JwtService(otherAudience).issue(user).token(),
                new JwtService(otherIssuer).issue(user).token(), unsigned, "not-a-jwt"}) {
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("The access token is invalid. Sign in again."));
        }
    }

    // ---- protection of existing APIs ----

    @Test
    void existingApplicationApisRequireAuthenticationWhileHealthStaysPublic() throws Exception {
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/metrics/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assessments/ASM-1/findings/F-1/auto-repair")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assessments/ASM-1/findings/F-1/auto-repair")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/incidents/INC-1/auto-repair")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/incidents/INC-1/auto-repair")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/applications/app-1/runtime")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("healthy"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());

        String token = login(register("Operator"));
        mvc.perform(get("/api/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void corsPreflightFromFrontendOriginIsAllowed() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-relay-client"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    // ---- authorization ----

    @Test
    void roleComesFromStoredUserNotFromTheClient() throws Exception {
        String userToken = login(register("Plain User"));
        mvc.perform(get("/api/test/admin-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action."));

        mvc.perform(authPost("/api/auth/register", Map.of("name", "Admin", "email", "ADMIN@relay.test", "password", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"));
        String adminToken = login("admin@relay.test");
        mvc.perform(get("/api/test/admin-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Demoting the stored user takes effect immediately, even for an access token issued while ADMIN.
        UserEntity admin = users.findByEmail("admin@relay.test").orElseThrow();
        admin.role = Role.USER;
        users.save(admin);
        mvc.perform(get("/api/test/admin-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationIgnoresAClientSuppliedRole() throws Exception {
        String email = uniqueEmail();
        mvc.perform(authPost("/api/auth/register", Map.of("name", "Sneaky", "email", email, "password", PASSWORD, "role", "ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("USER"));
    }

    // ---- refresh and logout ----

    @Test
    void refreshRotatesTheCookieAndDetectsReuse() throws Exception {
        String email = register("Rotation");
        Cookie first = loginResult(email).getResponse().getCookie(RefreshCookies.NAME);

        MvcResult refreshed = mvc.perform(authPost("/api/auth/refresh", "").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        Cookie second = refreshed.getResponse().getCookie(RefreshCookies.NAME);
        assertThat(second.getValue()).isNotBlank().isNotEqualTo(first.getValue());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(refreshed))).andExpect(status().isOk());

        // Replaying the rotated cookie is rejected and revokes the whole login, including the successor.
        MvcResult replay = mvc.perform(authPost("/api/auth/refresh", "").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Sign in again."))
                .andReturn();
        assertThat(replay.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        mvc.perform(authPost("/api/auth/refresh", "").cookie(second)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutOrWithUnknownCookieIsUnauthorized() throws Exception {
        mvc.perform(authPost("/api/auth/refresh", "")).andExpect(status().isUnauthorized());
        mvc.perform(authPost("/api/auth/refresh", "").cookie(new Cookie(RefreshCookies.NAME, "forged-value")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshTokenServerSideAndClearsTheCookie() throws Exception {
        String email = register("Logout");
        Cookie cookie = loginResult(email).getResponse().getCookie(RefreshCookies.NAME);
        MvcResult logout = mvc.perform(authPost("/api/auth/logout", "").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signedOut").value(true))
                .andReturn();
        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains(RefreshCookies.NAME + "=", "Max-Age=0", "Path=/api/auth");
        mvc.perform(authPost("/api/auth/refresh", "").cookie(cookie)).andExpect(status().isUnauthorized());
        // Logging out without a session is harmless.
        mvc.perform(authPost("/api/auth/logout", "")).andExpect(status().isOk());
    }

    @Test
    void refreshForADisabledUserEndsTheSession() throws Exception {
        String email = register("Disabled Refresh");
        Cookie cookie = loginResult(email).getResponse().getCookie(RefreshCookies.NAME);
        UserEntity user = users.findByEmail(email).orElseThrow();
        user.enabled = false;
        users.save(user);
        mvc.perform(authPost("/api/auth/refresh", "").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private static String uniqueEmail() { return "user-" + UUID.randomUUID() + "@relay.test"; }

    private MockHttpServletRequestBuilder authPost(String path, Object body) throws Exception {
        String json = body instanceof String raw ? raw : mapper.writeValueAsString(body);
        return post(path).contentType(MediaType.APPLICATION_JSON).header(AuthRequestGuardFilter.HEADER, "web").content(json);
    }

    private String register(String name) throws Exception {
        String email = uniqueEmail();
        mvc.perform(authPost("/api/auth/register", Map.of("name", name, "email", email, "password", PASSWORD))).andExpect(status().isCreated());
        return email;
    }

    private MvcResult loginResult(String email) throws Exception {
        return mvc.perform(authPost("/api/auth/login", Map.of("email", email, "password", PASSWORD))).andExpect(status().isOk()).andReturn();
    }

    private String login(String email) throws Exception { return accessToken(loginResult(email)); }

    private String accessToken(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).at("/data/accessToken").asText();
    }
}
