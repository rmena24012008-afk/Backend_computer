package com.agent.servlet.project;

import com.agent.dao.ProjectDao;
import com.agent.model.Project;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
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

@WebServlet("/api/projects")
public class ProjectsServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(ProjectsServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            List<Project> projects = ProjectDao.findByUserId(userId);

            // Build response array
            List<Map<String, Object>> data = new ArrayList<>();
            for (Project project : projects) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("project_id", project.getProjectId());
                item.put("name", project.getName());
                item.put("description", project.getDescription());

                // Parse files JSON string to actual JSON array
                if (project.getFiles() != null && !project.getFiles().isEmpty()) {
                    try {
                        item.put("files", JsonParser.parseString(project.getFiles()));
                    } catch (Exception e) {
                        item.put("files", project.getFiles());
                    }
                } else {
                    item.put("files", new ArrayList<>());
                }

                item.put("created_at", project.getCreatedAt());
                data.add(item);
            }

            log.debug("PROJECTS GET | userId={} | count={}", userId, data.size());
            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("PROJECTS GET — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
