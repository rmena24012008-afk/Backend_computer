# SHARED CONTRACTS — Single Source of Truth
# Project: AI Task Agent (Chat + Code Execution + Scheduled Tasks)
# Version: 1.0 — MVP Sprint

---

## 1. SYSTEM ARCHITECTURE

```
┌────────────────────┐    REST + SSE     ┌────────────────────┐   REST + SSE    ┌────────────────────┐
│                    │   (Port 8080)     │                    │  (Port 5000)    │                    │
│   TEAM A           │◄────────────────►│   TEAM B            │◄──────────────►│   TEAM C            │
│   FRONTEND         │                  │   BACKEND           │                │   MCP SERVER        │
│   React + Vite     │                  │   Java Servlets     │                │   Python Flask      │
│   Port 5173        │                  │   Tomcat 10         │                │   + Claude API      │
│                    │                  │   MySQL 8           │                │   Port 5000         │
└────────────────────┘                  └────────┬───────────┘                └──────────┬─────────┘
                                                 │                                       │
                                                 │ WebSocket (Port 6000)                 │ Internal
                                                 │                                       │ function calls
                                                 ▼                                       ▼
                                        ┌──────────────────────────────────────────────────────────┐
                                        │                                                          │
                                        │   TEAM C — TASK EXECUTOR (Remote Machine)                │
                                        │   Python WebSocket Server + APScheduler                  │
                                        │   Port 6000                                              │
                                        │                                                          │
                                        │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
                                        │   │ Code Runner   │  │ Scheduler    │  │ File Manager │  │
                                        │   │ (subprocess)  │  │ (APScheduler)│  │ (/workspace) │  │
                                        │   └──────────────┘  └──────────────┘  └──────────────┘  │
                                        │                                                          │
                                        └──────────────────────────────────────────────────────────┘
```

---

## 2. COMPLETE DATA FLOW (User Message → AI Response)

```
Step 1:  User types message in React chat UI
Step 2:  Frontend sends POST /api/chat/{sessionId}/send  { "message": "..." }
Step 3:  Backend saves message to MySQL (chat_messages table, role='user')
Step 4:  Backend opens SSE connection to MCP: POST /agent/chat  { "session_id": ..., "message": "...", "history": [...] }
Step 5:  MCP Server receives request, builds Claude API call with tools + conversation history
Step 6:  Claude returns response (may include tool_use calls)
Step 7:  If tool_use → MCP executes tool (e.g., sends WebSocket command to Task Executor)
Step 8:  Task Executor returns result → MCP feeds result back to Claude → Claude generates final text
Step 9:  MCP streams response tokens as SSE events back to Backend
Step 10: Backend proxies SSE stream to Frontend, saves complete assistant message to MySQL
Step 11: Frontend renders streamed tokens in real-time in chat bubble
```

---

## 3. API CONTRACTS — FRONTEND ↔ BACKEND (Port 8080)

### 3.1 Authentication

#### POST /api/auth/register
```json
// Request
{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "securePass123"
}

// Response 201
{
  "success": true,
  "data": {
    "user_id": 42,
    "username": "john_doe",
    "email": "john@example.com",
    "token": "eyJhbGciOiJIUzI1NiJ9..."
  }
}

// Response 409 (user exists)
{
  "success": false,
  "error": "Username or email already exists"
}
```

#### POST /api/auth/login
```json
// Request
{
  "email": "john@example.com",
  "password": "securePass123"
}

// Response 200
{
  "success": true,
  "data": {
    "user_id": 42,
    "username": "john_doe",
    "email": "john@example.com",
    "token": "eyJhbGciOiJIUzI1NiJ9..."
  }
}

// Response 401
{
  "success": false,
  "error": "Invalid email or password"
}
```

#### GET /api/auth/me
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": {
    "user_id": 42,
    "username": "john_doe",
    "email": "john@example.com"
  }
}

// Response 401
{
  "success": false,
  "error": "Invalid or expired token"
}
```

### 3.2 Chat Sessions

#### GET /api/sessions
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": [
    {
      "session_id": 1,
      "title": "Build a todo app",
      "created_at": "2025-01-18T10:30:00Z",
      "updated_at": "2025-01-18T14:20:00Z"
    },
    {
      "session_id": 2,
      "title": "Track gold prices",
      "created_at": "2025-01-17T08:00:00Z",
      "updated_at": "2025-01-17T09:15:00Z"
    }
  ]
}
```

#### POST /api/sessions
```json
// Request
Headers: Authorization: Bearer <token>
{
  "title": "New conversation"
}

// Response 201
{
  "success": true,
  "data": {
    "session_id": 3,
    "title": "New conversation",
    "created_at": "2025-01-18T15:00:00Z"
  }
}
```

