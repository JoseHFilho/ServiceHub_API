package com.servicehub.config;

import com.servicehub.security.DatabaseUserDetailsService;
import com.servicehub.security.PasswordHashService;
import com.servicehub.security.SecurityErrorHandler;
import com.servicehub.security.CurrentUserJwtConverter;
import com.servicehub.security.JwtProperties;
import com.servicehub.security.JwtService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@EnableMethodSecurity
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {
    @Bean("admissionClock")
    public java.time.Clock admissionClock() { return java.time.Clock.systemUTC(); }

    @Bean
    public AuthenticationManager authenticationManager(DatabaseUserDetailsService users, PasswordHashService encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setUserDetailsPasswordService(users);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt,
            CurrentUserJwtConverter converter, SecurityErrorHandler errors, Environment environment) throws Exception {
        boolean developmentDocs = environment.acceptsProfiles(Profiles.of("(dev | test) & !prod"));
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        resolver.setAllowFormEncodedBodyParameter(false);
        resolver.setAllowUriQueryParameter(false);
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // Only explicitly supplied Authorization: Bearer authenticates requests.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(request -> {
                            if (java.util.Collections.list(request.getHeaders("Authorization")).size() > 1) {
                                throw new OAuth2AuthenticationException(BearerTokenErrors.invalidRequest("Authorization inválido."));
                            }
                            return resolver.resolve(request);
                        })
                        .jwt(config -> config.decoder(jwt).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/hello", "/api/status", "/api/csrf", "/actuator/health").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/users", "/api/auth/login").permitAll();
                    authorize.requestMatchers("/graphql/schema", "/graphql/schema/**").denyAll();
                    if (developmentDocs) authorize.requestMatchers("/graphiql", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    else authorize.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").hasRole("ADMIN");
                    if (!developmentDocs) authorize.requestMatchers("/graphiql", "/graphiql/**").denyAll();
                    authorize.anyRequest().authenticated();
                });
        return http.build();
    }
}
