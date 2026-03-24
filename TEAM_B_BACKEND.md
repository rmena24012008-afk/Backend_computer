# TEAM B — BACKEND (Java Servlets + Tomcat + MySQL)
# AI Task Agent — Full Build Prompt

---

## YOUR ROLE

You are building the **BACKEND** of an AI Task Agent application. You are the central nervous system — you receive REST API calls from the React frontend, handle authentication (JWT), store all data in MySQL, proxy chat requests to the MCP/AI server, stream AI responses back to the frontend as SSE, and maintain a WebSocket connection to the remote Task Executor machine. You connect everything together.

---

## WHAT THE OTHER TEAMS ARE BUILDING (so you understand the full picture)

### Team A — Frontend (React + Vite, Port 5173)
Team A builds the React UI. They call YOUR REST API endpoints for everything: login, register, fetching sessions, sending chat messages, listing tasks, downloading files. When a user sends a chat message, they call `POST /api/chat/{sessionId}/send` on YOUR server and expect an SSE stream back. They store the JWT token you give them in localStorage and send it as a `Bearer` token in every request header. They never talk to any other server.

### Team C — MCP Server + Task Executor (Python)
Team C builds two Python services:
1. **MCP Server (Port 5000):** A Flask server that wraps the Claude AI API. When YOU forward a chat message to `POST /agent/chat` on port 5000, it calls Claude, executes any tools (code execution, web search, file creation), and streams the response back to YOU as SSE events. You must proxy this SSE stream back to the frontend.
2. **Task Executor (Port 6000):** A WebSocket server running on a remote machine that executes code, manages files, and runs scheduled tasks. YOU connect to it via WebSocket (`ws://localhost:6000/ws`) to forward execution commands and receive results. It also sends asynchronous updates when scheduled tasks run.

---

## YOUR TECH STACK