#### DELETE /api/sessions/{sessionId}
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": { "message": "Session deleted" }
}
```

#### GET /api/sessions/{sessionId}/messages
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": [
    {
      "message_id": 1,
      "role": "user",
      "content": "Build me a todo app in Python",
      "created_at": "2025-01-18T10:30:00Z"
    },
    {
      "message_id": 2,
      "role": "assistant",
      "content": "I'll create a todo app for you...",
      "tool_calls": [
        {
          "tool": "create_project",
          "input": { "name": "todo-app", "files": [...] },
          "result": "Project created successfully"
        }
      ],
      "created_at": "2025-01-18T10:30:05Z"
    }
  ]
}
```

### 3.3 Chat — Send Message + SSE Stream

#### POST /api/chat/{sessionId}/send
```json
// Request
Headers: Authorization: Bearer <token>
{
  "message": "Search the internet every 3 hrs and record the gold rate in Delhi on an excel sheet for the next 2 days"
}

// Response: SSE Stream (text/event-stream)
// The response is NOT JSON — it's a Server-Sent Events stream.

event: token
data: {"content": "I'll"}

event: token
data: {"content": " set"}

event: token
data: {"content": " up"}

event: tool_start
data: {"tool": "schedule_task", "input": {"description": "Track gold rate in Delhi", "interval": "3h", "duration": "2d"}}

event: tool_result
data: {"tool": "schedule_task", "result": {"task_id": "sched_abc123", "status": "scheduled", "total_runs": 16}}

event: token
data: {"content": "I've scheduled a task..."}

event: done
data: {"message_id": 15, "session_title": "Track gold prices"}

event: error
data: {"error": "Something went wrong. Please try again."}
```

**SSE Event Types:**
| Event | Description |
|-------|-------------|
| `token` | A chunk of the assistant's text response. Append to chat bubble. |
| `tool_start` | AI is calling a tool. Show a "thinking/working" indicator. |
| `tool_result` | Tool returned a result. Optionally display it. |
| `done` | Stream complete. Contains final message_id and possibly updated session title. |
| `error` | Something failed. Display error message. |

### 3.4 Scheduled Tasks

#### GET /api/tasks
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": [
    {
      "task_id": "sched_abc123",
      "description": "Track gold rate in Delhi every 3 hours",
      "status": "running",
      "interval_seconds": 10800,
      "total_runs": 16,
      "completed_runs": 5,
      "output_file": "gold_rates.xlsx",
      "started_at": "2025-01-18T10:00:00Z",
      "ends_at": "2025-01-20T10:00:00Z",
      "created_at": "2025-01-18T10:00:00Z"
    }
  ]
}
```

#### POST /api/tasks/{taskId}/cancel
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": { "task_id": "sched_abc123", "status": "cancelled" }
}
```

#### GET /api/tasks/{taskId}/download
```
Headers: Authorization: Bearer <token>

// Response 200 — Binary file download
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="gold_rates.xlsx"
<binary file bytes>
```

### 3.5 Projects

#### GET /api/projects
```
Headers: Authorization: Bearer <token>

// Response 200
{
  "success": true,
  "data": [
    {
      "project_id": "proj_xyz789",
      "name": "todo-app",
      "description": "Python todo application",
      "files": ["main.py", "requirements.txt", "README.md"],
      "created_at": "2025-01-18T10:30:00Z"
    }
  ]
}
```

#### GET /api/projects/{projectId}/download
```
Headers: Authorization: Bearer <token>

// Response 200 — ZIP file download
Content-Type: application/zip
Content-Disposition: attachment; filename="todo-app.zip"
<binary zip bytes>
```

---

## 4. API CONTRACTS — BACKEND ↔ MCP SERVER (Port 5000)

### 4.1 Chat with Agent

#### POST /agent/chat
```json
// Request (Backend → MCP Server)
{
  "session_id": 1,
  "user_id": 42,
  "message": "Search the internet every 3 hrs and record the gold rate in Delhi",
  "history": [
    { "role": "user", "content": "Hello" },
    { "role": "assistant", "content": "Hi! How can I help you?" }
  ]
}

// Response: SSE Stream (text/event-stream)

event: token
data: {"content": "I'll"}

event: tool_start
data: {"tool": "schedule_task", "tool_use_id": "toolu_abc", "input": {"description": "Track gold rate", "interval": "3h", "duration": "2d", "steps": [...]}}

event: tool_result
data: {"tool_use_id": "toolu_abc", "result": {"task_id": "sched_abc123", "status": "scheduled"}}

event: token
data: {"content": "I've scheduled..."}

event: done
data: {"full_response": "I'll set up a scheduled task...", "tool_calls": [...]}

event: error
data: {"error": "Claude API error: rate limited"}
```

