package com.wander.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Paths that belong to the API surface rather than to the SPA. */
    private static final List<String> API_PREFIXES = List.of("/api/", "/v3/", "/swagger-ui", "/actuator/");

    /**
     * Any GET that is not part of the API surface: the SPA shell, its chunks, and
     * every client-side route such as /trips/7. All of it has to load before
     * anyone can log in, so it is public by nature — the app decides what to show
     * once /api/auth/me answers.
     *
     * This is broad, so it is paired with EndpointAuthRatchetTest, which walks
     * every handler this project declares (at any path, not just /api/**) and
     * fails if one answers an anonymous caller. A future non-API endpoint added
     * here would therefore break the build rather than quietly go public.
     */
    private static final RequestMatcher SPA_DOCUMENTS = request -> HttpMethod.GET.matches(request.getMethod())
            && API_PREFIXES.stream().noneMatch(prefix -> request.getRequestURI().startsWith(prefix));

    /**
     * Delegating encoder, so every hash carries its algorithm as a prefix
     * ({bcrypt}$2a$...). Moving to Argon2 later is then a one-line change to the
     * encoder used for new hashes: old bcrypt hashes keep verifying and get
     * re-hashed on next login, with no migration and no forced password reset.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * The session is the credential. Angular is served same-origin from this very
     * jar, so an httpOnly cookie beats a token in localStorage: XSS cannot read
     * it, and there is no refresh-token dance to get wrong.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    AuthenticationManager authenticationManager(WanderUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Verifies the password even when the user does not exist, so a missing
        // account and a wrong password take the same time to answer.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,
            ContentSecurityPolicy csp) throws Exception {

        // Angular's HttpClient reads the XSRF-TOKEN cookie and echoes it as
        // X-XSRF-TOKEN with no configuration, which is exactly what
        // CookieCsrfTokenRepository expects. Clearing the request-attribute name
        // opts out of Security's deferred token loading — without it the cookie
        // is only written once something has already read the token, so the very
        // first POST of a session has nothing to send.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        // Auth entry points and the healthcheck.
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/health").permitAll()
                        // Whether this instance accepts sign-ups, which the login
                        // page has to know before anybody has a session. One
                        // boolean; the rest of /api/config stays authenticated.
                        .requestMatchers("/api/config/sign-in").permitAll()
                        // Redeeming a password reset link. The second anonymous
                        // endpoint in this application and the only one that
                        // takes a write, and it has to be: everybody who needs
                        // it is somebody who cannot sign in. The token is the
                        // credential — 256 bits, hashed at rest, single use,
                        // expiring — and it is throttled per address like the
                        // other anonymous doors. See PasswordResetController.
                        .requestMatchers("/api/auth/reset/**").permitAll()
                        // The OpenAPI document is what generates the typed client.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // The SPA shell, its assets, and every client-side route.
                        .requestMatchers(SPA_DOCUMENTS).permitAll()
                        // Default deny. Every new endpoint is authenticated until
                        // someone deliberately lists it above.
                        .anyRequest().authenticated())
                // An unauthenticated API call gets a bare 401. The default would
                // redirect to a login page that does not exist in an SPA, and the
                // client would see an opaque 200 of HTML instead.
                // Spring Security's defaults (nosniff, X-Frame-Options: DENY,
                // HSTS over TLS) stay as they are; only the policy it has no
                // default for is added, and it is assembled from the configured
                // map hosts rather than written out here — see
                // ContentSecurityPolicy.
                .headers(headers -> {
                    if (!csp.isEnabled()) {
                        return;
                    }
                    headers.contentSecurityPolicy(policy -> {
                        policy.policyDirectives(csp.header());
                        if (csp.isReportOnly()) {
                            policy.reportOnly();
                        }
                    });
                })
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(Customizer.withDefaults());

        return http.build();
    }
}
