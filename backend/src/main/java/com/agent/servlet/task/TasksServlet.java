package com.agent.servlet.task;

import com.agent.dao.ScheduledTaskDao;
import com.agent.model.ScheduledTask;
import com.agent.util.ResponseUtil;
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
 * GET /api/tasks — List all scheduled tasks for the authenticated user.
 */
@WebServlet("/api/tasks")
public class TasksServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

            List<ScheduledTask> tasks = ScheduledTaskDao.findByUserId(userId);

            // Build response array
            List<Map<String, Object>> data = new ArrayList<>();
            for (ScheduledTask task : tasks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("task_id", task.getTaskId());
                item.put("description", task.getDescription());
                item.put("status", task.getStatus());
                item.put("interval_seconds", task.getIntervalSecs());
                item.put("total_runs", task.getTotalRuns());
                item.put("completed_runs", task.getCompletedRuns());
                item.put("output_file", task.getOutputFile());
                item.put("started_at", task.getStartedAt());
                item.put("ends_at", task.getEndsAt());
                item.put("created_at", task.getCreatedAt());
                data.add(item);
            }

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
