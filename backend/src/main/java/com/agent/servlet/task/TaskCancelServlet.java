package com.agent.servlet.task;

import com.agent.dao.ScheduledTaskDao;
import com.agent.service.TaskExecutorClient;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/task-cancel/{taskId} — Cancel a running scheduled task.
 *
 * NOTE: Servlet spec does NOT support mid-path wildcards like /api/tasks/X/cancel.
 * Remapped to /api/task-cancel/* for correct routing.
 */
@WebServlet("/api/task-cancel/*")
public class TaskCancelServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

            // Extract taskId from path info: /api/task-cancel/{taskId}
            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                ResponseUtil.sendError(response, 400, "Task ID is required in path");
                return;
            }

            String[] parts = pathInfo.split("/");
            if (parts.length < 2 || parts[1].isBlank()) {
                ResponseUtil.sendError(response, 400, "Invalid path format");
                return;
            }

            String taskId = parts[1];

            // Verify task belongs to user
            if (!ScheduledTaskDao.belongsToUser(taskId, userId)) {
                ResponseUtil.sendError(response, 404, "Task not found");
                return;
            }

            // Send cancel command via WebSocket to Task Executor
            try {
                TaskExecutorClient client = TaskExecutorClient.getInstance();
                if (client.isConnected()) {
                    JsonObject cancelCommand = new JsonObject();
                    cancelCommand.addProperty("type", "cancel_task");
                    cancelCommand.addProperty("task_id", taskId);
                    client.send(cancelCommand);
                }
            } catch (Exception e) {
                System.err.println("Failed to send cancel to Task Executor: " + e.getMessage());
                // Continue — still update DB status even if executor is unreachable
            }

            // Update status in DB
            ScheduledTaskDao.updateStatus(taskId, "cancelled");

            // Build response
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task_id", taskId);
            data.put("status", "cancelled");

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
