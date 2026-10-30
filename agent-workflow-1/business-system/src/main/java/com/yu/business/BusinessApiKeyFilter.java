package com.yu.business;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class BusinessApiKeyFilter extends OncePerRequestFilter {
    private final String apiKey;
    private final String operationsKey;

    public BusinessApiKeyFilter(@Value("${business.api-key}") String apiKey,
                                @Value("${business.operations-key}") String operationsKey) {
        if (apiKey.length() < 24 || operationsKey.length() < 24 || apiKey.equals(operationsKey)) {
            throw new IllegalStateException("Configure different business API and operations keys of at least 24 characters");
        }
        this.apiKey = apiKey;
        this.operationsKey = operationsKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/health".equals(request.getRequestURI())) { chain.doFilter(request, response); return; }
        boolean operations = request.getRequestURI().startsWith("/business/v1/operations/");
        String supplied = request.getHeader(operations ? "X-Operations-Key" : "X-Api-Key");
        String expected = operations ? operationsKey : apiKey;
        if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Valid service credential required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
