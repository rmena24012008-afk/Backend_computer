package com.agent.servlet.auth;

import com.agent.dao.UserDao;
import com.agent.model.User;
import com.agent.service.JwtService;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/auth/register
 * Registers a new user account with username, email, and password.
 * Returns user data with a JWT token on success.
 */
@WebServlet("/api/auth/register")
public class RegisterServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {

            // 1. Parse JSON body
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

            String username = json.has("username") ? json.get("username").getAsString().trim() : null;
            String email = json.has("email") ? json.get("email").getAsString().trim() : null;
            String password = json.has("password") ? json.get("password").getAsString() : null;

            // 2. Validate required fields
            if (username == null || username.isEmpty()) {
                ResponseUtil.sendError(response, 400, "Username is required");
                return;
            }
            if (email == null || email.isEmpty()) {
                ResponseUtil.sendError(response, 400, "Email is required");
                return;
            }
            if (password == null || password.length() < 6) {
                ResponseUtil.sendError(response, 400, "Password must be at least 6 characters");
                return;
            }

            // Basic email format validation
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                ResponseUtil.sendError(response, 400, "Invalid email format");
                return;
            }

            // 3. Check if user already exists
            if (UserDao.findByEmail(email) != null || UserDao.findByUsername(username) != null) {
                ResponseUtil.sendError(response, 409, "Username or email already exists");
                return;
            }

            // 4. Hash password
            String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

            // 5. Create user
            long userId = UserDao.create(username, email, passwordHash);

            // 6. Build user object for JWT generation
            User user = new User();
            user.setId(userId);
            user.setUsername(username);
            user.setEmail(email);

            // 7. Generate JWT
            String token = JwtService.generateToken(user);

            // 8. Build response
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id", userId);
            data.put("username", username);
            data.put("email", email);
            data.put("token", token);

            ResponseUtil.sendCreated(response, data);
        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
