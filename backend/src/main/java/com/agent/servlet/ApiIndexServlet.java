package com.agent.servlet;

import com.agent.util.ResponseUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApiIndexServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "AI Task Agent Backend API");
        data.put("context_path", request.getContextPath());
        data.put("auth_endpoints", List.of(
                "/api/auth",
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/me",
                "/api/auth/preferences",
                "/api/auth/oauth/link",
                "/api/auth/oauth/callback"
        ));

        ResponseUtil.sendSuccess(response, data);
    }
}
