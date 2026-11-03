package br.com.fixflow.shared.infrastructure.configuration;


import br.com.fixflow.shared.infrastructure.observability.UserMdcFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import br.com.fixflow.shared.infrastructure.security.GlobalAuthenticationProvider;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, GlobalAuthenticationProvider provider) throws Exception {
        var entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName("FixFlow");
        http
                .authenticationProvider(provider)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api-docs/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/health",
                                "/health/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new UserMdcFilter(),
                        BasicAuthenticationFilter.class);
        return http.build();
    }
}