- **Java 17** (LTS)
- **Apache Tomcat 10** (Servlet 6.0 / Jakarta EE)
- **Jakarta Servlet API** (`jakarta.servlet.*` — NOT `javax.servlet.*`, Tomcat 10 uses Jakarta namespace)
- **JDBC** for MySQL (direct JDBC, no Hibernate/JPA — keep it simple for MVP)
- **HikariCP** for connection pooling
- **MySQL 8**
- **jjwt** (io.jsonwebtoken) for JWT creation and validation
- **Gson** for JSON serialization/deserialization
- **Jakarta WebSocket API** (`jakarta.websocket.*`) for WebSocket client to Task Executor
- **Maven** for build management
- **BCrypt** (jBCrypt or Spring Security's BCryptPasswordEncoder standalone) for password hashing

### Maven Dependencies (pom.xml)
```xml
<dependencies>
    <!-- Servlet API (provided by Tomcat) -->
    <dependency>
        <groupId>jakarta.servlet</groupId>
        <artifactId>jakarta.servlet-api</artifactId>
        <version>6.0.0</version>
        <scope>provided</scope>
    </dependency>

    <!-- WebSocket API (provided by Tomcat) -->
    <dependency>
        <groupId>jakarta.websocket</groupId>
        <artifactId>jakarta.websocket-client-api</artifactId>
        <version>2.1.0</version>
        <scope>provided</scope>
    </dependency>

    <!-- MySQL JDBC Driver -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <version>8.2.0</version>
    </dependency>

    <!-- HikariCP Connection Pool -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-gson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>

    <!-- Gson -->
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>

    <!-- BCrypt -->
    <dependency>
        <groupId>org.mindrot</groupId>
        <artifactId>jbcrypt</artifactId>
        <version>0.4</version>
    </dependency>
</dependencies>
```

---

## FOLDER STRUCTURE

```
backend/
├── pom.xml
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── agent/
│       │           ├── config/
│       │           │   ├── DatabaseConfig.java       # HikariCP DataSource singleton
│       │           │   └── AppConfig.java            # Load env vars, constants
│       │           │
│       │           ├── filter/
│       │           │   ├── CorsFilter.java           # CORS headers for frontend
│       │           │   └── AuthFilter.java           # JWT validation on every request (except auth endpoints)
│       │           │
│       │           ├── servlet/
│       │           │   ├── auth/
│       │           │   │   ├── RegisterServlet.java  # POST /api/auth/register
│       │           │   │   ├── LoginServlet.java     # POST /api/auth/login
│       │           │   │   └── MeServlet.java        # GET /api/auth/me
│       │           │   │
│       │           │   ├── chat/
│       │           │   │   ├── SessionsServlet.java  # GET /api/sessions, POST /api/sessions
│       │           │   │   ├── SessionServlet.java   # DELETE /api/sessions/{id}
│       │           │   │   ├── MessagesServlet.java  # GET /api/sessions/{id}/messages
│       │           │   │   └── ChatServlet.java      # POST /api/chat/{sessionId}/send → SSE proxy
│       │           │   │
│       │           │   ├── task/
│       │           │   │   ├── TasksServlet.java     # GET /api/tasks
│       │           │   │   ├── TaskCancelServlet.java # POST /api/tasks/{id}/cancel
│       │           │   │   └── TaskDownloadServlet.java # GET /api/tasks/{id}/download
│       │           │   │
│       │           │   └── project/
│       │           │       ├── ProjectsServlet.java  # GET /api/projects
│       │           │       └── ProjectDownloadServlet.java # GET /api/projects/{id}/download
│       │           │
│       │           ├── dao/
│       │           │   ├── UserDao.java              # CRUD for users table
│       │           │   ├── SessionDao.java           # CRUD for chat_sessions table
│       │           │   ├── MessageDao.java           # CRUD for chat_messages table
│       │           │   ├── ProjectDao.java           # CRUD for projects table
│       │           │   ├── ScheduledTaskDao.java     # CRUD for scheduled_tasks table
│       │           │   └── TaskRunLogDao.java        # CRUD for task_run_logs table
│       │           │
│       │           ├── model/
│       │           │   ├── User.java                 # id, username, email, passwordHash, createdAt
│       │           │   ├── ChatSession.java          # id, userId, title, createdAt, updatedAt
│       │           │   ├── ChatMessage.java          # id, sessionId, role, content, toolCalls, createdAt
│       │           │   ├── Project.java              # id, userId, sessionId, projectId, name, description, files, createdAt
│       │           │   ├── ScheduledTask.java        # id, userId, sessionId, taskId, description, status, intervalSecs, startedAt, endsAt, totalRuns, completedRuns, outputFile, createdAt
│       │           │   └── TaskRunLog.java           # id, taskId, runNumber, status, resultData, errorMessage, executedAt
│       │           │
│       │           ├── service/
│       │           │   ├── JwtService.java           # Generate token, validate token, extract claims
│       │           │   ├── McpClient.java            # HTTP client to MCP Server (POST /agent/chat), reads SSE stream
│       │           │   └── TaskExecutorClient.java   # WebSocket client to Task Executor (Port 6000)
│       │           │
│       │           └── util/
│       │               ├── JsonUtil.java             # Gson instance, toJson/fromJson helpers
│       │               └── ResponseUtil.java         # sendSuccess(response, data), sendError(response, code, message)
│       │
│       ├── resources/
│       │   └── schema.sql                            # Full MySQL DDL (for reference/manual setup)
│       │
│       └── webapp/
│           └── WEB-INF/
│               └── web.xml                           # Servlet mappings, filter order
```

---

## DEVELOPER ASSIGNMENTS

| Dev | Responsibility | Files |
|-----|---------------|-------|
| **B1** | Auth system — servlets, JWT, password hashing, auth filter | `RegisterServlet.java`, `LoginServlet.java`, `MeServlet.java`, `AuthFilter.java`, `JwtService.java`, `UserDao.java`, `User.java` |
| **B2** | Chat & SSE proxy — receive message, forward to MCP, stream response back | `ChatServlet.java`, `SessionsServlet.java`, `SessionServlet.java`, `MessagesServlet.java`, `McpClient.java`, `SessionDao.java`, `MessageDao.java`, `ChatSession.java`, `ChatMessage.java` |
| **B3** | Data layer + Config — DB setup, all DAOs, models, task/project servlets | `DatabaseConfig.java`, `AppConfig.java`, `ProjectDao.java`, `ScheduledTaskDao.java`, `TaskRunLogDao.java`, `Project.java`, `ScheduledTask.java`, `TaskRunLog.java`, `ProjectsServlet.java`, `ProjectDownloadServlet.java`, `TasksServlet.java`, `TaskCancelServlet.java`, `TaskDownloadServlet.java` |
| **B4** | WebSocket client + integration — connect to Task Executor, handle async updates, CORS | `TaskExecutorClient.java`, `CorsFilter.java`, `JsonUtil.java`, `ResponseUtil.java`, `web.xml`, `pom.xml`, `schema.sql` |

---

## API ENDPOINTS YOU EXPOSE (to Frontend)

### Authentication

#### POST /api/auth/register
```java
// RegisterServlet.java — @WebServlet("/api/auth/register")
// 1. Parse JSON body: { username, email, password }
// 2. Validate: all fields required, email format, password min 6 chars
// 3. Check if user exists: UserDao.findByEmail(email) or UserDao.findByUsername(username)
// 4. Hash password: BCrypt.hashpw(password, BCrypt.gensalt())
// 5. Insert user: UserDao.create(user)
// 6. Generate JWT: JwtService.generateToken(user)
// 7. Return: { success: true, data: { user_id, username, email, token } }
```

#### POST /api/auth/login
```java
// LoginServlet.java — @WebServlet("/api/auth/login")
// 1. Parse JSON body: { email, password }
// 2. Find user: UserDao.findByEmail(email)
// 3. Verify password: BCrypt.checkpw(password, user.getPasswordHash())
// 4. Generate JWT: JwtService.generateToken(user)
// 5. Return: { success: true, data: { user_id, username, email, token } }
```

#### GET /api/auth/me
```java
// MeServlet.java — @WebServlet("/api/auth/me")
// 1. AuthFilter has already validated JWT and set request attribute "userId"
// 2. long userId = (long) request.getAttribute("userId");
// 3. User user = UserDao.findById(userId);
// 4. Return: { success: true, data: { user_id, username, email } }
```

### Chat Sessions

#### GET /api/sessions
```java
// SessionsServlet.java — @WebServlet("/api/sessions")
// doGet:
// 1. long userId = (long) request.getAttribute("userId");
// 2. List<ChatSession> sessions = SessionDao.findByUserId(userId);
// 3. Return JSON array sorted by updated_at DESC
```

#### POST /api/sessions
```java
// SessionsServlet.java
// doPost:
// 1. Parse JSON body: { title }
// 2. long userId = (long) request.getAttribute("userId");
// 3. ChatSession session = SessionDao.create(userId, title);
// 4. Return: { success: true, data: { session_id, title, created_at } }
```

#### DELETE /api/sessions/{id}
```java
// SessionServlet.java — @WebServlet("/api/sessions/*")
// doDelete:
// 1. Extract sessionId from URL path
// 2. Verify session belongs to user
// 3. SessionDao.delete(sessionId);
// 4. Return: { success: true, data: { message: "Session deleted" } }
```

#### GET /api/sessions/{id}/messages
```java
// MessagesServlet.java — @WebServlet("/api/sessions/*/messages")
// doGet:
// 1. Extract sessionId from URL path
// 2. Verify session belongs to user
// 3. List<ChatMessage> messages = MessageDao.findBySessionId(sessionId);
// 4. Return JSON array sorted by created_at ASC
```

### Chat — THE CRITICAL SSE PROXY ENDPOINT

#### POST /api/chat/{sessionId}/send
```java
// ChatServlet.java — @WebServlet("/api/chat/*")
// THIS IS THE MOST COMPLEX SERVLET. It must:
// 1. Parse sessionId from path, message from body
// 2. Save user message to DB: MessageDao.create(sessionId, "user", message)
// 3. Fetch conversation history: MessageDao.findBySessionId(sessionId)
// 4. Start async response: AsyncContext async = request.startAsync();
// 5. Set response content type to "text/event-stream"
// 6. Set response headers: Cache-Control: no-cache, Connection: keep-alive
// 7. Call MCP Server: POST http://localhost:5000/agent/chat with SSE response
// 8. Read MCP's SSE stream line by line
// 9. For each SSE event from MCP, forward it directly to the frontend response
// 10. Collect all tokens to build the full assistant message
// 11. When MCP stream ends (done event), save assistant message to DB
// 12. Update session title if it's the first message (use first ~50 chars of user message or AI-generated)
// 13. Complete the async context

// IMPORTANT: Use AsyncContext for SSE. Do NOT block the servlet thread.
```

**Detailed SSE Proxy Implementation Pattern:**
```java
@WebServlet(urlPatterns = "/api/chat/*", asyncSupported = true)
public class ChatServlet extends HttpServlet {
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 1. Extract session ID and user ID
        String pathInfo = request.getPathInfo(); // "/{sessionId}/send"
        long sessionId = Long.parseLong(pathInfo.split("/")[1]);
        long userId = (long) request.getAttribute("userId");
        
        // 2. Parse message
        String body = new String(request.getInputStream().readAllBytes());
        JsonObject json = JsonUtil.parse(body);
        String message = json.get("message").getAsString();
        
        // 3. Save user message
        MessageDao.create(sessionId, "user", message, null);
        
        // 4. Get history for context
        List<ChatMessage> history = MessageDao.findBySessionId(sessionId);
        
        // 5. Start async
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(300000); // 5 minute timeout
        
        // 6. Set SSE headers
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.flushBuffer();
        
        // 7. Forward to MCP in a new thread
        new Thread(() -> {
            try {
                PrintWriter writer = response.getWriter();
                StringBuilder fullResponse = new StringBuilder();
                List<Object> toolCalls = new ArrayList<>();
                
                // Call MCP Server and read its SSE stream
                McpClient.streamChat(userId, sessionId, message, history, 
                    (eventType, eventData) -> {
                        // Forward each SSE event to frontend
                        writer.write("event: " + eventType + "\n");
                        writer.write("data: " + eventData + "\n\n");
                        writer.flush();
                        
                        // Collect tokens for DB save
                        if ("token".equals(eventType)) {
                            JsonObject tokenData = JsonUtil.parse(eventData);
                            fullResponse.append(tokenData.get("content").getAsString());
                        }
                        if ("tool_start".equals(eventType) || "tool_result".equals(eventType)) {
                            toolCalls.add(eventData);
                        }
                    }
                );
                
                // Save assistant message to DB
                String toolCallsJson = toolCalls.isEmpty() ? null : JsonUtil.toJson(toolCalls);
                long messageId = MessageDao.create(sessionId, "assistant", fullResponse.toString(), toolCallsJson);
                
                // Update session title if first exchange
                ChatSession session = SessionDao.findById(sessionId);
                if ("New conversation".equals(session.getTitle())) {
                    String newTitle = message.length() > 50 ? message.substring(0, 50) + "..." : message;
                    SessionDao.updateTitle(sessionId, newTitle);
                }
                
                // Send done event
                JsonObject doneData = new JsonObject();
                doneData.addProperty("message_id", messageId);
                doneData.addProperty("session_title", SessionDao.findById(sessionId).getTitle());
                writer.write("event: done\n");
                writer.write("data: " + doneData.toString() + "\n\n");
                writer.flush();
                
                asyncContext.complete();
                
            } catch (Exception e) {
                try {
                    PrintWriter writer = response.getWriter();
                    writer.write("event: error\n");
                    writer.write("data: {\"error\":\"" + e.getMessage() + "\"}\n\n");
                    writer.flush();
                    asyncContext.complete();
                } catch (IOException ignored) {}
            }
        }).start();
    }
}
```

### Scheduled Tasks

#### GET /api/tasks
```java
// TasksServlet.java — @WebServlet("/api/tasks")
// 1. long userId = (long) request.getAttribute("userId");
// 2. List<ScheduledTask> tasks = ScheduledTaskDao.findByUserId(userId);
// 3. Return JSON array
```

#### POST /api/tasks/{taskId}/cancel
```java
// TaskCancelServlet.java — @WebServlet("/api/tasks/*/cancel")
// 1. Extract taskId from path
// 2. Verify task belongs to user
// 3. Send cancel command via WebSocket: TaskExecutorClient.send({ type: "cancel_task", task_id: taskId })
// 4. Update DB: ScheduledTaskDao.updateStatus(taskId, "cancelled")
// 5. Return: { success: true, data: { task_id, status: "cancelled" } }
```

#### GET /api/tasks/{taskId}/download
```java
// TaskDownloadServlet.java — @WebServlet("/api/tasks/*/download")
// 1. Extract taskId from path
// 2. Verify task belongs to user
// 3. Get output file path from DB
// 4. Fetch file from Task Executor: GET http://localhost:6000/download/{userId}/{filePath}
// 5. Stream binary response to frontend with appropriate headers
// NOTE: Auth can be via query param ?token=... since browser downloads can't set headers
```

### Projects

#### GET /api/projects
```java
// ProjectsServlet.java — @WebServlet("/api/projects")
// 1. long userId = (long) request.getAttribute("userId");
// 2. List<Project> projects = ProjectDao.findByUserId(userId);
// 3. Return JSON array
```

#### GET /api/projects/{projectId}/download
```java
// ProjectDownloadServlet.java — @WebServlet("/api/projects/*/download")
// 1. Extract projectId from path
// 2. Verify project belongs to user
// 3. Get project name and userId
// 4. Fetch ZIP from Task Executor: GET http://localhost:6000/download-project/{userId}/{projectName}
// 5. Stream ZIP to frontend
```

---

## API ENDPOINTS YOU CALL (on MCP Server)

### POST http://localhost:5000/agent/chat

```java
// McpClient.java — call this when the user sends a chat message

// Request body:
{
  "session_id": 1,
  "user_id": 42,
  "message": "Build me a todo app in Python",
  "history": [
    { "role": "user", "content": "Hello" },
    { "role": "assistant", "content": "Hi! How can I help you?" }
  ]
}

// Response: SSE stream (text/event-stream)
// You read this line by line and forward events to frontend.

// SSE events from MCP:
//   event: token       → { "content": "I'll" }
//   event: tool_start  → { "tool": "create_project", "tool_use_id": "toolu_abc", "input": {...} }
//   event: tool_result → { "tool_use_id": "toolu_abc", "result": {...} }
//   event: token       → { "content": "Your project is ready" }
//   event: done        → { "full_response": "...", "tool_calls": [...] }
//   event: error       → { "error": "Claude API error" }
```

**McpClient SSE reader pattern:**
```java
public class McpClient {
    private static final String MCP_URL = AppConfig.get("MCP_SERVER_URL", "http://localhost:5000");
    
    public interface SseCallback {
        void onEvent(String eventType, String eventData) throws IOException;
    }
    
    public static void streamChat(long userId, long sessionId, String message, 
                                   List<ChatMessage> history, SseCallback callback) throws Exception {
        
        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("session_id", sessionId);
        requestBody.addProperty("user_id", userId);
        requestBody.addProperty("message", message);
        requestBody.add("history", buildHistoryArray(history));
        
        // Make HTTP POST request
        URL url = new URL(MCP_URL + "/agent/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(300000); // 5 min read timeout for long AI responses
        
        // Send body
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.toString().getBytes("UTF-8"));
        }
        
        // Read SSE stream
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            
            String line;
            String currentEvent = "";
            
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                } else if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    callback.onEvent(currentEvent, data);
                    
                    if ("done".equals(currentEvent) || "error".equals(currentEvent)) {
                        break;
                    }
                }
                // Empty lines are SSE event separators — skip
            }
        }
    }
}
```

---

## WEBSOCKET CLIENT TO TASK EXECUTOR (Port 6000)

```java
// TaskExecutorClient.java — Singleton WebSocket client

@ClientEndpoint
public class TaskExecutorClient {
    private static TaskExecutorClient instance;
    private Session wsSession;
    private Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    
    // ── Connect on app startup ──
    public static synchronized TaskExecutorClient getInstance() {
        if (instance == null) {
            instance = new TaskExecutorClient();
            instance.connect();
        }
        return instance;
    }
    
    private void connect() {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            String wsUrl = AppConfig.get("TASK_EXECUTOR_WS_URL", "ws://localhost:6000/ws");
            container.connectToServer(this, new URI(wsUrl));
        } catch (Exception e) {
            // Log error, retry after delay
            scheduleReconnect();
        }
    }
    
    @OnOpen
    public void onOpen(Session session) {
        this.wsSession = session;
        // Start heartbeat
        startHeartbeat();
    }
    
    @OnMessage
    public void onMessage(String message) {
        JsonObject json = JsonUtil.parse(message);
        String type = json.get("type").getAsString();
        
        // Handle request-response pattern
        if (json.has("request_id")) {
            String requestId = json.get("request_id").getAsString();
            CompletableFuture<String> future = pendingRequests.remove(requestId);
            if (future != null) {
                future.complete(message);
            }
        }
        
        // Handle async push notifications (scheduled task updates)
        if ("task_run_update".equals(type)) {
            handleTaskRunUpdate(json);
        } else if ("task_completed".equals(type)) {
            handleTaskCompleted(json);
        }
    }
    
    // ── Send a command and wait for response ──
    public String sendAndWait(JsonObject command, long timeoutSeconds) throws Exception {
        String requestId = "req_" + UUID.randomUUID().toString().substring(0, 8);
        command.addProperty("request_id", requestId);
        
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        wsSession.getBasicRemote().sendText(command.toString());
        
        return future.get(timeoutSeconds, TimeUnit.SECONDS);
    }
    
    // ── Fire and forget ──
    public void send(JsonObject command) throws IOException {
        wsSession.getBasicRemote().sendText(command.toString());
    }
    
    // ── Handle scheduled task run updates ──
    private void handleTaskRunUpdate(JsonObject json) {
        String taskId = json.get("task_id").getAsString();
        int runNumber = json.get("run_number").getAsInt();
        String status = json.get("status").getAsString();
        String resultData = json.has("result") ? json.get("result").toString() : null;
        
        // Save to DB
        TaskRunLogDao.create(taskId, runNumber, status, resultData, null);
        ScheduledTaskDao.incrementCompletedRuns(taskId);
    }
    
    private void handleTaskCompleted(JsonObject json) {
        String taskId = json.get("task_id").getAsString();
        String outputFile = json.has("output_file") ? json.get("output_file").getAsString() : null;
        ScheduledTaskDao.updateStatus(taskId, "completed");
        if (outputFile != null) {
            ScheduledTaskDao.updateOutputFile(taskId, outputFile);
        }
    }
    
    // ── Heartbeat ──
    private void startHeartbeat() {
        new Timer(true).scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    send(JsonUtil.parse("{\"type\":\"ping\"}"));
                } catch (IOException e) {
                    scheduleReconnect();
                }
            }
        }, 30000, 30000); // Every 30 seconds
    }
    
    @OnClose
    public void onClose(Session session, CloseReason reason) {
        scheduleReconnect();
    }
    
    private void scheduleReconnect() {
        new Timer(true).schedule(new TimerTask() {
            @Override
            public void run() { connect(); }
        }, 5000); // Retry after 5 seconds
    }
}
```

### When MCP tools need the Task Executor:

The MCP Server talks to the Task Executor directly via WebSocket (MCP is also a WS client on port 6000). BUT — your backend ALSO connects to the Task Executor for:
1. **Cancelling scheduled tasks** (user clicks cancel in UI)
2. **Receiving async task run updates** (scheduled jobs push results)
3. **Downloading files** (proxy file downloads from executor to frontend)

For the initial MVP: **MCP Server handles tool execution** (code running, project creation, task scheduling) by talking to the executor directly. **Your backend handles** cancel commands, status updates, and file downloads.

---

## AUTH FILTER IMPLEMENTATION

```java
// AuthFilter.java — Applied to all /api/* except /api/auth/login and /api/auth/register

@WebFilter(urlPatterns = "/api/*", asyncSupported = true)
public class AuthFilter implements Filter {
    
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/auth/login",
        "/api/auth/register"
    );
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        
        // Skip auth for OPTIONS (CORS preflight)
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        
        // Skip auth for public paths
        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        
        // Extract token
        String authHeader = request.getHeader("Authorization");
        
        // Also check query param (for file downloads from browser)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null) {
                authHeader = "Bearer " + tokenParam;
            }
        }
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ResponseUtil.sendError(response, 401, "Missing authentication token");
            return;
        }
        
        String token = authHeader.substring(7);
        
        try {
            Claims claims = JwtService.validateToken(token);
            request.setAttribute("userId", claims.get("user_id", Long.class));
            request.setAttribute("username", claims.get("username", String.class));
            chain.doFilter(req, res);
        } catch (Exception e) {
            ResponseUtil.sendError(response, 401, "Invalid or expired token");
        }
    }
}
```

---

## CORS FILTER

```java
// CorsFilter.java — Must run BEFORE AuthFilter

@WebFilter(urlPatterns = "/*", asyncSupported = true)
public class CorsFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;
        
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:5173");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        
        // Handle preflight
        if ("OPTIONS".equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        
        chain.doFilter(req, res);
    }
}
```

---

## RESPONSE UTILITIES

```java
// ResponseUtil.java
public class ResponseUtil {
    
    public static void sendSuccess(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(200);
        
        JsonObject json = new JsonObject();
        json.addProperty("success", true);
        json.add("data", JsonUtil.getGson().toJsonTree(data));
        
        response.getWriter().write(json.toString());
    }
    
    public static void sendCreated(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(201);
        
        JsonObject json = new JsonObject();
        json.addProperty("success", true);
        json.add("data", JsonUtil.getGson().toJsonTree(data));
        
        response.getWriter().write(json.toString());
    }
    
    public static void sendError(HttpServletResponse response, int statusCode, String message) 
            throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);
        
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("error", message);
        
        response.getWriter().write(json.toString());
    }
}
```

---

## DATABASE CONFIG

```java
// DatabaseConfig.java
public class DatabaseConfig {
    private static HikariDataSource dataSource;
    
    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + 
                AppConfig.get("DB_HOST", "localhost") + ":" + 
                AppConfig.get("DB_PORT", "3306") + "/" + 
                AppConfig.get("DB_NAME", "ai_task_agent"));
            config.setUsername(AppConfig.get("DB_USER", "root"));
            config.setPassword(AppConfig.get("DB_PASSWORD", "password"));
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }
    
    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }
}
```

---

## DAO PATTERN (Example — UserDao)

```java
// UserDao.java — All DAOs follow this same pattern
public class UserDao {
    
    public static User findByEmail(String email) {
        String sql = "SELECT * FROM users WHERE email = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("DB error", e);
        }
    }
    
    public static User findById(long id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("DB error", e);
        }
    }
    
    public static long create(String username, String email, String passwordHash) {
        String sql = "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, email);
            stmt.setString(3, passwordHash);
            stmt.executeUpdate();
            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("DB error", e);
        }
    }
    
    private static User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setCreatedAt(rs.getTimestamp("created_at"));
        return user;
    }
}
```

---

## JWT SERVICE

```java
// JwtService.java
public class JwtService {
    private static final String SECRET = AppConfig.get("JWT_SECRET", "your-256-bit-secret-key-here");
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24 hours
    
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    
    public static String generateToken(User user) {
        return Jwts.builder()
            .claim("user_id", user.getId())
            .claim("username", user.getUsername())
            .claim("email", user.getEmail())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
            .signWith(KEY)
            .compact();
    }
    
    public static Claims validateToken(String token) {
        return Jwts.parser()
            .verifyWith(KEY)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
```

---

## MYSQL SCHEMA

The full DDL is in the SHARED_CONTRACTS.md file. Create it in `src/main/resources/schema.sql`. You can either run it manually or create a `SchemaInitializer` that runs on app startup.

---

## web.xml — Filter Order

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee 
         https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
         version="6.0">

    <!-- CORS filter must run FIRST -->
    <filter>
        <filter-name>CorsFilter</filter-name>
        <filter-class>com.agent.filter.CorsFilter</filter-class>
        <async-supported>true</async-supported>
    </filter>
    <filter-mapping>
        <filter-name>CorsFilter</filter-name>
        <url-pattern>/*</url-pattern>
    </filter-mapping>

    <!-- Auth filter runs second -->
    <filter>
        <filter-name>AuthFilter</filter-name>
        <filter-class>com.agent.filter.AuthFilter</filter-class>
        <async-supported>true</async-supported>
    </filter>
    <filter-mapping>
        <filter-name>AuthFilter</filter-name>
        <url-pattern>/api/*</url-pattern>
    </filter-mapping>

</web-app>
```

---

## DAY-BY-DAY MILESTONES

### Day 1
- **B1:** Maven project setup, `User.java` model, `UserDao.java`, `JwtService.java`, `RegisterServlet.java`, `LoginServlet.java`, `MeServlet.java`, `AuthFilter.java`
- **B2:** `ChatSession.java`, `ChatMessage.java` models, `SessionDao.java`, `MessageDao.java`
- **B3:** `DatabaseConfig.java`, `AppConfig.java`, `schema.sql`, run DDL on MySQL, `Project.java`, `ScheduledTask.java`, `TaskRunLog.java` models
- **B4:** `pom.xml` (all dependencies), `web.xml`, `CorsFilter.java`, `JsonUtil.java`, `ResponseUtil.java`

### Day 2
- **B1:** Test auth flow end-to-end (register → login → me), password hashing, token expiry
- **B2:** `SessionsServlet.java`, `SessionServlet.java`, `MessagesServlet.java` — full CRUD for sessions and messages
- **B3:** `ProjectDao.java`, `ScheduledTaskDao.java`, `TaskRunLogDao.java` — all remaining DAOs
- **B4:** `TaskExecutorClient.java` — WebSocket client to port 6000, connect/reconnect, heartbeat, send/receive

### Day 3
- **B1:** Help B2 with auth integration in ChatServlet
- **B2:** `McpClient.java` — HTTP client that calls MCP's `/agent/chat` and reads SSE stream. `ChatServlet.java` — the full async SSE proxy (THE HARDEST PART)
- **B3:** `TasksServlet.java`, `TaskCancelServlet.java`, `TaskDownloadServlet.java`, `ProjectsServlet.java`, `ProjectDownloadServlet.java`
- **B4:** Handle async WebSocket messages — `task_run_update`, `task_completed`. Write to DB via DAOs.

### Day 4
- **B1:** Integration test with Team A — register/login from React UI
- **B2:** Integration test — send chat from React → backend → MCP → stream back. Debug SSE proxy.
- **B3:** Integration test — task list, cancel, download from React UI
- **B4:** Integration test — WebSocket connection to Team C's Task Executor. End-to-end tool execution flow.

### Day 5
- **ALL:** Full end-to-end testing. Fix bugs. Handle edge cases (timeout, connection drops, malformed data).
- **ALL:** Load test basic: multiple concurrent SSE streams. Ensure async contexts don't leak.

---

## KEY RULES

1. **AsyncContext is REQUIRED for SSE.** You cannot hold a servlet thread open for minutes. Use `request.startAsync()` and write to the response from a separate thread.
2. **CORS filter MUST run before Auth filter.** Preflight OPTIONS requests must be handled before auth checks.
3. **All passwords must be hashed with BCrypt.** Never store plain text.
4. **Every public endpoint returns the standard JSON format:** `{ "success": true/false, "data": ... }` or `{ "success": false, "error": "..." }`.
5. **Close DB connections!** Always use try-with-resources for Connection, PreparedStatement, ResultSet.
6. **Jakarta namespace**, not javax. Tomcat 10+ uses `jakarta.servlet.*`.
7. **File downloads accept `?token=` query param** in addition to Authorization header, because browsers can't set headers for direct downloads.
8. **WebSocket reconnection is critical.** If the Task Executor connection drops, retry every 5 seconds. Never crash.
