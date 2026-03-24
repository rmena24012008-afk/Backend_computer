package com.agent.servlet.auth;

import com.agent.util.ResponseUtil;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet(urlPatterns = {"/api/auth", "/api/auth/"})
public class AuthIndexServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "Use one of the auth endpoints below.");
        data.put("endpoints", List.of(
                "POST /api/auth/register",
                "POST /api/auth/login",
                "GET /api/auth/me",
                "GET /api/auth/preferences",
                "PUT /api/auth/preferences",
                "GET /api/auth/oauth/link",
                "POST /api/auth/oauth/link",
                "PUT /api/auth/oauth/link",
                "DELETE /api/auth/oauth/link",
                "GET /api/auth/oauth/callback",
                "POST /api/auth/oauth/callback"
        ));

        ResponseUtil.sendSuccess(response, data);
    }
}