### 4.2 Health Check

#### GET /health
```json
// Response 200
{
  "status": "ok",
  "claude_api": "connected",
  "task_executor": "connected"
}
```

---

## 5. WEBSOCKET CONTRACT — BACKEND/MCP ↔ TASK EXECUTOR (Port 6000)

**Connection:** `ws://localhost:6000/ws`

### 5.1 Execute Code
```json
// → Send (to executor)
{
  "type": "execute_code",
  "request_id": "req_001",
  "user_id": 42,
  "language": "python",
  "code": "print('hello world')",
  "timeout": 30
}

// ← Receive (from executor)
{
  "type": "execution_result",
  "request_id": "req_001",
  "status": "success",
  "stdout": "hello world\n",
  "stderr": "",
  "exit_code": 0,
  "execution_time_ms": 45
}
```

### 5.2 Create Project
```json
// → Send
{
  "type": "create_project",
  "request_id": "req_002",
  "user_id": 42,
  "project_name": "todo-app",
  "files": [
    { "path": "main.py", "content": "print('todo app')" },
    { "path": "requirements.txt", "content": "flask==3.0.0" },
    { "path": "README.md", "content": "# Todo App" }
  ]
}

// ← Receive
{
  "type": "project_created",
  "request_id": "req_002",
  "project_id": "proj_xyz789",
  "project_path": "/workspace/users/42/todo-app",
  "files_created": ["main.py", "requirements.txt", "README.md"]
}
```

### 5.3 Schedule Task
```json
// → Send
{
  "type": "schedule_task",
  "request_id": "req_003",
  "user_id": 42,
  "task_id": "sched_abc123",
  "interval_seconds": 10800,
  "end_at": "2025-01-20T10:00:00Z",
  "steps": [
    {
      "action": "web_search",
      "params": { "query": "current gold rate per gram Delhi today INR" }
    },
    {
      "action": "extract_data",
      "params": { "pattern": "gold rate", "format": "number_with_currency" }
    },
    {
      "action": "append_to_excel",
      "params": {
        "file": "gold_rates.xlsx",
        "headers": ["Date & Time", "Gold Rate (₹/gram)", "Source"],
        "row": ["{timestamp}", "{extracted_rate}", "{source_url}"]
      }
    }
  ]
}

// ← Receive (immediate acknowledgment)
{
  "type": "task_scheduled",
  "request_id": "req_003",
  "task_id": "sched_abc123",
  "status": "scheduled",
  "next_run": "2025-01-18T13:00:00Z",
  "total_runs": 16
}

// ← Receive (every time the job runs — pushed automatically)
{
  "type": "task_run_update",
  "task_id": "sched_abc123",
  "run_number": 1,
  "total_runs": 16,
  "status": "success",
  "result": {
    "gold_rate": "7245",
    "currency": "INR/gram",
    "source": "goodreturns.in"
  },
  "next_run": "2025-01-18T16:00:00Z"
}

// ← Receive (when all runs complete)
{
  "type": "task_completed",
  "task_id": "sched_abc123",
  "total_runs_completed": 16,
  "output_file": "/workspace/users/42/gold_rates.xlsx"
}
```

### 5.4 Cancel Task
```json
// → Send
{
  "type": "cancel_task",
  "request_id": "req_004",
  "task_id": "sched_abc123"
}

// ← Receive
{
  "type": "task_cancelled",
  "request_id": "req_004",
  "task_id": "sched_abc123"
}
```

### 5.5 List Files
```json
// → Send
{
  "type": "list_files",
  "request_id": "req_005",
  "user_id": 42,
  "path": "/"
}

// ← Receive
{
  "type": "file_list",
  "request_id": "req_005",
  "files": [
    { "name": "todo-app", "type": "directory" },
    { "name": "gold_rates.xlsx", "type": "file", "size": 4096 }
  ]
}
```

### 5.6 Read File
```json
// → Send
{
  "type": "read_file",
  "request_id": "req_006",
  "user_id": 42,
  "path": "todo-app/main.py"
}

// ← Receive
{
  "type": "file_content",
  "request_id": "req_006",
  "path": "todo-app/main.py",
  "content": "print('todo app')"
}
```

### 5.7 Download File (REST — not WebSocket)
```
GET http://localhost:6000/download/{user_id}/{file_path}

// Response: Binary file
Content-Type: application/octet-stream
```

### 5.8 Download Project as ZIP (REST)
```
GET http://localhost:6000/download-project/{user_id}/{project_name}

// Response: ZIP file
Content-Type: application/zip
```

### 5.9 Heartbeat
```json
// → Send (every 30 seconds)
{ "type": "ping" }

// ← Receive
{ "type": "pong" }
```

