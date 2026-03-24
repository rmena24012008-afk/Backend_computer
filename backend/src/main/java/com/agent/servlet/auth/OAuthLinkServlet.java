package com.agent.servlet.auth;

import com.agent.dao.AuthTokenDao;
import com.agent.model.AuthToken;
import com.agent.service.OAuthTokenService;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST   /api/auth/oauth/link — Save client credentials & get authorization URL
 * GET    /api/auth/oauth/link — List all linked OAuth providers for the user
 * PUT    /api/auth/oauth/link — Force-refresh an access token
 * DELETE /api/auth/oauth/link — Unlink a provider
 */
@WebServlet("/api/auth/oauth/link")
public class OAuthLinkServlet extends HttpServlet {

    /* ── POST: Save credentials, return authorization URL ── */
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

            JsonObject json;
            try {
                json = JsonUtil.parse(body);
            } catch (Exception e) {
                ResponseUtil.sendError(response, 400, "Invalid JSON body");
                return;
            }

            String provider      = getStr(json, "provider");
            String clientId      = getStr(json, "client_id");
            String clientSecret  = getStr(json, "client_secret");
            String tokenEndpoint = getStr(json, "token_endpoint");
            String oauthLink     = getStr(json, "oauth_token_link");
            String scope         = getStr(json, "scope");
            String redirectUri   = getStr(json, "redirect_uri");
            String headerType    = json.has("header_type") ? json.get("header_type").getAsString() : "Bearer";

            if (provider == null || provider.isBlank()) {
                ResponseUtil.sendError(response, 400, "provider is required");
                return;
            }
            if (clientId == null || clientId.isBlank()) {
                ResponseUtil.sendError(response, 400, "client_id is required");
                return;
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                ResponseUtil.sendError(response, 400, "client_secret is required");
                return;
            }
            if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
                ResponseUtil.sendError(response, 400, "token_endpoint is required");
                return;
            }

            // Build and upsert the AuthToken (no access/refresh token yet)
            AuthToken token = new AuthToken(
                    userId, provider, headerType,
                    "pending",   // placeholder access_token
                    null,        // no refresh_token yet
                    null,        // no expiry yet
                    clientId, clientSecret,
                    tokenEndpoint, oauthLink
            );
            AuthTokenDao.upsert(token);

            // Build the authorization URL if oauth_token_link + scope + redirect_uri provided
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", provider);
            data.put("status", "credentials_saved");

            if (oauthLink != null && !oauthLink.isBlank()
                    && scope != null && !scope.isBlank()
                    && redirectUri != null && !redirectUri.isBlank()) {
                // Re-fetch to get the full token object with ID
                AuthToken saved = AuthTokenDao.findByUserAndProvider(userId, provider);
                String authUrl = OAuthTokenService.buildAuthorizationUrl(saved, scope, redirectUri);
                data.put("authorization_url", authUrl);
            }

            ResponseUtil.sendCreated(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Error linking provider: " + e.getMessage());
        }
    }

    /* ── GET: List all linked providers ── */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            long userId = (long) request.getAttribute("userId");
            List<AuthToken> tokens = AuthTokenDao.findByUser(userId);

            List<Map<String, Object>> result = new ArrayList<>();
            for (AuthToken t : tokens) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("provider", t.getProvider());
                entry.put("header_type", t.getHeaderType());
                entry.put("has_access_token", t.getAccessToken() != null && !"pending".equals(t.getAccessToken()));
                entry.put("has_refresh_token", t.getRefreshToken() != null);
                entry.put("expires_at", t.getExpiresAt() != null ? t.getExpiresAt().toString() : null);
                entry.put("is_expired", t.isExpired());
                entry.put("token_endpoint", t.getTokenEndpoint());
                entry.put("oauth_token_link", t.getOauthTokenLink());
                entry.put("updated_at", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
                result.add(entry);
            }

            ResponseUtil.sendSuccess(response, result);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Error listing providers: " + e.getMessage());
        }
    }

    /* ── PUT: Force-refresh an access token ── */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            long userId = (long) request.getAttribute("userId");

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

            String provider = getStr(json, "provider");

            if (provider == null || provider.isBlank()) {
                ResponseUtil.sendError(response, 400, "provider is required");
                return;
            }

            AuthToken refreshed = OAuthTokenService.refreshAccessToken(userId, provider);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", refreshed.getProvider());
            data.put("status", "refreshed");
            data.put("expires_at", refreshed.getExpiresAt() != null ? refreshed.getExpiresAt().toString() : null);

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Error refreshing token: " + e.getMessage());
        }
    }

    /* ── DELETE: Unlink a provider ── */
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            long userId  = (long) request.getAttribute("userId");
            String provider = request.getParameter("provider");

            if (provider == null || provider.isBlank()) {
                ResponseUtil.sendError(response, 400, "provider query parameter is required");
                return;
            }

            AuthToken existing = AuthTokenDao.findByUserAndProvider(userId, provider);
            if (existing == null) {
                ResponseUtil.sendError(response, 404, "Provider '" + provider + "' not linked");
                return;
            }

            AuthTokenDao.delete(userId, provider);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", provider);
            data.put("status", "unlinked");

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Error unlinking provider: " + e.getMessage());
        }
    }

    private static String getStr(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsString().trim() : null;
    }
}
