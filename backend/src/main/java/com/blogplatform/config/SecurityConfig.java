package com.blogplatform.config;

import com.blogplatform.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final FindByIndexNameSessionRepository<?> sessionRepository;

    public SecurityConfig(ObjectMapper objectMapper,
                          FindByIndexNameSessionRepository<?> sessionRepository) {
        this.objectMapper = objectMapper;
        this.sessionRepository = sessionRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    @SuppressWarnings("unchecked")
    public SpringSessionBackedSessionRegistry<?> sessionRegistry() {
        return new SpringSessionBackedSessionRegistry(sessionRepository);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                    "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                    "/api/v1/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/tags").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/authors", "/api/v1/authors/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/posts/{postId}/comments").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionFixation().newSession()
                .maximumSessions(1)
                .sessionRegistry(sessionRegistry())
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error("Authentication required"));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error("Access denied"));
                })
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpStatus.OK.value());
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.success(null, "Logged out successfully"));
                })
            );

        return http.build();
    }
}
