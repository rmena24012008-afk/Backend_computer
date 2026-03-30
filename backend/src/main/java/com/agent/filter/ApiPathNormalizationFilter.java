package com.agent.filter;

import com.agent.util.AppLogger;
import org.slf4j.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Normalizes API URLs so servlet mappings work with or without a trailing slash.
 * Example: /api/auth/ -> /api/auth
 */
public class ApiPathNormalizationFilter implements Filter {

    private static final Logger log = AppLogger.get(ApiPathNormalizationFilter.class);

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("ApiPathNormalizationFilter initialised.");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        String contextPath = httpRequest.getContextPath();
        String requestUri = httpRequest.getRequestURI();
        String path = requestUri.substring(contextPath.length());

        if (path.length() > 1 && path.endsWith("/")) {
            String normalizedPath = path.replaceAll("/+$", "");
            if (normalizedPath.isEmpty()) {
                normalizedPath = "/";
            }

            log.debug("PATH NORMALIZE — trailing slash removed | original={} | normalized={}", path, normalizedPath);
            RequestDispatcher dispatcher = request.getRequestDispatcher(normalizedPath);
            dispatcher.forward(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        log.info("ApiPathNormalizationFilter destroyed.");
    }
}
