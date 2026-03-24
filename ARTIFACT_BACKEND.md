# Java Backend — Artifact Event Passthrough Guide

> The `artifact` SSE event must pass through the Java backend unchanged.
> No enrichment needed — the content is owned entirely by the AI Agent.

---

## What the backend receives from AI Agent

```
event: artifact
data: {
  "id":      "artifact_1",
  "type":    "html",
  "title":   "Monthly Sales Chart",
  "content": "<!DOCTYPE html><html>...</html>"
}
```

## What the backend forwards to the frontend

```
event: artifact
data: {
  "id":      "artifact_1",
  "type":    "html",
  "title":   "Monthly Sales Chart",
  "content": "<!DOCTYPE html><html>...</html>"
}
```

Identical — no modification. The backend is a pure passthrough for this event.

---

## What to change

### Option A — Generic passthrough (already recommended in SSE proxy guide)

If you already applied the generic passthrough from the SSE proxy guide, **no
change needed**. The `artifact` event will flow through automatically alongside
`thinking`, `status`, and `iteration`.

### Option B — Explicit allowlist

Add `"artifact"` to wherever you list allowed event names:

```java
// Set-based allowlist
private static final Set<String> ALLOWED_EVENTS = Set.of(
    "token",
    "tool_start",
    "tool_result",
    "done",
    "error",
    "thinking",
    "status",
    "iteration",
    "artifact"    // ← ADD
);
```

```java
// if/else chain
if (eventName.equals("token")      ||
    eventName.equals("tool_start") ||
    eventName.equals("tool_result")||
    eventName.equals("error")      ||
    eventName.equals("thinking")   ||
    eventName.equals("status")     ||
    eventName.equals("iteration")  ||
    eventName.equals("artifact")) {   // ← ADD
    frontendWriter.write(line + "\n");
}
```

---

## Important: content size

The `content` field of an `artifact` event can be large — a full HTML page with
inline charts may be 10–50 KB. Make sure your SSE proxy does not have a
per-event size limit that would truncate it.

```java
// If you have a buffer size guard like this — increase or remove it for SSE
if (line.length() > 4096) {
    // ← remove this guard, or raise the limit to 512_000
}
```

Also confirm your servlet or response writer flushes after every event, not just
at the end of the stream:

```java
frontendWriter.write("event: artifact\n");
frontendWriter.write("data: " + dataJson + "\n\n");
frontendWriter.flush();   // ← must flush immediately
```

---

## Updated SHARED_CONTRACTS.md table

```markdown
| Event       | Source        | Modified by backend? | Description                        |
|-------------|---------------|---------------------|------------------------------------|
| `artifact`  | AI Agent      | No                  | Self-contained HTML/SVG visual      |
| `thinking`  | AI Agent      | No                  | Thinking pill status               |
| `iteration` | AI Agent      | No                  | Agent loop progress                |
| `status`    | AI Agent      | No                  | Per-tool activity                  |
| `token`     | AI Agent      | No                  | Streamed text chunk                |
| `tool_start`| AI Agent      | No                  | Tool invocation started            |
| `tool_result`| AI Agent     | No                  | Tool returned result               |
| `done`      | Java Backend  | Yes — adds message_id, session_title, enriches downloads | Stream complete |
| `error`     | AI Agent      | No                  | Error occurred                     |
```

---

## Checklist

- [ ] Add `"artifact"` to event allowlist (or confirm generic passthrough is in place)
- [ ] Confirm no per-event size limit that would truncate large HTML payloads
- [ ] Confirm `flush()` is called after every SSE event write
- [ ] Update `SHARED_CONTRACTS.md` table
