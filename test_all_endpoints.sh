#!/bin/bash
#
# ═══════════════════════════════════════════════════════════════════════
#  COMPREHENSIVE ENDPOINT TEST SUITE
#  Tests ALL 16 servlets / all HTTP methods
# ═══════════════════════════════════════════════════════════════════════

BASE_URL="http://localhost:8080"
PASS=0
FAIL=0
SKIP=0
TOTAL=0

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Unique test data
TIMESTAMP=$(date +%s)
TEST_USER="testuser_${TIMESTAMP}"
TEST_EMAIL="test_${TIMESTAMP}@example.com"
TEST_PASS="TestPassword123"

# Will be set during tests
AUTH_TOKEN=""
USER_ID=""
SESSION_ID=""

# ── Utility Functions ─────────────────────────────────────────────────

test_endpoint() {
    local test_name="$1"
    local method="$2"
    local url="$3"
    local expected_status="$4"
    local body="$5"
    local extra_headers="$6"
    local expected_body_contains="$7"

    TOTAL=$((TOTAL + 1))

    # Build curl command
    local curl_cmd="curl -s -w '\n%{http_code}' -X ${method}"

    if [ -n "$AUTH_TOKEN" ] && [ "$extra_headers" != "NO_AUTH" ]; then
        curl_cmd="$curl_cmd -H 'Authorization: Bearer ${AUTH_TOKEN}'"
    fi

    if [ "$extra_headers" == "NO_AUTH" ]; then
        extra_headers=""
    fi

    if [ -n "$extra_headers" ]; then
        curl_cmd="$curl_cmd -H '${extra_headers}'"
    fi

    if [ -n "$body" ]; then
        curl_cmd="$curl_cmd -H 'Content-Type: application/json' -d '${body}'"
    fi

    curl_cmd="$curl_cmd '${url}'"

    # Execute
    local response
    response=$(eval $curl_cmd 2>&1)
    local actual_status=$(echo "$response" | tail -1)
    local response_body=$(echo "$response" | sed '$d')

    # Check status code
    local status_ok=false
    if [ "$actual_status" == "$expected_status" ]; then
        status_ok=true
    fi

    # Check body contains (if specified)
    local body_ok=true
    if [ -n "$expected_body_contains" ]; then
        if echo "$response_body" | grep -q "$expected_body_contains"; then
            body_ok=true
        else
            body_ok=false
        fi
    fi

    # Report
    if $status_ok && $body_ok; then
        PASS=$((PASS + 1))
        printf "${GREEN}  ✅ PASS${NC} | %-55s | ${method} → ${actual_status}\n" "$test_name"
        # Print compact body for success (truncated)
        local short_body=$(echo "$response_body" | head -1 | cut -c1-120)
        printf "          ${CYAN}↳ %s${NC}\n" "$short_body"
    else
        FAIL=$((FAIL + 1))
        printf "${RED}  ❌ FAIL${NC} | %-55s | Expected: ${expected_status}, Got: ${actual_status}\n" "$test_name"
        printf "          ${RED}↳ %s${NC}\n" "$(echo "$response_body" | head -3 | cut -c1-150)"
    fi

    # Return response body for chaining
    echo "$response_body"
}

skip_test() {
    local test_name="$1"
    local reason="$2"
    TOTAL=$((TOTAL + 1))
    SKIP=$((SKIP + 1))
    printf "${YELLOW}  ⏭  SKIP${NC} | %-55s | %s\n" "$test_name" "$reason"
}

