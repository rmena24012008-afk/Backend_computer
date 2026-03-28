package com.agent.servlet.auth;

import com.agent.dao.UserDao;
import com.agent.model.User;
import com.agent.service.JwtService;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/auth/login
 * Authenticates a user with email and password.
 * Returns user data with a JWT token on success.
 */
public class LoginServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(LoginServlet.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String ip = request.getRemoteAddr();
        log.info("LOGIN attempt | ip={}", ip);

        try {
            // 1. Parse JSON body
            String body = new String(request.getInputStream().readAllBytes());
            if (body == null || body.isBlank()) {
                log.warn("LOGIN failed — empty body | ip={}", ip);
                ResponseUtil.sendError(response, 400, "Request body is required");
                return;
            }

            JsonObject json;
            try {
                json = JsonUtil.parse(body);
            } catch (Exception e) {
                log.warn("LOGIN failed — invalid JSON | ip={}", ip);
                ResponseUtil.sendError(response, 400, "Invalid JSON body");
                return;
            }

            String email    = json.has("email")    ? json.get("email").getAsString().trim() : null;
            String password = json.has("password") ? json.get("password").getAsString()     : null;

            // 2. Validate required fields
            if (email == null || email.isEmpty()) {
                log.warn("LOGIN failed — missing email | ip={}", ip);
                ResponseUtil.sendError(response, 400, "Email is required");
                return;
            }
            if (password == null || password.isEmpty()) {
                log.warn("LOGIN failed — missing password | email={} | ip={}", email, ip);
                ResponseUtil.sendError(response, 400, "Password is required");
                return;
            }

            // 3. Find user by email
            User user = UserDao.findByEmail(email);
            if (user == null) {
                log.warn("LOGIN failed — user not found | email={} | ip={}", email, ip);
                AppLogger.auditWarn("LOGIN_FAILED | reason=user_not_found | email={} | ip={}", email, ip);
                ResponseUtil.sendError(response, 401, "Invalid email or password");
                return;
            }

            // 4. Verify password
            if (!BCrypt.checkpw(password, user.getPasswordHash())) {
                log.warn("LOGIN failed — wrong password | email={} | ip={}", email, ip);
                AppLogger.auditWarn("LOGIN_FAILED | reason=wrong_password | email={} | ip={}", email, ip);
                ResponseUtil.sendError(response, 401, "Invalid email or password");
                return;
            }

            // 5. Generate JWT
            String token = JwtService.generateToken(user);

            // 6. Build response
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id",  user.getId());
            data.put("username", user.getUsername());
            data.put("email",    user.getEmail());
            data.put("token",    token);

            log.info("LOGIN success | userId={} | username={} | ip={}",
                    user.getId(), user.getUsername(), ip);
            AppLogger.audit("LOGIN_SUCCESS | userId={} | username={} | email={} | ip={}",
                    user.getId(), user.getUsername(), email, ip);

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("LOGIN error | ip={} | error={}", ip, e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
