package com.agent.filter;

import com.agent.config.AppConfig;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * CORS filter — adds Cross-Origin Resource Sharing headers to all responses.
 * Must run BEFORE AuthFilter so that preflight OPTIONS requests are handled
 * before authentication checks.
 *
 * Configured in web.xml with url-pattern /* and filter ordering.
 */
public class CorsFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // No initialization needed
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;

        // Validate origin against configured frontend origin
        String origin = request.getHeader("Origin");
        String allowedOrigin = AppConfig.FRONTEND_ORIGIN;

        if (origin != null && origin.equals(allowedOrigin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
        } else {
            // Fallback for same-origin requests (no Origin header) or dev
            response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
        }

        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        // Handle preflight OPTIONS requests immediately
        if ("OPTIONS".equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(req, res);
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }
}
