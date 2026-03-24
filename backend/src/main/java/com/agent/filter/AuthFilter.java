package com.agent.filter;

import com.agent.service.JwtService;
import com.agent.util.ResponseUtil;
import io.jsonwebtoken.Claims;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

/**
 * Authentication filter — validates JWT tokens on all /api/* requests
 * except public endpoints (login, register).
 *
 * On successful validation, sets request attributes:
 *   - "userId" (Long) — the authenticated user's ID
 *   - "username" (String) — the authenticated user's username
 *
 * Configured in web.xml with url-pattern /api/* and runs after CorsFilter.
 */
public class AuthFilter implements Filter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api",
            "/api/auth",
            "/api/auth/login",
            "/api/auth/register"
    );

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // No initialization needed
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Skip auth for OPTIONS (CORS preflight) — already handled by CorsFilter
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        // Skip auth for public paths
        String path = request.getRequestURI();
        // Strip context path if present
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            path = path.substring(contextPath.length());
        }

        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }

        // Extract token from Authorization header
        String authHeader = request.getHeader("Authorization");


        // Also check query param ?token=... (for file downloads from browser)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isBlank()) {
                authHeader = "Bearer " + tokenParam;
            }
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ResponseUtil.sendError(response, 401, "Missing authentication token");
            return;
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = JwtService.validateToken(token);
            Long userId = ((Number) claims.get("user_id")).longValue();
            request.setAttribute("userId", userId);
            request.setAttribute("username", claims.get("username", String.class));
            chain.doFilter(req, res);
        } catch (Exception e) {
            ResponseUtil.sendError(response, 401, "Invalid or expired token");
        }
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }
}
