package com.agent.servlet.auth;

import com.agent.model.AuthToken;
import com.agent.service.OAuthTokenService;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/auth/oauth/callback?code=AUTH_CODE&state=PROVIDER_TIMESTAMP
 *
 * This servlet receives the OAuth2 authorization code from the provider
 * redirect and exchanges it for access + refresh tokens.
 */
public class OAuthCallbackServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(OAuthCallbackServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            log.info("OAUTH_CALLBACK GET | ip={}", request.getRemoteAddr());
            String code        = request.getParameter("code");
            String state       = request.getParameter("state");
            String redirectUri = request.getParameter("redirect_uri");

            if (code == null || code.isBlank()) {
                // Check for error from provider
                String error = request.getParameter("error");
                if (error != null) {
                    String errorDesc = request.getParameter("error_description");
                    ResponseUtil.sendError(response, 400,
                            "OAuth error from provider: " + error + " — " + (errorDesc != null ? errorDesc : ""));
                    return;
                }
                ResponseUtil.sendError(response, 400, "Missing 'code' parameter");
                return;
            }

            Long userId = extractUserId(request, state);
            String provider = extractProvider(request, state);

            if (provider == null || provider.isBlank()) {
                ResponseUtil.sendError(response, 400,
                        "Cannot determine provider from state. Expected format: 'provider_userId_timestamp'");
                return;
            }
            if (userId == null) {
                ResponseUtil.sendError(response, 401,
                        "Missing authenticated user context for OAuth callback");
                return;
            }

            // Determine redirect_uri — try query param, otherwise use request URL
            if (redirectUri == null || redirectUri.isBlank()) {
                redirectUri = request.getRequestURL().toString();
            }

            // Exchange authorization code for tokens
            AuthToken exchanged = OAuthTokenService.exchangeAuthorizationCode(
                    userId, provider, code, redirectUri);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider",          exchanged.getProvider());
            data.put("status",            "tokens_exchanged");
            data.put("header_type",       exchanged.getHeaderType());
            data.put("access_token",      exchanged.getAccessToken());
            data.put("has_refresh_token", exchanged.getRefreshToken() != null);
            data.put("expires_at",        exchanged.getExpiresAt() != null ? exchanged.getExpiresAt().toString() : null);
            data.put("scope",             exchanged.getScope());

            log.info("OAUTH_CALLBACK GET — tokens exchanged | userId={} | provider={}",
                    userId, exchanged.getProvider());
            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("OAUTH_CALLBACK GET — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "OAuth callback error: " + e.getMessage());
        }
    }

    /**
     * POST variant: Manually trigger code exchange via JSON body.
     * Body: { "provider": "...", "code": "...", "redirect_uri": "..." }
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();
            log.info("OAUTH_CALLBACK POST | userId={}", userId);

            String body = new String(request.getInputStream().readAllBytes());
            if (body == null || body.isBlank()) {
                ResponseUtil.sendError(response, 400, "Request body is required");
                return;
            }

            JsonObject json;
            try {
                json = JsonUtil.parse(body);
            } catch (Exception e) {
                ResponseUtil.sendError(response, 400, "Invalid JSON body");
                return;
            }

            String provider    = json.has("provider")     ? json.get("provider").getAsString().trim()     : null;
            String code        = json.has("code")         ? json.get("code").getAsString().trim()         : null;
            String redirectUri = json.has("redirect_uri") ? json.get("redirect_uri").getAsString().trim() : null;

            if (provider == null || provider.isBlank()) {
                ResponseUtil.sendError(response, 400, "provider is required");
                return;
            }
            if (code == null || code.isBlank()) {
                ResponseUtil.sendError(response, 400, "code (authorization code) is required");
                return;
            }
            if (redirectUri == null || redirectUri.isBlank()) {
                ResponseUtil.sendError(response, 400, "redirect_uri is required");
                return;
            }

            AuthToken exchanged = OAuthTokenService.exchangeAuthorizationCode(
                    userId, provider, code, redirectUri);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", exchanged.getProvider());
            data.put("status", "tokens_exchanged");
            data.put("has_access_token", exchanged.getAccessToken() != null);
            data.put("has_refresh_token", exchanged.getRefreshToken() != null);
            data.put("expires_at", exchanged.getExpiresAt() != null ? exchanged.getExpiresAt().toString() : null);

            log.info("OAUTH_CALLBACK POST — tokens exchanged | userId={} | provider={}", userId, exchanged.getProvider());
            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("OAUTH_CALLBACK POST — error | userId={} | error={}", 
                    request.getAttribute("userId"), e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "OAuth callback error: " + e.getMessage());
        }
    }

    private static Long extractUserId(HttpServletRequest request, String state) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr instanceof Long) {
            return (Long) userIdAttr;
        }
        if (userIdAttr instanceof Number) {
            return ((Number) userIdAttr).longValue();
        }

        if (state != null) {
            String[] parts = state.split("_");
            if (parts.length >= 3) {
                try {
                    return Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                    // Fall through to null if state does not carry a numeric user ID.
                }
            }
        }

        return null;
    }

    private static String extractProvider(HttpServletRequest request, String state) {
        if (state != null) {
            String[] parts = state.split("_");
            if (parts.length >= 1 && !parts[0].isBlank()) {
                return parts[0];
            }
        }

        String provider = request.getParameter("provider");
        return provider == null || provider.isBlank() ? null : provider;
    }
}