---

## 6. MYSQL SCHEMA (Complete DDL)

```sql
-- ============================================
-- DATABASE: ai_task_agent
-- ============================================

CREATE DATABASE IF NOT EXISTS ai_task_agent;
USE ai_task_agent;

-- ── Users ──
CREATE TABLE users (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    username        VARCHAR(50) UNIQUE NOT NULL,
    email           VARCHAR(100) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ── Chat Sessions ──
CREATE TABLE chat_sessions (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    title           VARCHAR(255) DEFAULT 'New conversation',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ── Chat Messages ──
CREATE TABLE chat_messages (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id      BIGINT NOT NULL,
    role            VARCHAR(20) NOT NULL,           -- 'user' | 'assistant'
    content         TEXT NOT NULL,
    tool_calls      JSON DEFAULT NULL,              -- JSON array of tool call objects (nullable)
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

-- ── Projects (created by AI on remote machine) ──
CREATE TABLE projects (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    session_id      BIGINT,
    project_id      VARCHAR(100) UNIQUE NOT NULL,   -- "proj_xyz789"
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    files           JSON NOT NULL,                  -- JSON array of file paths
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE SET NULL
);

-- ── Scheduled Tasks ──
CREATE TABLE scheduled_tasks (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    session_id      BIGINT,
    task_id         VARCHAR(100) UNIQUE NOT NULL,   -- "sched_abc123"
    description     TEXT,
    status          VARCHAR(20) DEFAULT 'scheduled', -- scheduled | running | completed | cancelled | failed
    interval_secs   INT NOT NULL,
    started_at      TIMESTAMP NULL,
    ends_at         TIMESTAMP NOT NULL,
    total_runs      INT DEFAULT 0,
    completed_runs  INT DEFAULT 0,
    output_file     VARCHAR(500),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE SET NULL
);

-- ── Task Run Logs (one row per execution of a scheduled task) ──
CREATE TABLE task_run_logs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id         VARCHAR(100) NOT NULL,
    run_number      INT NOT NULL,
    status          VARCHAR(20) DEFAULT 'success',  -- success | failed
    result_data     JSON DEFAULT NULL,              -- JSON with extracted data
    error_message   TEXT,
    executed_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(task_id) ON DELETE CASCADE
);

-- ── Indexes ──
CREATE INDEX idx_sessions_user ON chat_sessions(user_id);
CREATE INDEX idx_messages_session ON chat_messages(session_id);
CREATE INDEX idx_projects_user ON projects(user_id);
CREATE INDEX idx_tasks_user ON scheduled_tasks(user_id);
CREATE INDEX idx_task_logs_task ON task_run_logs(task_id);
```

---

## 7. JWT TOKEN FORMAT

```
Header:  { "alg": "HS256", "typ": "JWT" }
Payload: { "user_id": 42, "username": "john_doe", "email": "john@example.com", "iat": 1737200000, "exp": 1737286400 }
Secret:  Environment variable JWT_SECRET (e.g., "your-256-bit-secret-key-here")
Expiry:  24 hours from issue time
```

**Usage:** Frontend stores token in `localStorage`. Sends on every request as:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```
Backend validates token on every request except `/api/auth/login` and `/api/auth/register`.

---

## 8. STANDARD ERROR RESPONSE FORMAT

All error responses across all endpoints follow this format:

```json
{
  "success": false,
  "error": "Human-readable error message"
}
```

HTTP Status Codes:
| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created |
| 400 | Bad request (missing/invalid fields) |
| 401 | Unauthorized (missing/invalid/expired token) |
| 404 | Not found |
| 409 | Conflict (e.g., duplicate user) |
| 500 | Internal server error |

---

## 9. CORS CONFIGURATION

Backend must allow:
```
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Allow-Credentials: true
```

---

## 10. ENVIRONMENT VARIABLES

### Backend (Java)
```
DB_HOST=localhost
DB_PORT=3306
DB_NAME=ai_task_agent
DB_USER=root
DB_PASSWORD=password
JWT_SECRET=your-256-bit-secret-key-here
MCP_SERVER_URL=http://localhost:5000
TASK_EXECUTOR_WS_URL=ws://localhost:6000/ws
```

### MCP Server (Python)
```
ANTHROPIC_API_KEY=sk-ant-...
TASK_EXECUTOR_WS_URL=ws://localhost:6000/ws
PORT=5000
```

### Task Executor (Python)
```
WORKSPACE_ROOT=/workspace/users
PORT=6000
SERPAPI_KEY=...  (or TAVILY_API_KEY for web search)
```

### Frontend (React/Vite)
```
VITE_API_BASE_URL=http://localhost:8080
```