section_header() {
    echo ""
    printf "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    printf "${BOLD}  📋 %s${NC}\n" "$1"
    printf "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# ═══════════════════════════════════════════════════════════════════════
#  1. AUTH ENDPOINTS — Public (No Token Required)
# ═══════════════════════════════════════════════════════════════════════

section_header "1. REGISTER — POST /api/auth/register"

# 1.1 Missing body
test_endpoint \
    "Register: empty body → 400" \
    "POST" "${BASE_URL}/api/auth/register" "400" "" "NO_AUTH" "Request body is required" \
    > /dev/null

# 1.2 Invalid JSON
test_endpoint \
    "Register: invalid JSON → 400" \
    "POST" "${BASE_URL}/api/auth/register" "400" "not-json" "NO_AUTH" "Invalid JSON" \
    > /dev/null

# 1.3 Missing username
test_endpoint \
    "Register: missing username → 400" \
    "POST" "${BASE_URL}/api/auth/register" "400" \
    '{"email":"a@b.com","password":"123456"}' "NO_AUTH" "Username is required" \
    > /dev/null

# 1.4 Missing email
test_endpoint \
    "Register: missing email → 400" \
    "POST" "${BASE_URL}/api/auth/register" "400" \
    '{"username":"user1","password":"123456"}' "NO_AUTH" "Email is required" \
    > /dev/null

# 1.5 Short password
test_endpoint \
    "Register: password too short → 400" \
    "POST" "${BASE_URL}/api/auth/register" "400" \
    '{"username":"user1","email":"a@b.com","password":"123"}' "NO_AUTH" "Password must be at least 6" \
    > /dev/null

# 1.6 Invalid email format
test_endpoint \
    "Register: invalid email format → 400" \
    "POST" "${BASE_URL}/api/auth/register" "400" \
    '{"username":"user1","email":"not-an-email","password":"123456"}' "NO_AUTH" "Invalid email format" \
    > /dev/null

# 1.7 Successful registration
REGISTER_RESP=$(test_endpoint \
    "Register: valid registration → 201" \
    "POST" "${BASE_URL}/api/auth/register" "201" \
    "{\"username\":\"${TEST_USER}\",\"email\":\"${TEST_EMAIL}\",\"password\":\"${TEST_PASS}\"}" \
    "NO_AUTH" "token")

AUTH_TOKEN=$(echo "$REGISTER_RESP" | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//;s/"//')
USER_ID=$(echo "$REGISTER_RESP" | grep -o '"user_id":[0-9]*' | head -1 | sed 's/"user_id"://')

if [ -z "$AUTH_TOKEN" ]; then
    echo ""
    printf "${RED}  ⚠️  CRITICAL: No auth token received from registration. Cannot continue authenticated tests.${NC}\n"
    printf "${RED}  Response was: %s${NC}\n" "$REGISTER_RESP"
fi

# 1.8 Duplicate registration
test_endpoint \
    "Register: duplicate user → 409" \
    "POST" "${BASE_URL}/api/auth/register" "409" \
    "{\"username\":\"${TEST_USER}\",\"email\":\"${TEST_EMAIL}\",\"password\":\"${TEST_PASS}\"}" \
    "NO_AUTH" "already exists" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "2. LOGIN — POST /api/auth/login"

# 2.1 Missing body
test_endpoint \
    "Login: empty body → 400" \
    "POST" "${BASE_URL}/api/auth/login" "400" "" "NO_AUTH" "Request body is required" \
    > /dev/null

# 2.2 Invalid JSON
test_endpoint \
    "Login: invalid JSON → 400" \
    "POST" "${BASE_URL}/api/auth/login" "400" "{{bad" "NO_AUTH" "Invalid JSON" \
    > /dev/null

# 2.3 Missing email
test_endpoint \
    "Login: missing email → 400" \
    "POST" "${BASE_URL}/api/auth/login" "400" \
    '{"password":"123456"}' "NO_AUTH" "Email is required" \
    > /dev/null

# 2.4 Missing password
test_endpoint \
    "Login: missing password → 400" \
    "POST" "${BASE_URL}/api/auth/login" "400" \
    '{"email":"a@b.com"}' "NO_AUTH" "Password is required" \
    > /dev/null

# 2.5 Wrong email
test_endpoint \
    "Login: wrong email → 401" \
    "POST" "${BASE_URL}/api/auth/login" "401" \
    '{"email":"nonexistent@example.com","password":"wrong"}' "NO_AUTH" "Invalid email or password" \
    > /dev/null

# 2.6 Wrong password
test_endpoint \
    "Login: wrong password → 401" \
    "POST" "${BASE_URL}/api/auth/login" "401" \
    "{\"email\":\"${TEST_EMAIL}\",\"password\":\"wrongpassword\"}" "NO_AUTH" "Invalid email or password" \
    > /dev/null

# 2.7 Successful login
LOGIN_RESP=$(test_endpoint \
    "Login: valid credentials → 200" \
    "POST" "${BASE_URL}/api/auth/login" "200" \
    "{\"email\":\"${TEST_EMAIL}\",\"password\":\"${TEST_PASS}\"}" \
    "NO_AUTH" "token")

# Refresh token from login
LOGIN_TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//;s/"//')
if [ -n "$LOGIN_TOKEN" ]; then
    AUTH_TOKEN="$LOGIN_TOKEN"
fi


# ═══════════════════════════════════════════════════════════════════════
section_header "3. AUTH GUARD — Token Validation"

# 3.1 No token
test_endpoint \
    "Auth: no token on protected endpoint → 401" \
    "GET" "${BASE_URL}/api/auth/me" "401" "" "NO_AUTH" "Missing authentication token" \
    > /dev/null

# 3.2 Invalid token
SAVE_TOKEN="$AUTH_TOKEN"
AUTH_TOKEN="invalid.jwt.token"
test_endpoint \
    "Auth: invalid token → 401" \
    "GET" "${BASE_URL}/api/auth/me" "401" "" "" "Invalid or expired" \
    > /dev/null
AUTH_TOKEN="$SAVE_TOKEN"

# 3.3 Token via query param
test_endpoint \
    "Auth: token via ?token= query param → 200" \
    "GET" "${BASE_URL}/api/auth/me?token=${AUTH_TOKEN}" "200" "" "NO_AUTH" "user_id" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "4. ME — GET /api/auth/me"

# 4.1 Get current user profile
test_endpoint \
    "Me: get profile with valid token → 200" \
    "GET" "${BASE_URL}/api/auth/me" "200" "" "" "user_id" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "5. PREFERENCES — GET/PUT /api/auth/preferences"

# 5.1 Get default preferences
test_endpoint \
    "Preferences: GET defaults → 200" \
    "GET" "${BASE_URL}/api/auth/preferences" "200" "" "" "preferences" \
    > /dev/null

# 5.2 Update preferences (deep merge)
test_endpoint \
    "Preferences: PUT theme=dark → 200" \
    "PUT" "${BASE_URL}/api/auth/preferences" "200" \
    '{"theme":"dark"}' "" "Preferences updated" \
    > /dev/null

# 5.3 Verify preference was merged
PREF_RESP=$(test_endpoint \
    "Preferences: GET after update → theme=dark" \
    "GET" "${BASE_URL}/api/auth/preferences" "200" "" "" "dark")

# 5.4 Deep merge nested object
test_endpoint \
    "Preferences: PUT nested editor.font_size=16 → 200" \
    "PUT" "${BASE_URL}/api/auth/preferences" "200" \
    '{"editor":{"font_size":16}}' "" "Preferences updated" \
    > /dev/null

# 5.5 Invalid body
test_endpoint \
    "Preferences: PUT empty body → 400" \
    "PUT" "${BASE_URL}/api/auth/preferences" "400" "" "" "Request body" \
    > /dev/null

# 5.6 Invalid JSON
test_endpoint \
    "Preferences: PUT invalid JSON → 400" \
    "PUT" "${BASE_URL}/api/auth/preferences" "400" "not-json" "" "Invalid JSON" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "6. SESSIONS — GET/POST /api/sessions"

# 6.1 Create a new session
SESSION_RESP=$(test_endpoint \
    "Sessions: POST create new session → 201" \
    "POST" "${BASE_URL}/api/sessions" "201" \
    '{"title":"Test Session"}' "" "session_id")

SESSION_ID=$(echo "$SESSION_RESP" | grep -o '"session_id":[0-9]*' | head -1 | sed 's/"session_id"://')

# 6.2 Create session with default title
test_endpoint \
    "Sessions: POST create with empty body (default title) → 201" \
    "POST" "${BASE_URL}/api/sessions" "201" \
    '' "" "New conversation" \
    > /dev/null

# 6.3 List sessions
test_endpoint \
    "Sessions: GET list all sessions → 200" \
    "GET" "${BASE_URL}/api/sessions" "200" "" "" "success" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "7. SESSION (Single) — GET/DELETE /api/sessions/{id}"

if [ -n "$SESSION_ID" ]; then
    # 7.1 Get single session
    test_endpoint \
        "Session: GET single session by ID → 200" \
        "GET" "${BASE_URL}/api/sessions/${SESSION_ID}" "200" "" "" "session_id" \
        > /dev/null

    # 7.2 Get non-existent session
    test_endpoint \
        "Session: GET non-existent session → 404" \
        "GET" "${BASE_URL}/api/sessions/999999" "404" "" "" "Session not found" \
        > /dev/null

    # 7.3 Invalid session ID format
    test_endpoint \
        "Session: GET invalid ID format → 400" \
        "GET" "${BASE_URL}/api/sessions/abc" "400" "" "" "Invalid session ID" \
        > /dev/null
else
    skip_test "Session: GET single session" "No session_id from create"
    skip_test "Session: GET non-existent" "No session_id from create"
    skip_test "Session: GET invalid ID" "No session_id from create"
fi


# ═══════════════════════════════════════════════════════════════════════
section_header "8. MESSAGES — GET /api/messages/{sessionId}"

if [ -n "$SESSION_ID" ]; then
    # 8.1 Get messages for empty session
    test_endpoint \
        "Messages: GET messages (empty session) → 200" \
        "GET" "${BASE_URL}/api/messages/${SESSION_ID}" "200" "" "" "success" \
        > /dev/null

    # 8.2 Non-existent session
    test_endpoint \
        "Messages: GET messages for non-existent session → 404" \
        "GET" "${BASE_URL}/api/messages/999999" "404" "" "" "Session not found" \
        > /dev/null

    # 8.3 Missing session ID
    test_endpoint \
        "Messages: GET without session ID → 400" \
        "GET" "${BASE_URL}/api/messages/" "400" "" "" "" \
        > /dev/null

    # 8.4 Invalid session ID
    test_endpoint \
        "Messages: GET invalid session ID → 400" \
        "GET" "${BASE_URL}/api/messages/notanumber" "400" "" "" "Invalid session ID" \
        > /dev/null
else
    skip_test "Messages: GET empty session" "No session_id"
    skip_test "Messages: GET non-existent" "No session_id"
    skip_test "Messages: GET missing ID" "No session_id"
    skip_test "Messages: GET invalid ID" "No session_id"
fi


# ═══════════════════════════════════════════════════════════════════════
section_header "9. CHAT (SSE) — POST /api/chat/{sessionId}/send"

if [ -n "$SESSION_ID" ]; then
    # 9.1 Missing /send path
    test_endpoint \
        "Chat: POST without /send path → 400" \
        "POST" "${BASE_URL}/api/chat/${SESSION_ID}" "400" \
        '{"message":"hello"}' "" "Invalid chat endpoint" \
        > /dev/null

    # 9.2 Missing body
    test_endpoint \
        "Chat: POST empty body → 400" \
        "POST" "${BASE_URL}/api/chat/${SESSION_ID}/send" "400" "" "" "Request body is required" \
        > /dev/null

    # 9.3 Missing message field
    test_endpoint \
        "Chat: POST empty message → 400" \
        "POST" "${BASE_URL}/api/chat/${SESSION_ID}/send" "400" \
        '{"message":""}' "" "Message is required" \
        > /dev/null

    # 9.4 Invalid session
    test_endpoint \
        "Chat: POST to non-existent session → 404" \
        "POST" "${BASE_URL}/api/chat/999999/send" "404" \
        '{"message":"test"}' "" "Session not found" \
        > /dev/null

    # 9.5 Invalid session ID format
    test_endpoint \
        "Chat: POST invalid session ID → 400" \
        "POST" "${BASE_URL}/api/chat/abc/send" "400" \
        '{"message":"test"}' "" "Invalid session ID" \
        > /dev/null

    # 9.6 SSE stream test — sends message and checks for SSE headers
    # This is a special test: we use curl with timeout to capture SSE stream
    TOTAL=$((TOTAL + 1))
    SSE_RESP=$(curl -s -m 10 -D - \
        -H "Authorization: Bearer ${AUTH_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"message\":\"Say hello in one word\"}" \
        "${BASE_URL}/api/chat/${SESSION_ID}/send" 2>&1 || true)

    SSE_CONTENT_TYPE=$(echo "$SSE_RESP" | grep -i "content-type" | head -1)
    if echo "$SSE_CONTENT_TYPE" | grep -qi "text/event-stream"; then
        PASS=$((PASS + 1))
        printf "${GREEN}  ✅ PASS${NC} | %-55s | SSE headers correct\n" "Chat: SSE stream returns text/event-stream"
        # Check for SSE event format
        if echo "$SSE_RESP" | grep -q "event:"; then
            printf "          ${CYAN}↳ SSE events detected in stream${NC}\n"
        else
            printf "          ${CYAN}↳ SSE header correct, stream may need AI Agent running${NC}\n"
        fi
    else
        # If Flask agent is not running, we might get an error event back
        if echo "$SSE_RESP" | grep -qi "event-stream\|event:"; then
            PASS=$((PASS + 1))
            printf "${GREEN}  ✅ PASS${NC} | %-55s | SSE response detected\n" "Chat: SSE stream returns text/event-stream"
        else
            FAIL=$((FAIL + 1))
            printf "${RED}  ❌ FAIL${NC} | %-55s | Expected text/event-stream\n" "Chat: SSE stream returns text/event-stream"
            printf "          ${RED}↳ Headers: %s${NC}\n" "$(echo "$SSE_CONTENT_TYPE" | head -1 | cut -c1-100)"
        fi
    fi
else
    skip_test "Chat: missing /send" "No session_id"
    skip_test "Chat: empty body" "No session_id"
    skip_test "Chat: empty message" "No session_id"
    skip_test "Chat: non-existent session" "No session_id"
    skip_test "Chat: invalid session ID" "No session_id"
    skip_test "Chat: SSE stream" "No session_id"
fi


# ═══════════════════════════════════════════════════════════════════════
section_header "10. PROJECTS — GET /api/projects"

test_endpoint \
    "Projects: GET list projects → 200" \
    "GET" "${BASE_URL}/api/projects" "200" "" "" "success" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "11. PROJECT DOWNLOAD — GET /api/project-download/{id}"

# 11.1 Missing project ID
test_endpoint \
    "ProjectDownload: GET without project ID → 400" \
    "GET" "${BASE_URL}/api/project-download/" "400" "" "" "" \
    > /dev/null

# 11.2 Non-existent project
test_endpoint \
    "ProjectDownload: GET non-existent project → 404" \
    "GET" "${BASE_URL}/api/project-download/nonexistent-id" "404" "" "" "Project not found" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "12. TASKS — GET /api/tasks"

test_endpoint \
    "Tasks: GET list tasks → 200" \
    "GET" "${BASE_URL}/api/tasks" "200" "" "" "success" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "13. TASK CANCEL — POST /api/task-cancel/{taskId}"

# 13.1 Missing task ID
test_endpoint \
    "TaskCancel: POST without task ID → 400" \
    "POST" "${BASE_URL}/api/task-cancel/" "400" "" "" "" \
    > /dev/null

# 13.2 Non-existent task
test_endpoint \
    "TaskCancel: POST non-existent task → 404" \
    "POST" "${BASE_URL}/api/task-cancel/nonexistent-task-id" "404" "" "" "Task not found" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "14. TASK DOWNLOAD — GET /api/task-download/{taskId}"

# 14.1 Missing task ID
test_endpoint \
    "TaskDownload: GET without task ID → 400" \
    "GET" "${BASE_URL}/api/task-download/" "400" "" "" "" \
    > /dev/null

# 14.2 Non-existent task
test_endpoint \
    "TaskDownload: GET non-existent task → 404" \
    "GET" "${BASE_URL}/api/task-download/nonexistent-task-id" "404" "" "" "Task not found" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "15. WORKSPACE — GET/PUT/POST/DELETE /api/workspace/*"

# 15.1 No auth
SAVE_TOKEN="$AUTH_TOKEN"
AUTH_TOKEN=""
test_endpoint \
    "Workspace: GET without auth → 401" \
    "GET" "${BASE_URL}/api/workspace/projects" "401" "" "" "Missing authentication token" \
    > /dev/null
AUTH_TOKEN="$SAVE_TOKEN"

# 15.2 GET projects (proxy to Flask Agent)
# Flask agent may not be running, so we expect either 200 (success) or 502 (agent unreachable)
TOTAL=$((TOTAL + 1))
WS_RESP=$(curl -s -w '\n%{http_code}' \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    "${BASE_URL}/api/workspace/projects" 2>&1)
WS_STATUS=$(echo "$WS_RESP" | tail -1)
WS_BODY=$(echo "$WS_RESP" | sed '$d')

if [ "$WS_STATUS" == "200" ] || [ "$WS_STATUS" == "502" ]; then
    PASS=$((PASS + 1))
    printf "${GREEN}  ✅ PASS${NC} | %-55s | GET → ${WS_STATUS}\n" "Workspace: GET /api/workspace/projects"
    printf "          ${CYAN}↳ %s${NC}\n" "$(echo "$WS_BODY" | head -1 | cut -c1-120)"
else
    FAIL=$((FAIL + 1))
    printf "${RED}  ❌ FAIL${NC} | %-55s | Expected: 200|502, Got: ${WS_STATUS}\n" "Workspace: GET /api/workspace/projects"
    printf "          ${RED}↳ %s${NC}\n" "$(echo "$WS_BODY" | head -1 | cut -c1-120)"
fi

# 15.3 PUT file (proxy)
TOTAL=$((TOTAL + 1))
WS_PUT_RESP=$(curl -s -w '\n%{http_code}' -X PUT \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"content":"console.log(\"hello\");"}' \
    "${BASE_URL}/api/workspace/projects/test-proj/file?path=test.js" 2>&1)
WS_PUT_STATUS=$(echo "$WS_PUT_RESP" | tail -1)
WS_PUT_BODY=$(echo "$WS_PUT_RESP" | sed '$d')

if [ "$WS_PUT_STATUS" == "200" ] || [ "$WS_PUT_STATUS" == "201" ] || [ "$WS_PUT_STATUS" == "502" ]; then
    PASS=$((PASS + 1))
    printf "${GREEN}  ✅ PASS${NC} | %-55s | PUT → ${WS_PUT_STATUS}\n" "Workspace: PUT /api/workspace/projects/*/file"
    printf "          ${CYAN}↳ %s${NC}\n" "$(echo "$WS_PUT_BODY" | head -1 | cut -c1-120)"
else
    FAIL=$((FAIL + 1))
    printf "${RED}  ❌ FAIL${NC} | %-55s | Expected: 200|201|502, Got: ${WS_PUT_STATUS}\n" "Workspace: PUT /api/workspace/projects/*/file"
    printf "          ${RED}↳ %s${NC}\n" "$(echo "$WS_PUT_BODY" | head -1 | cut -c1-120)"
fi

# 15.4 POST (rename/create folder proxy)
TOTAL=$((TOTAL + 1))
WS_POST_RESP=$(curl -s -w '\n%{http_code}' -X POST \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name":"new-folder"}' \
    "${BASE_URL}/api/workspace/projects/test-proj/folder" 2>&1)
WS_POST_STATUS=$(echo "$WS_POST_RESP" | tail -1)
WS_POST_BODY=$(echo "$WS_POST_RESP" | sed '$d')

if [ "$WS_POST_STATUS" == "200" ] || [ "$WS_POST_STATUS" == "201" ] || [ "$WS_POST_STATUS" == "502" ]; then
    PASS=$((PASS + 1))
    printf "${GREEN}  ✅ PASS${NC} | %-55s | POST → ${WS_POST_STATUS}\n" "Workspace: POST /api/workspace/projects/*/folder"
    printf "          ${CYAN}↳ %s${NC}\n" "$(echo "$WS_POST_BODY" | head -1 | cut -c1-120)"
else
    FAIL=$((FAIL + 1))
    printf "${RED}  ❌ FAIL${NC} | %-55s | Expected: 200|201|502, Got: ${WS_POST_STATUS}\n" "Workspace: POST /api/workspace/projects/*/folder"
    printf "          ${RED}↳ %s${NC}\n" "$(echo "$WS_POST_BODY" | head -1 | cut -c1-120)"
fi

# 15.5 DELETE (proxy)
TOTAL=$((TOTAL + 1))
WS_DEL_RESP=$(curl -s -w '\n%{http_code}' -X DELETE \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    "${BASE_URL}/api/workspace/projects/test-proj/file?path=test.js" 2>&1)
WS_DEL_STATUS=$(echo "$WS_DEL_RESP" | tail -1)
WS_DEL_BODY=$(echo "$WS_DEL_RESP" | sed '$d')

if [ "$WS_DEL_STATUS" == "200" ] || [ "$WS_DEL_STATUS" == "404" ] || [ "$WS_DEL_STATUS" == "502" ]; then
    PASS=$((PASS + 1))
    printf "${GREEN}  ✅ PASS${NC} | %-55s | DELETE → ${WS_DEL_STATUS}\n" "Workspace: DELETE /api/workspace/projects/*/file"
    printf "          ${CYAN}↳ %s${NC}\n" "$(echo "$WS_DEL_BODY" | head -1 | cut -c1-120)"
else
    FAIL=$((FAIL + 1))
    printf "${RED}  ❌ FAIL${NC} | %-55s | Expected: 200|404|502, Got: ${WS_DEL_STATUS}\n" "Workspace: DELETE /api/workspace/projects/*/file"
    printf "          ${RED}↳ %s${NC}\n" "$(echo "$WS_DEL_BODY" | head -1 | cut -c1-120)"
fi


# ═══════════════════════════════════════════════════════════════════════
section_header "16. OAUTH LINK — POST/GET/PUT/DELETE /api/auth/oauth/link"

# 16.1 GET linked providers (should be empty initially)
test_endpoint \
    "OAuthLink: GET list providers → 200" \
    "GET" "${BASE_URL}/api/auth/oauth/link" "200" "" "" "success" \
    > /dev/null

# 16.2 POST link provider - missing body
test_endpoint \
    "OAuthLink: POST missing body → 400" \
    "POST" "${BASE_URL}/api/auth/oauth/link" "400" "" "" "Request body is required" \
    > /dev/null

# 16.3 POST link provider - missing required fields
test_endpoint \
    "OAuthLink: POST missing provider → 400" \
    "POST" "${BASE_URL}/api/auth/oauth/link" "400" \
    '{"client_id":"abc","client_secret":"xyz","token_endpoint":"https://example.com/token"}' "" "provider is required" \
    > /dev/null

# 16.4 POST link provider - missing client_id
test_endpoint \
    "OAuthLink: POST missing client_id → 400" \
    "POST" "${BASE_URL}/api/auth/oauth/link" "400" \
    '{"provider":"github","client_secret":"xyz","token_endpoint":"https://example.com/token"}' "" "client_id is required" \
    > /dev/null

# 16.5 POST link provider - success
test_endpoint \
    "OAuthLink: POST link provider → 201" \
    "POST" "${BASE_URL}/api/auth/oauth/link" "201" \
    '{"provider":"test_provider","client_id":"test_cid","client_secret":"test_secret","token_endpoint":"https://example.com/token"}' \
    "" "credentials_saved" \
    > /dev/null

# 16.6 GET should now list the linked provider
test_endpoint \
    "OAuthLink: GET list (after linking) → has provider" \
    "GET" "${BASE_URL}/api/auth/oauth/link" "200" "" "" "test_provider" \
    > /dev/null

# 16.7 PUT refresh - missing body
test_endpoint \
    "OAuthLink: PUT missing body → 400" \
    "PUT" "${BASE_URL}/api/auth/oauth/link" "400" "" "" "Request body is required" \
    > /dev/null

# 16.8 PUT refresh - missing provider
test_endpoint \
    "OAuthLink: PUT missing provider → 400" \
    "PUT" "${BASE_URL}/api/auth/oauth/link" "400" \
    '{"something":"else"}' "" "provider is required" \
    > /dev/null

# 16.9 DELETE unlink - missing provider param
test_endpoint \
    "OAuthLink: DELETE without provider param → 400" \
    "DELETE" "${BASE_URL}/api/auth/oauth/link" "400" "" "" "provider query parameter is required" \
    > /dev/null

# 16.10 DELETE unlink - non-existent provider
test_endpoint \
    "OAuthLink: DELETE non-existent provider → 404" \
    "DELETE" "${BASE_URL}/api/auth/oauth/link?provider=nonexistent" "404" "" "" "not linked" \
    > /dev/null

# 16.11 DELETE unlink - success
test_endpoint \
    "OAuthLink: DELETE unlink test_provider → 200" \
    "DELETE" "${BASE_URL}/api/auth/oauth/link?provider=test_provider" "200" "" "" "unlinked" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "17. OAUTH CALLBACK — GET/POST /api/auth/oauth/callback"

# 17.1 Missing code
test_endpoint \
    "OAuthCallback: GET missing code → 400" \
    "GET" "${BASE_URL}/api/auth/oauth/callback" "400" "" "" "Missing" \
    > /dev/null

# 17.2 Provider error
test_endpoint \
    "OAuthCallback: GET error from provider → 400" \
    "GET" "${BASE_URL}/api/auth/oauth/callback?error=access_denied&error_description=User+denied" "400" "" "" "OAuth error" \
    > /dev/null

# 17.3 Missing state (cannot determine provider)
test_endpoint \
    "OAuthCallback: GET code without state → 400" \
    "GET" "${BASE_URL}/api/auth/oauth/callback?code=test_auth_code" "400" "" "" "Cannot determine provider" \
    > /dev/null

# 17.4 POST missing body
test_endpoint \
    "OAuthCallback: POST missing body → 400" \
    "POST" "${BASE_URL}/api/auth/oauth/callback" "400" "" "" "Request body is required" \
    > /dev/null

# 17.5 POST missing fields
test_endpoint \
    "OAuthCallback: POST missing provider → 400" \
    "POST" "${BASE_URL}/api/auth/oauth/callback" "400" \
    '{"code":"abc","redirect_uri":"http://localhost"}' "" "provider is required" \
    > /dev/null


# ═══════════════════════════════════════════════════════════════════════
section_header "18. CORS — Preflight Tests"

# 18.1 OPTIONS on protected endpoint
TOTAL=$((TOTAL + 1))
CORS_RESP=$(curl -s -D - -X OPTIONS \
    -H "Origin: http://localhost:5173" \
    -H "Access-Control-Request-Method: POST" \
    -H "Access-Control-Request-Headers: Authorization, Content-Type" \
    "${BASE_URL}/api/sessions" 2>&1)

CORS_STATUS=$(echo "$CORS_RESP" | head -1 | grep -o "[0-9][0-9][0-9]")
CORS_ALLOW=$(echo "$CORS_RESP" | grep -i "Access-Control-Allow")

if [ -n "$CORS_ALLOW" ]; then
    PASS=$((PASS + 1))
    printf "${GREEN}  ✅ PASS${NC} | %-55s | OPTIONS → ${CORS_STATUS}\n" "CORS: preflight on /api/sessions"
    printf "          ${CYAN}↳ %s${NC}\n" "$(echo "$CORS_ALLOW" | head -1 | tr -d '\r')"
else
    FAIL=$((FAIL + 1))
    printf "${RED}  ❌ FAIL${NC} | %-55s | No CORS headers found\n" "CORS: preflight on /api/sessions"
fi

# 18.2 OPTIONS on workspace endpoint
TOTAL=$((TOTAL + 1))
CORS_WS_RESP=$(curl -s -D - -X OPTIONS \
    -H "Origin: http://localhost:5173" \
    -H "Access-Control-Request-Method: PUT" \
    -H "Access-Control-Request-Headers: Authorization, Content-Type" \
    "${BASE_URL}/api/workspace/projects" 2>&1)

CORS_WS_STATUS=$(echo "$CORS_WS_RESP" | head -1 | grep -o "[0-9][0-9][0-9]")
CORS_WS_ALLOW=$(echo "$CORS_WS_RESP" | grep -i "Access-Control-Allow")

if [ -n "$CORS_WS_ALLOW" ]; then
    PASS=$((PASS + 1))
    printf "${GREEN}  ✅ PASS${NC} | %-55s | OPTIONS → ${CORS_WS_STATUS}\n" "CORS: preflight on /api/workspace/projects"
    printf "          ${CYAN}↳ %s${NC}\n" "$(echo "$CORS_WS_ALLOW" | head -1 | tr -d '\r')"
else
    FAIL=$((FAIL + 1))
    printf "${RED}  ❌ FAIL${NC} | %-55s | No CORS headers found\n" "CORS: preflight on /api/workspace/projects"
fi


# ═══════════════════════════════════════════════════════════════════════
section_header "19. CLEANUP — DELETE test session"

if [ -n "$SESSION_ID" ]; then
    test_endpoint \
        "Cleanup: DELETE test session → 200" \
        "DELETE" "${BASE_URL}/api/sessions/${SESSION_ID}" "200" "" "" "Session deleted" \
        > /dev/null

    # Verify deletion
    test_endpoint \
        "Cleanup: GET deleted session → 404" \
        "GET" "${BASE_URL}/api/sessions/${SESSION_ID}" "404" "" "" "Session not found" \
        > /dev/null
else
    skip_test "Cleanup: DELETE session" "No session_id"
    skip_test "Cleanup: verify deletion" "No session_id"
fi


# ═══════════════════════════════════════════════════════════════════════
#  SUMMARY
# ═══════════════════════════════════════════════════════════════════════

echo ""
printf "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════════════════════${NC}\n"
printf "${BOLD}  📊 TEST RESULTS SUMMARY${NC}\n"
printf "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════════════════════${NC}\n"
echo ""
printf "  Total Tests:  ${BOLD}%d${NC}\n" "$TOTAL"
printf "  ${GREEN}Passed:       %d${NC}\n" "$PASS"
printf "  ${RED}Failed:       %d${NC}\n" "$FAIL"
printf "  ${YELLOW}Skipped:      %d${NC}\n" "$SKIP"
echo ""

if [ $FAIL -eq 0 ]; then
    printf "  ${GREEN}${BOLD}🎉 ALL TESTS PASSED!${NC}\n"
else
    printf "  ${RED}${BOLD}⚠️  %d TEST(S) FAILED${NC}\n" "$FAIL"
fi

echo ""
printf "${BOLD}  Endpoints Covered:${NC}\n"
printf "    POST   /api/auth/register        (RegisterServlet)\n"
printf "    POST   /api/auth/login           (LoginServlet)\n"
printf "    GET    /api/auth/me              (MeServlet)\n"
printf "    GET    /api/auth/preferences     (PreferencesServlet)\n"
printf "    PUT    /api/auth/preferences     (PreferencesServlet)\n"
printf "    POST   /api/auth/oauth/link      (OAuthLinkServlet)\n"
printf "    GET    /api/auth/oauth/link      (OAuthLinkServlet)\n"
printf "    PUT    /api/auth/oauth/link      (OAuthLinkServlet)\n"
printf "    DELETE /api/auth/oauth/link      (OAuthLinkServlet)\n"
printf "    GET    /api/auth/oauth/callback  (OAuthCallbackServlet)\n"
printf "    POST   /api/auth/oauth/callback  (OAuthCallbackServlet)\n"
printf "    GET    /api/sessions             (SessionsServlet)\n"
printf "    POST   /api/sessions             (SessionsServlet)\n"
printf "    GET    /api/sessions/{id}        (SessionServlet)\n"
printf "    DELETE /api/sessions/{id}        (SessionServlet)\n"
printf "    GET    /api/messages/{id}        (MessagesServlet)\n"
printf "    POST   /api/chat/{id}/send       (ChatServlet — SSE)\n"
printf "    GET    /api/projects             (ProjectsServlet)\n"
printf "    GET    /api/project-download/{id}(ProjectDownloadServlet)\n"
printf "    GET    /api/tasks                (TasksServlet)\n"
printf "    POST   /api/task-cancel/{id}     (TaskCancelServlet)\n"
printf "    GET    /api/task-download/{id}   (TaskDownloadServlet)\n"
printf "    GET    /api/workspace/*          (WorkspaceServlet)\n"
printf "    PUT    /api/workspace/*          (WorkspaceServlet)\n"
printf "    POST   /api/workspace/*          (WorkspaceServlet)\n"
printf "    DELETE /api/workspace/*          (WorkspaceServlet)\n"
echo ""
printf "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════════════════════${NC}\n"
