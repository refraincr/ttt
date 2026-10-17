package cn.governance;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationAudit audit) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/csrf", "/api/auth/login").permitAll()
                .anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)))
            .formLogin(login -> login.loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> {
                    audit.record(auth.getName(), "LOGIN", "SUCCESS");
                    res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                })
                .failureHandler((req, res, ex) -> {
                    audit.record(req.getParameter("username"), "LOGIN", "FAILURE");
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> {
                    if (auth != null) audit.record(auth.getName(), "LOGOUT", "SUCCESS");
                    res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                }));
        return http.build();
    }
}
