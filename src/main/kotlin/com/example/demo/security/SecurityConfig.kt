package com.example.demo.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class SecurityConfig(@Value("\${ascii.cors.allowed-origins:http://localhost:4200}") private val allowedOrigins: List<String>) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .cors(Customizer.withDefaults())
        .csrf {
            it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
        }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/", "/index.html", "/gallery", "/gallery/*", "/prompt", "/loadingscreen",
                "/*.js", "/*.css", "/favicon.ico", "/assets/**", "/ascii/gallery", "/ascii/gallery/*", "/ascii/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/ascii/custom", "/ascii/random").permitAll()
                .anyRequest().denyAll()
        }
        .build()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        fun configuration(methods: List<String>) = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfig.allowedOrigins
            allowedMethods = methods
            allowedHeaders = listOf("Content-Type", "X-XSRF-TOKEN")
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/ascii/gallery", configuration(listOf("GET")))
            registerCorsConfiguration("/ascii/gallery/*", configuration(listOf("GET")))
            registerCorsConfiguration("/ascii/csrf", configuration(listOf("GET")))
            registerCorsConfiguration("/ascii/custom", configuration(listOf("POST")))
            registerCorsConfiguration("/ascii/random", configuration(listOf("POST")))
        }
    }
}
