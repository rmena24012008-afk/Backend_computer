package com.agent.filter;

import com.agent.service.JwtService;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;

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

public class AuthFilter implements Filter {

    private static final Logger log = AppLogger.get(AuthFilter.class);

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api",
            "/api/auth",
            "/api/auth/login",
            "/api/auth/register",
            "/api/health"
    );

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("AuthFilter initialised.");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            path = path.substring(contextPath.length());
        }

        if (PUBLIC_PATHS.contains(path)) {
            log.debug("AUTH SKIP — public path | path={}", path);
            chain.doFilter(req, res);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isBlank()) {
                authHeader = "Bearer " + tokenParam;
                log.debug("AUTH — token sourced from query param | path={}", path);
            }
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("AUTH REJECT — missing token | path={} | ip={}",
                    path, request.getRemoteAddr());
            AppLogger.auditWarn("AUTH_MISSING_TOKEN | path={} | ip={}",
                    path, request.getRemoteAddr());
            ResponseUtil.sendError(response, 401, "Missing authentication token");
            return;
        }

        String token = authHeader.substring(7);

        try {
            Claims claims   = JwtService.validateToken(token);
            Long   userId   = ((Number) claims.get("user_id")).longValue();
            String username = claims.get("username", String.class);

            request.setAttribute("userId",   userId);
            request.setAttribute("username", username);

            log.debug("AUTH OK | userId={} | username={} | path={}", userId, username, path);
            AppLogger.audit("AUTH_SUCCESS | userId={} | username={} | path={} | ip={}",
                    userId, username, path, request.getRemoteAddr());

            chain.doFilter(req, res);
        } catch (Exception e) {
            log.warn("AUTH REJECT — invalid/expired token | path={} | ip={} | reason={}",
                    path, request.getRemoteAddr(), e.getMessage());
            AppLogger.auditWarn("AUTH_INVALID_TOKEN | path={} | ip={} | reason={}",
                    path, request.getRemoteAddr(), e.getMessage());
            ResponseUtil.sendError(response, 401, "Invalid or expired token");
        }
    }

    @Override
    public void destroy() {
        log.info("AuthFilter destroyed.");
    }
}
