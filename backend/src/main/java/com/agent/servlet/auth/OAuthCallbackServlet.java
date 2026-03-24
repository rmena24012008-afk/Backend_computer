package com.agent.servlet.auth;

import com.agent.dao.AuthTokenDao;
import com.agent.model.AuthToken;
import com.agent.service.OAuthTokenService;
import com.agent.util.ResponseUtil;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
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
@WebServlet("/api/auth/oauth/callback")
public class OAuthCallbackServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            long userId = (long) request.getAttribute("userId");

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

            // Extract provider name from state (format: "providerName_timestamp")
            String provider = null;
            if (state != null && state.contains("_")) {
                provider = state.substring(0, state.lastIndexOf('_'));
            }
            // Fallback: accept provider as a query param
            if (provider == null || provider.isBlank()) {
                provider = request.getParameter("provider");
            }
            if (provider == null || provider.isBlank()) {
                ResponseUtil.sendError(response, 400,
                        "Cannot determine provider from state. Expected format: 'providerName_timestamp'");
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
            data.put("provider", exchanged.getProvider());
            data.put("status", "tokens_exchanged");
            data.put("has_access_token", exchanged.getAccessToken() != null);
            data.put("has_refresh_token", exchanged.getRefreshToken() != null);
            data.put("expires_at", exchanged.getExpiresAt() != null ? exchanged.getExpiresAt().toString() : null);

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
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
            long userId = (long) request.getAttribute("userId");

            String body = new String(request.getInputStream().readAllBytes());
            if (body == null || body.isBlank()) {
                ResponseUtil.sendError(response, 400, "Request body is required");
                return;
            }

            com.google.gson.JsonObject json;
            try {
                json = com.agent.util.JsonUtil.parse(body);
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

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "OAuth callback error: " + e.getMessage());
        }
    }
}
