# Java Backend — Workspace Explorer Proxy Endpoints

> Add REST endpoints that proxy file explorer requests to the AI Agent.
> Auth is handled here (JWT → user_id). The AI Agent does the actual file ops.

---

## Pattern

```
Frontend  →  GET /api/workspace/projects
                    ↓  (backend injects user_id from JWT)
Backend   →  GET http://ai-agent:5000/workspace/{user_id}/projects
                    ↓
Backend   ←  JSON response
                    ↓
Frontend  ←  same JSON (pass-through)
```

The backend's only job here is: **verify JWT → extract user_id → proxy to AI Agent → return response**.

---

## New Servlet: `WorkspaceServlet.java`

Map to `/api/workspace/*`:

```java
package com.yourapp.servlets;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@WebServlet("/api/workspace/*")
public class WorkspaceServlet extends HttpServlet {

    private static final String AI_AGENT = "http://ai-agent:5000";

    // ── GET ───────────────────────────────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;  // getAuthenticatedUserId already wrote 401

        String agentPath = buildAgentPath(req, userId);
        String queryString = req.getQueryString();
        if (queryString != null) agentPath += "?" + queryString;

        proxyRequest(req, resp, "GET", agentPath, null);
    }

    // ── PUT ───────────────────────────────────────────────────────────────────
    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String body = readBody(req);
        proxyRequest(req, resp, "PUT", buildAgentPath(req, userId), body);
    }

    // ── POST ──────────────────────────────────────────────────────────────────
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String body = readBody(req);
        proxyRequest(req, resp, "POST", buildAgentPath(req, userId), body);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String queryString = req.getQueryString();
        String agentPath = buildAgentPath(req, userId);
        if (queryString != null) agentPath += "?" + queryString;

        proxyRequest(req, resp, "DELETE", agentPath, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Convert frontend path to agent path by injecting user_id.
     *
     * Frontend sends:  /api/workspace/projects/tic-tac-toe/files
     * Agent expects:   /workspace/{userId}/projects/tic-tac-toe/files
     */
    private String buildAgentPath(HttpServletRequest req, int userId) {
        // pathInfo: "/projects/tic-tac-toe/files"  (everything after /api/workspace)
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";
        return "/workspace/" + userId + pathInfo;
    }

    private void proxyRequest(
            HttpServletRequest req,
            HttpServletResponse resp,
            String method,
            String agentPath,
            String body) throws IOException {

        HttpURLConnection conn = (HttpURLConnection)
                new URL(AI_AGENT + agentPath).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);

        if (body != null && !body.isEmpty()) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = conn.getResponseCode();
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-cache");

        InputStream in = (status >= 200 && status < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        if (in != null) {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 PrintWriter writer = resp.getWriter()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
            }
        }
    }

    private int getAuthenticatedUserId(HttpServletRequest req,
                                        HttpServletResponse resp) throws IOException {
        // Use your existing JWT verification — same pattern as other servlets
        Integer userId = (Integer) req.getAttribute("userId");
        if (userId == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write("{\"error\":\"Unauthorized\"}");
            return -1;
        }
        return userId;
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
```

---

## Frontend ↔ Backend URL Mapping

| Frontend calls | Backend proxies to AI Agent |
|---|---|
| `GET /api/workspace/projects` | `GET /workspace/{uid}/projects` |
| `GET /api/workspace/projects/{name}/files` | `GET /workspace/{uid}/projects/{name}/files` |
| `GET /api/workspace/projects/{name}/files?recursive=true` | `GET /workspace/{uid}/projects/{name}/files?recursive=true` |
| `GET /api/workspace/projects/{name}/file?path=src/main.py` | `GET /workspace/{uid}/projects/{name}/file?path=src/main.py` |
| `PUT /api/workspace/projects/{name}/file` | `PUT /workspace/{uid}/projects/{name}/file` |
| `DELETE /api/workspace/projects/{name}/file?path=src/main.py` | `DELETE /workspace/{uid}/projects/{name}/file?path=src/main.py` |
| `POST /api/workspace/projects/{name}/file/rename` | `POST /workspace/{uid}/projects/{name}/file/rename` |
| `POST /api/workspace/projects/{name}/rename` | `POST /workspace/{uid}/projects/{name}/rename` |
| `DELETE /api/workspace/projects/{name}` | `DELETE /workspace/{uid}/projects/{name}` |
| `POST /api/workspace/projects/{name}/folder` | `POST /workspace/{uid}/projects/{name}/folder` |

The backend injects `{uid}` from the JWT so the frontend never touches user IDs directly.

---

## Add to web.xml (if not using annotation scanning)

```xml
<servlet>
    <servlet-name>WorkspaceServlet</servlet-name>
    <servlet-class>com.yourapp.servlets.WorkspaceServlet</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>WorkspaceServlet</servlet-name>
    <url-pattern>/api/workspace/*</url-pattern>
</servlet-mapping>
```

---

## Update SHARED_CONTRACTS.md

Add a new section **4.6 Workspace Explorer**:

```markdown
### 4.6 Workspace Explorer

All endpoints require `Authorization: Bearer <token>`.
user_id is extracted from the JWT — not passed by the frontend.

| Method   | Endpoint                                               | Description              |
|----------|--------------------------------------------------------|--------------------------|
| GET      | /api/workspace/projects                                | List all projects        |
| GET      | /api/workspace/projects/{name}/files                   | List files (shallow)     |
| GET      | /api/workspace/projects/{name}/files?recursive=true    | Full file tree           |
| GET      | /api/workspace/projects/{name}/file?path={path}        | Read file content        |
| PUT      | /api/workspace/projects/{name}/file                    | Write / create file      |
| DELETE   | /api/workspace/projects/{name}/file?path={path}        | Delete file or folder    |
| POST     | /api/workspace/projects/{name}/file/rename             | Rename / move file       |
| POST     | /api/workspace/projects/{name}/rename                  | Rename project           |
| DELETE   | /api/workspace/projects/{name}                         | Delete entire project    |
| POST     | /api/workspace/projects/{name}/folder                  | Create new folder        |
```

---

## Checklist

- [ ] Create `WorkspaceServlet.java` in your servlets package
- [ ] Add `@WebServlet` annotation or `web.xml` mapping for `/api/workspace/*`
- [ ] Confirm JWT filter runs before `WorkspaceServlet` and sets `req.setAttribute("userId", ...)`
- [ ] Test: `GET /api/workspace/projects` with a valid token returns project list
- [ ] Test: invalid token returns 401
- [ ] Update `SHARED_CONTRACTS.md` section 4.6
