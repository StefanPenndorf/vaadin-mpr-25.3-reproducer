package com.example.mprrepro;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Simulates a slow network / cold browser cache by delaying every request for a legacy widget set file.
 * <p>
 * In production the same effect is caused by downloading the legacy widget set (several hundred kB) over a slow
 * connection, while the Flow push message with the navigation is small and arrives first. The delay makes the
 * reproduction deterministic. Set {@code repro.widgetset-delay-ms=0} to disable it and use browser network
 * throttling instead.
 */
@Configuration
public class WidgetsetDelayFilter {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> widgetsetDelay(@Value("${repro.widgetset-delay-ms:3000}") long delayMillis) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (delayMillis > 0 && request.getRequestURI().contains("/VAADIN/widgetsets/")) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
