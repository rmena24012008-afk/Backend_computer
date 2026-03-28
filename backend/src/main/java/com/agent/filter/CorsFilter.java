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

        // Always set Vary: Origin so proxies/CDNs do not serve a CORS-less
        // cached response to a cross-origin client.
        response.setHeader("Vary", "Origin");

        String origin = request.getHeader("Origin");
//        String allowedOrigin = AppConfig.FRONTEND_ORIGIN;

        // Only reflect the ACAO header when the request Origin matches exactly.
        // For same-origin requests (no Origin header) or unknown origins, omit
        // the header entirely — the browser will block mismatched origins anyway,
        // and this avoids leaking the allowed origin in every response.
        if (origin != null && AppConfig.ALLOWED_ORIGINS.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers",
                    "Content-Type, Authorization, Cache-Control, X-Requested-With");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
            // Cache preflight result for 1 hour to reduce OPTIONS round-trips
            response.setHeader("Access-Control-Max-Age", "3600");
        }

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
