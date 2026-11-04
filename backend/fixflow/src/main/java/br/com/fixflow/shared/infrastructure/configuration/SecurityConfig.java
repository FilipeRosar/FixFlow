package br.com.fixflow.shared.infrastructure.configuration;

import br.com.fixflow.modules.identity.infrastructure.security.ActiveJwtAuthenticationConverter;
import br.com.fixflow.shared.infrastructure.observability.UserMdcFilter;
import br.com.fixflow.shared.infrastructure.security.GlobalAuthenticationProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, GlobalAuthenticationProvider provider,
            JwtDecoder decoder, ActiveJwtAuthenticationConverter converter, org.springframework.core.env.Environment environment,
            @Value("${fixflow.auth.basic-enabled:false}") boolean legacyBasic,
            @Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String allowedOrigin) throws Exception {
        boolean basicForTests = legacyBasic && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("integration-test"));
        var signup = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/contas");
        var login = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login");
        var recovery = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/{operation:logout|forgot-password|reset-password}");
        var refresh = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/refresh");
        org.springframework.security.web.AuthenticationEntryPoint entryPoint = (request, response, error) -> {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", basicForTests ? "Basic realm=\"FixFlow\"" : "Bearer");
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":401,\"detail\":\"Autenticação obrigatória.\"}");
        };
        http.authenticationProvider(provider)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers(signup,
                        request -> request.getRequestURI().substring(request.getContextPath().length()).matches("/api/oficinas/[^/]+/usuarios(?:/.*)?")
                                && request.getHeader("Authorization") != null
                                && request.getHeader("Authorization").startsWith("Bearer "), request ->
                        (login.matches(request) || refresh.matches(request) || recovery.matches(request))
                        && "true".equals(request.getHeader("X-Auth-Request"))
                        && (request.getHeader("Origin") == null || allowedOrigin.equals(request.getHeader("Origin")))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/enderecos/cep/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/contas", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers("/api-docs/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/health", "/health/**").permitAll()
                         .requestMatchers(HttpMethod.POST, "/api/v1/work-orders").hasAuthority("workorder:create")
                        .requestMatchers("/api/v1/finance/**", "/api/v1/dashboard/**").hasAuthority("finance:read")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint));
        if (basicForTests) http.httpBasic(basic -> basic.authenticationEntryPoint(entryPoint));
        http.addFilterAfter(new UserMdcFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }
}
