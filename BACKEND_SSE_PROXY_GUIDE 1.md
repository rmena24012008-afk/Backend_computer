# Java Backend — SSE Proxy Changes

> The Java backend (Port 8080) receives SSE from the AI Agent (Port 5000) and
> proxies it to the Frontend (Port 5173). Three new event types must be added
> to the proxy so they reach the frontend unchanged.

---

## Why this change is needed

The AI Agent now emits 3 new SSE event types:

```
thinking    → "Thinking…" / "Thought" status
status      → per-tool activity (before + after each tool runs)
iteration   → which loop step the agent is on
```

If the Java backend has an **event allowlist or explicit forwarding logic**, these
events will be silently dropped before reaching the frontend. This guide covers
exactly what to add.

---

## Current contract (before change)

**AI Agent → Backend** (Port 5000 SSE):
```
event: token
event: tool_start
event: tool_result
event: done
event: error
```

**Backend → Frontend** (Port 8080 SSE):
```
event: token
event: tool_start
event: tool_result
event: done        ← Backend enriches this with message_id + session_title
event: error
```

---

## Updated contract (after change)

**AI Agent → Backend** (Port 5000 SSE):
```
event: thinking    ← NEW
event: iteration   ← NEW
event: status      ← NEW
event: token
event: tool_start
event: tool_result
event: done
event: error
```

**Backend → Frontend** (Port 8080 SSE):
```
event: thinking    ← NEW — pass through as-is
event: iteration   ← NEW — pass through as-is
event: status      ← NEW — pass through as-is
event: token
event: tool_start
event: tool_result
event: done        ← still enriched with message_id + session_title
event: error
```

---

## What to change in the Java backend

### Option A — Generic passthrough (recommended)

If the proxy reads lines from the AI Agent response and forwards them to the
frontend response, the simplest fix is to **forward every `event:` line
generically** instead of matching on specific names.

```java
// BEFORE — explicit event name matching
if (line.startsWith("event: token") ||
    line.startsWith("event: tool_start") ||
    line.startsWith("event: tool_result") ||
    line.startsWith("event: error")) {
    frontendWriter.write(line + "\n");
}

// AFTER — forward all events generically
// Only intercept "done" for enrichment; everything else passes straight through
if (line.startsWith("event: ")) {
    String eventName = line.substring(7).trim();
    if (eventName.equals("done")) {
        // Buffer the next "data:" line, enrich it, then write both
        pendingDone = true;
    } else {
        frontendWriter.write(line + "\n");
    }
}

if (line.startsWith("data: ") && pendingDone) {
    // Enrich done payload with message_id + session_title, then forward
    String enriched = enrichDonePayload(line, savedMessageId, sessionTitle);
    frontendWriter.write("event: done\n");
    frontendWriter.write(enriched + "\n\n");
    frontendWriter.flush();
    pendingDone = false;
}
```

This approach means **any future event types** added to the AI Agent will also
pass through automatically — no more backend changes needed.

---

### Option B — Add the 3 names to an existing allowlist

If your proxy uses an explicit set/list of allowed event names, add the three
new entries:

```java
// Example: Set-based allowlist
private static final Set<String> ALLOWED_EVENTS = Set.of(
    "token",
    "tool_start",
    "tool_result",
    "done",
    "error",
    "thinking",   // ← ADD
    "status",     // ← ADD
    "iteration"   // ← ADD
);
```

```java
// Example: if/else chain
if (eventName.equals("token")      ||
    eventName.equals("tool_start") ||
    eventName.equals("tool_result")||
    eventName.equals("error")      ||
    eventName.equals("thinking")   ||   // ← ADD
    eventName.equals("status")     ||   // ← ADD
    eventName.equals("iteration")) {    // ← ADD
    frontendWriter.write(line + "\n");
}
```

---

### Option C — Servlet / Spring streaming handler

If the backend uses a `StreamingResponseBody` or `SseEmitter`, the same rule
applies — add the three event names wherever events are selectively forwarded:

```java
// Spring SseEmitter example
SseEmitter.SseEventBuilder event = SseEmitter.event()
    .name(eventName)
    .data(eventData);

// If you filter before calling emitter.send():
if (ALLOWED_EVENTS.contains(eventName)) {
    emitter.send(event);
}
```

---

## New event payloads (read-only — backend does not modify these)

### `thinking`
```
event: thinking
data: {"label": "Thinking…", "status": "active"}

event: thinking
data: {"label": "Thought", "status": "done"}
```

### `status`
```
event: status
data: {"tool": "web_search", "label": "Searching the web", "icon": "🔍", "args_preview": "python async tips", "status": "active"}

event: status
data: {"tool": "web_search", "label": "Searching the web", "icon": "🔍", "args_preview": "python async tips", "status": "done"}
```

### `iteration`
```
event: iteration
data: {"current": 2, "max": 10}
```

---

## Updated SHARED_CONTRACTS.md table

Update the **SSE Event Types** table in section 4.3:

```markdown
| Event       | Source        | Description                                      |
|-------------|---------------|--------------------------------------------------|
| `thinking`  | AI Agent      | Agent started thinking — show spinner pill       |
| `iteration` | AI Agent      | Loop step N of 10 — show progress bar            |
| `status`    | AI Agent      | Tool activity before/after each tool runs        |
| `token`     | AI Agent      | Streamed text chunk — append to chat bubble      |
| `tool_start`| AI Agent      | Tool invocation started — show loading indicator |
| `tool_result`| AI Agent     | Tool returned result — optionally display        |
| `done`      | Java Backend  | Stream complete — contains message_id            |
| `error`     | AI Agent      | Error occurred — display message                 |
```

Note: `done` is the only event the backend **modifies** (enriches with
`message_id` and `session_title`). All other events are pure passthrough.

---

## Checklist

- [ ] Locate the SSE proxy code in the Java backend (the servlet or handler
      that opens a connection to `POST /agent/chat` and streams to the frontend)
- [ ] Apply Option A (generic passthrough) or Option B (add to allowlist)
- [ ] Confirm `done` enrichment still works after the change
- [ ] Update the SSE event table in `SHARED_CONTRACTS.md`
- [ ] Test end-to-end: send a message and confirm `thinking`, `status`,
      and `iteration` events appear in the browser's DevTools → Network → EventStream
