package com.momstarter.config;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-on-revert guard for the {@code server.servlet.context-path=/v1} double-prefix bug.
 *
 * <p><b>Why this test exists (and why {@code @WebMvcTest}/{@code MockMvc} tests did NOT catch it):
 * </b> {@code MockMvc} dispatches directly against the controller's {@code @RequestMapping} and
 * does NOT apply {@code server.servlet.context-path}. A controller that mistakenly hard-codes the
 * {@code /v1} prefix in its own {@code @RequestMapping} (e.g. {@code @RequestMapping("/v1/foo")})
 * passes all MockMvc tests hitting {@code /v1/foo} — but on the real embedded server, the
 * context-path ALSO prepends {@code /v1}, so the route is only reachable at {@code /v1/v1/foo} and
 * the contractually-correct {@code /v1/foo} 404s.
 *
 * <p>This test uses a real embedded server ({@code webEnvironment = RANDOM_PORT}) and
 * {@link TestRestTemplate}, both of which DO honor {@code server.servlet.context-path}, so it
 * reproduces exactly what a real client sees.
 *
 * <p><b>Path convention.</b> {@link TestRestTemplate}'s root URI already incorporates
 * {@code server.servlet.context-path} (via Spring Boot's {@code LocalHostUriTemplateHandler}), so
 * requests here use the CONTEXT-RELATIVE path (e.g. {@code /feeding-sessions}, no {@code /v1}) —
 * {@code TestRestTemplate} prepends {@code /v1} itself, producing the real client-facing URL
 * {@code /v1/feeding-sessions} exactly once. (Passing {@code /v1/feeding-sessions} here would
 * double-count the prefix in the TEST client itself and mask the very bug under guard.)
 *
 * <p><b>Must use an authenticated request.</b> {@code SecurityConfig} applies
 * {@code .anyRequest().authenticated()}, so Spring Security's filter chain returns 401 for ANY
 * path — including ones that don't exist at all — when the request is unauthenticated. An
 * unauthenticated "assert not 404" probe would therefore pass even against the buggy
 * double-{@code /v1} mapping (false negative). Authenticating first lets the request reach
 * {@code DispatcherServlet}'s actual route matching, where a wrong/missing mapping produces a
 * genuine 404 that only appears when the URL truly isn't served under {@code /v1/...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ApiVersionPrefixReachabilityTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    private String token;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("v1-prefix-reachability-test@example.com");
        user.setEmailVerified(true);
        users.saveAndFlush(user);
        token = jwtService.issueAccessToken(user.getId(), true);
    }

    @Test
    void feedingSessions_isReachableAtSingleV1Prefix() {
        // Context-relative: TestRestTemplate's root URI already applies the /v1 context-path,
        // so the real request URL is exactly /v1/feeding-sessions.
        ResponseEntity<String> response = authenticatedGet("/feeding-sessions");

        assertThat(response.getStatusCode())
                .as("GET /v1/feeding-sessions must be reachable on the real server (context-path + "
                        + "controller mapping must combine to exactly ONE /v1, not zero or two)")
                .isNotEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void consumptionMappings_isReachableAtSingleV1Prefix() {
        // Context-relative: TestRestTemplate's root URI already applies the /v1 context-path,
        // so the real request URL is exactly /v1/consumption-mappings.
        ResponseEntity<String> response = authenticatedGet("/consumption-mappings");

        assertThat(response.getStatusCode())
                .as("GET /v1/consumption-mappings must be reachable on the real server (context-path + "
                        + "controller mapping must combine to exactly ONE /v1, not zero or two)")
                .isNotEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> authenticatedGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
