'use strict';

/* 聊天式前端：左侧会话列表 + 中央对话（消息气泡，流式逐字出），管理功能在侧边栏切换视图。
   登录 / 上传 / 清单 / 用户管理仍走后端 /api/**。样式底子是 Tabler，布局见 styles.css。 */

const TOKEN_KEY = 'kb.token';
const REFRESH_KEY = 'kb.refreshToken';
const USER_KEY = 'kb.user';
const SESSIONS_KEY = 'kb.sessions';
const DOCS_PAGE_SIZE = 20;
const MAX_STORED_MESSAGES = 100;

const $ = (id) => document.getElementById(id);
const messageEl = $('message');

let currentUser = null;
let docsPage = 0;
let auditPage = 0;
let notifPage = 0;
let asking = false;
let currentSessionId = null;

function showMessage(text) {
    messageEl.textContent = text;
    messageEl.hidden = !text;
}

/** 带 Token 的请求；401 时先用 Refresh Token 换一对新 Token 再重试一次，还不行才回登录页。 */
async function api(path, { method = 'GET', body = null, isForm = false } = {}) {
    let response = await doApi(path, method, body, isForm);
    if (response.status === 401 && await tryRefresh()) {
        response = await doApi(path, method, body, isForm);
    }
    if (response.status === 401) {
        logout('登录已过期，请重新登录');
        throw new Error('未登录');
    }
    if (!response.ok) {
        let detail = '请求失败（' + response.status + '）';
        try {
            const err = await response.json();
            if (err.message) {
                detail = err.message;
            }
        } catch (e) {
            // 错误体不是 JSON 时用状态码兜底
        }
        throw new Error(detail);
    }
    return response.json();
}

function doApi(path, method, body, isForm) {
    const headers = {};
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) {
        headers.Authorization = 'Bearer ' + token;
    }
    let payload = body;
    if (payload && !isForm) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(payload);
    }
    return fetch(path, { method, headers, body: payload });
}

/** 用 Refresh Token 换一对新 Token；成功返回 true，失败返回 false（不抛错，调用方决定回登录页）。 */
async function tryRefresh() {
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    if (!refreshToken) {
        return false;
    }
    try {
        const response = await fetch('/api/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken }),
        });
        if (!response.ok) {
            return false;
        }
        saveSession(await response.json());
        return true;
    } catch (e) {
        return false;
    }
}

function saveSession(login) {
    localStorage.setItem(TOKEN_KEY, login.token);
    if (login.refreshToken) {
        localStorage.setItem(REFRESH_KEY, login.refreshToken);
    }
    localStorage.setItem(USER_KEY, JSON.stringify(login));
    currentUser = login;
}

/** clearChat 只在主动点退出时清本地对话记录；Token 过期回登录页时保留。 */
function logout(message, clearChat = false) {
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    if (refreshToken) {
        fetch('/api/auth/logout', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken }),
        }).catch(() => {});
    }
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
    if (clearChat) {
        const sessions = loadSessions();
        sessions.forEach((s) => localStorage.removeItem(messagesKey(s.id)));
        localStorage.removeItem(SESSIONS_KEY);
        currentSessionId = null;
    }
    currentUser = null;
    $('appView').hidden = true;
    $('loginView').hidden = false;
    showMessage(message || '');
}

function showApp() {
    $('loginView').hidden = true;
    $('appView').hidden = false;
    $('userInfo').textContent = currentUser.username + '（' + currentUser.role + '）';
    $('userAvatar').textContent = currentUser.username.charAt(0).toUpperCase();
    const deptBadge = $('deptBadge');
    if (currentUser.department) {
        deptBadge.hidden = false;
        deptBadge.textContent = currentUser.department;
    } else {
        deptBadge.hidden = true;
    }
    $('adminNavBtn').hidden = !(currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN');
    $('approvalNavBtn').hidden = !(currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN');
    $('deptNavBtn').hidden = currentUser.role !== 'ADMIN';
    $('auditNavBtn').hidden = currentUser.role !== 'ADMIN';
    $('apikeysNavBtn').hidden = currentUser.role !== 'ADMIN';
    ensureSession();
    showView('dashboard'); // 切到总览时自动刷新统计
    loadDocuments(0);
    refreshNotifBadge();
    if (currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN') {
        loadUsers();
    }
    if (currentUser.role === 'ADMIN') {
        loadDepartments();
        loadAuditLogs(0);
    }
}

/** 总览统计全部来自真实接口：文档总数与块数来自 /api/documents，用户数来自 /api/admin/users。 */
async function loadDashboard() {
    $('statDept').textContent = currentUser.department || '全部';
    try {
        const data = await api('/api/documents?page=0&size=100');
        $('statDocs').textContent = data.total;
        const chunks = (data.documents || []).reduce((sum, d) => sum + d.chunks, 0);
        $('statChunks').textContent = data.total > (data.documents || []).length ? chunks + '+' : chunks;
        const tbody = $('recentDocsTable').querySelector('tbody');
        tbody.textContent = '';
        (data.documents || []).slice(0, 5).forEach((doc) => {
            const row = document.createElement('tr');
            const source = document.createElement('td');
            source.textContent = doc.source;
            const chunkCell = document.createElement('td');
            chunkCell.textContent = doc.chunks;
            const visibility = document.createElement('td');
            visibility.textContent = doc.visibility;
            row.append(source, chunkCell, visibility);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
    if (currentUser.role === 'ADMIN') {
        try {
            const users = await api('/api/admin/users');
            $('statUsersCard').hidden = false;
            $('statUsers').textContent = users.length;
        } catch (e) {
            // 用户数拿不到不影响总览其余内容
        }
    }
    if (currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN') {
        try {
            const pending = await api('/api/documents?status=pending&page=0&size=1');
            $('statPendingCard').hidden = false;
            $('statPending').textContent = pending.total;
        } catch (e) {
            // 待审批数拿不到不影响总览其余内容
        }
    }
    if (currentUser.role === 'ADMIN') {
        try {
            const stats = await api('/api/admin/feedback/stats');
            $('statFeedbackCard').hidden = false;
            $('statFeedback').textContent = stats.total === 0
                ? '—' : Math.round(stats.helpfulRate * 100) + '%';
        } catch (e) {
            // 好评率拿不到不影响总览其余内容
        }
    }
}

/* ---------------- 会话（只存本浏览器） ---------------- */

function messagesKey(sessionId) {
    return 'kb.messages.' + sessionId;
}

function loadSessions() {
    try {
        return JSON.parse(localStorage.getItem(SESSIONS_KEY) || '[]');
    } catch (e) {
        return [];
    }
}

function saveSessions(sessions) {
    localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));
}

function newSessionId() {
    return 'web-' + Math.random().toString(36).slice(2, 10);
}

function createSession() {
    const sessions = loadSessions();
    const session = { id: newSessionId(), title: '新对话', updatedAt: Date.now() };
    sessions.unshift(session);
    saveSessions(sessions);
    return session;
}

function ensureSession() {
    const sessions = loadSessions();
    if (sessions.length === 0) {
        currentSessionId = createSession().id;
    } else {
        currentSessionId = sessions[0].id;
    }
    renderSessionList();
    renderMessages();
}

function switchSession(id) {
    currentSessionId = id;
    renderSessionList();
    renderMessages();
    showView('chat');
}

function deleteSession(id, event) {
    event.stopPropagation();
    const sessions = loadSessions().filter((s) => s.id !== id);
    saveSessions(sessions);
    localStorage.removeItem(messagesKey(id));
    if (currentSessionId === id) {
        currentSessionId = sessions.length > 0 ? sessions[0].id : createSession().id;
    }
    renderSessionList();
    renderMessages();
}

function renderSessionList() {
    const list = $('sessionList');
    list.textContent = '';
    loadSessions().forEach((session) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'session-item' + (session.id === currentSessionId ? ' active' : '');
        const title = document.createElement('span');
        title.className = 'session-title';
        title.textContent = session.title;
        const del = document.createElement('span');
        del.className = 'session-del';
        del.textContent = '×';
        del.addEventListener('click', (e) => deleteSession(session.id, e));
        item.append(title, del);
        item.addEventListener('click', () => switchSession(session.id));
        list.appendChild(item);
    });
}

function loadMessages(sessionId) {
    try {
        return JSON.parse(localStorage.getItem(messagesKey(sessionId)) || '[]');
    } catch (e) {
        return [];
    }
}

function storeMessage(sessionId, message) {
    const messages = loadMessages(sessionId);
    messages.push(message);
    localStorage.setItem(messagesKey(sessionId), JSON.stringify(messages.slice(-MAX_STORED_MESSAGES)));
}

/** 首问给会话起标题（只起一次）。 */
function nameSessionIfUntitled(question) {
    const sessions = loadSessions();
    const session = sessions.find((s) => s.id === currentSessionId);
    if (session && session.title === '新对话') {
        session.title = question.length > 18 ? question.slice(0, 18) + '…' : question;
        session.updatedAt = Date.now();
        saveSessions(sessions);
        renderSessionList();
    }
}

/* ---------------- 消息气泡 ---------------- */

function scrollMessagesToBottom() {
    const container = $('messages');
    container.scrollTop = container.scrollHeight;
}

function messageElement(message) {
    const wrap = document.createElement('div');
    wrap.className = 'msg msg-' + message.role;
    if (message.question) {
        wrap.dataset.question = message.question;
    }
    const bubble = document.createElement('div');
    bubble.className = 'msg-bubble';
    const text = document.createElement('div');
    text.className = 'msg-text';
    text.textContent = message.text;
    bubble.appendChild(text);
    if (message.role === 'assistant' && message.hits && message.hits.length > 0) {
        const hits = document.createElement('div');
        renderHitsInto(hits, message.hits);
        bubble.appendChild(hits);
    }
    if (message.role === 'assistant') {
        const feedback = document.createElement('div');
        feedback.className = 'feedback-row';
        const up = document.createElement('button');
        up.type = 'button';
        up.className = 'btn btn-outline-secondary btn-sm';
        up.textContent = '👍 有用';
        up.addEventListener('click', () => submitFeedback(wrap, up, true));
        const down = document.createElement('button');
        down.type = 'button';
        down.className = 'btn btn-outline-secondary btn-sm';
        down.textContent = '👎 没用';
        down.addEventListener('click', () => submitFeedback(wrap, down, false));
        feedback.append(up, down);
        bubble.appendChild(feedback);
    }
    wrap.appendChild(bubble);
    return wrap;
}

/** 回答反馈：按原问答提交好评/差评，提交后两个按钮都禁用。 */
async function submitFeedback(wrap, button, helpful) {
    const question = wrap.dataset.question || '';
    if (!question) {
        return;
    }
    const answer = wrap.querySelector('.msg-text').textContent;
    try {
        await api('/api/feedback', { method: 'POST', body: { question, answer, helpful } });
        wrap.querySelectorAll('.feedback-row button').forEach((b) => { b.disabled = true; });
        button.classList.add('btn-primary');
        button.classList.remove('btn-outline-secondary');
    } catch (e) {
        showMessage(e.message);
    }
}

function renderMessages() {
    const container = $('messages');
    container.textContent = '';
    const messages = loadMessages(currentSessionId);
    if (messages.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'chat-empty';
        empty.textContent = '在下面输入问题开始对话，回答会带资料来源引用。';
        container.appendChild(empty);
        return;
    }
    messages.forEach((m) => container.appendChild(messageElement(m)));
    scrollMessagesToBottom();
}

function renderHitsInto(container, hits) {
    container.textContent = '';
    (hits || []).forEach((hit) => {
        const block = document.createElement('div');
        block.className = 'hit';
        const meta = document.createElement('div');
        meta.className = 'hit-meta';
        meta.textContent = '[' + hit.index + '] ' + hit.source + ' · 综合分 ' + hit.score.toFixed(3);
        const text = document.createElement('div');
        text.className = 'hit-text';
        text.textContent = hit.text;
        block.append(meta, text);
        container.appendChild(block);
    });
}

/* ---------------- 问答 ---------------- */

function buildAskRequest(question) {
    const request = {
        question,
        sessionId: currentSessionId,
    };
    const department = $('askDepartment').value.trim();
    if (department) {
        request.department = department;
    }
    const sources = $('askSources').value.split(',').map((s) => s.trim()).filter(Boolean);
    if (sources.length > 0) {
        request.sources = sources;
    }
    return request;
}

async function askOnce(request, bubble) {
    const response = await api('/api/ask', { method: 'POST', body: request });
    bubble.querySelector('.msg-text').textContent = response.answer;
    renderHitsInto(bubble.querySelector('.hits') || appendHitsContainer(bubble), response.hits);
    scrollMessagesToBottom();
    return response;
}

function fetchAskStream(request) {
    const token = localStorage.getItem(TOKEN_KEY);
    return fetch('/api/ask-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(request),
    });
}

function appendHitsContainer(bubble) {
    const hits = document.createElement('div');
    bubble.appendChild(hits);
    return hits;
}

/** 手写 SSE 解析：fetch 不能用 EventSource（POST + Authorization 头只能走 fetch）。 */
async function askStream(request, bubble) {
    let response = await fetchAskStream(request);
    if (response.status === 401 && await tryRefresh()) {
        response = await fetchAskStream(request);
    }
    if (response.status === 401) {
        logout('登录已过期，请重新登录');
        throw new Error('未登录');
    }
    if (!response.ok) {
        const err = await response.json().catch(() => ({}));
        throw new Error(err.message || '请求失败（' + response.status + '）');
    }
    const textEl = bubble.querySelector('.msg-text');
    const hitsEl = appendHitsContainer(bubble);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let answer = '';
    let hits = [];
    for (;;) {
        const { done, value } = await reader.read();
        if (done) {
            break;
        }
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split('\n\n');
        buffer = events.pop();
        for (const raw of events) {
            let event = '';
            const dataLines = [];
            for (const line of raw.split('\n')) {
                if (line.startsWith('event:')) {
                    event = line.slice(6).trim();
                } else if (line.startsWith('data:')) {
                    dataLines.push(line.slice(5).replace(/^ /, ''));
                }
            }
            const data = dataLines.join('\n');
            if (event === 'sources') {
                hits = JSON.parse(data);
                renderHitsInto(hitsEl, hits);
            } else if (event === 'token') {
                answer += data;
                textEl.textContent = answer;
            }
            scrollMessagesToBottom();
        }
    }
    return { answer, hits };
}

/* ---------------- 视图切换 ---------------- */

const VIEW_TITLES = { dashboard: '总览', chat: '智能问答', manage: '文档管理', notifications: '通知',
    approval: '审批', departments: '部门管理', audit: '审计日志', admin: '用户管理',
    apikeys: 'API 密钥', password: '改密码' };

function showView(name) {
    $('dashboardView').hidden = name !== 'dashboard';
    $('chatView').hidden = name !== 'chat';
    $('manageView').hidden = name !== 'manage';
    $('notificationsView').hidden = name !== 'notifications';
    $('approvalView').hidden = name !== 'approval';
    $('departmentsView').hidden = name !== 'departments';
    $('auditView').hidden = name !== 'audit';
    $('adminView').hidden = name !== 'admin';
    $('apikeysView').hidden = name !== 'apikeys';
    $('passwordView').hidden = name !== 'password';
    document.querySelectorAll('.sidebar-link').forEach((link) => {
        link.classList.toggle('active', link.dataset.view === name);
    });
    $('viewTitle').textContent = VIEW_TITLES[name] || '总览';
    $('appView').classList.remove('sidebar-open');
    if (name === 'dashboard') {
        loadDashboard();
    } else if (name === 'notifications') {
        loadNotifications(notifPage);
    } else if (name === 'approval') {
        loadPending();
    } else if (name === 'departments') {
        loadDepartments();
    } else if (name === 'audit') {
        loadAuditLogs(auditPage);
    } else if (name === 'apikeys') {
        loadApiKeys();
    }
}

/* ---------------- 文档清单 / 上传 / 用户 ---------------- */

async function loadDocuments(page) {
    docsPage = page;
    try {
        const data = await api('/api/documents?page=' + page + '&size=' + DOCS_PAGE_SIZE);
        const tbody = $('docsTable').querySelector('tbody');
        tbody.textContent = '';
        (data.documents || []).forEach((doc) => {
            const row = document.createElement('tr');
            const source = document.createElement('td');
            source.textContent = doc.source;
            const chunks = document.createElement('td');
            chunks.textContent = doc.chunks;
            const visibility = document.createElement('td');
            visibility.textContent = doc.visibility;
            const status = document.createElement('td');
            status.textContent = doc.status === 'pending' ? '待审批' : doc.status === 'rejected' ? '已驳回' : '已发布';
            const version = document.createElement('td');
            version.textContent = 'v' + doc.version;
            const action = document.createElement('td');
            const canDelete = currentUser && (currentUser.role === 'ADMIN'
                || (currentUser.role === 'DEPT_ADMIN' && currentUser.department === doc.visibility));
            if (canDelete) {
                const del = document.createElement('button');
                del.type = 'button';
                del.className = 'btn btn-outline-danger btn-sm';
                del.textContent = '删除';
                del.addEventListener('click', () => deleteDocument(doc.source));
                action.appendChild(del);
            }
            const canApprove = currentUser && (currentUser.role === 'ADMIN'
                || (currentUser.role === 'DEPT_ADMIN' && currentUser.department === doc.visibility));
            if (canApprove && doc.version > 1) {
                const rollback = document.createElement('button');
                rollback.type = 'button';
                rollback.className = 'btn btn-outline-secondary btn-sm ms-1';
                rollback.textContent = '回滚';
                rollback.addEventListener('click', () => rollbackDocument(doc.source, doc.version));
                action.appendChild(rollback);
            }
            row.append(source, chunks, visibility, status, version, action);
            tbody.appendChild(row);
        });
        const totalPages = Math.max(1, Math.ceil(data.total / DOCS_PAGE_SIZE));
        $('pageInfo').textContent = '第 ' + (page + 1) + ' / ' + totalPages + ' 页，共 ' + data.total + ' 篇';
        $('prevPageBtn').disabled = page <= 0;
        $('nextPageBtn').disabled = (page + 1) * DOCS_PAGE_SIZE >= data.total;
    } catch (e) {
        showMessage(e.message);
    }
}

async function deleteDocument(source) {
    if (!window.confirm('确定删除「' + source + '」吗？')) {
        return;
    }
    try {
        const result = await api('/api/document?source=' + encodeURIComponent(source), { method: 'DELETE' });
        showMessage('已删除「' + source + '」，共 ' + result.deletedChunks + ' 块');
        loadDocuments(docsPage);
    } catch (e) {
        showMessage(e.message);
    }
}

/** 回滚：先取全部版本号，只剩当前版时没得回；让用户在旧版里挑一个，回滚后刷新清单。 */
async function rollbackDocument(source, currentVersion) {
    try {
        const data = await api('/api/document/versions?source=' + encodeURIComponent(source));
        const oldVersions = (data.versions || []).filter((v) => v < currentVersion);
        if (oldVersions.length === 0) {
            showMessage('「' + source + '」没有可回滚的旧版');
            return;
        }
        const input = window.prompt('可回滚到：v' + oldVersions.join('、v') + '\n输入版本号：',
            String(oldVersions[oldVersions.length - 1]));
        if (input === null) {
            return;
        }
        const version = Number.parseInt(input, 10);
        if (!oldVersions.includes(version)) {
            showMessage('版本号不对，只能选 v' + oldVersions.join('、v'));
            return;
        }
        if (!window.confirm('确定把「' + source + '」回滚到 v' + version + ' 吗？当前版将被替换。')) {
            return;
        }
        const result = await api('/api/document/rollback?source=' + encodeURIComponent(source)
            + '&version=' + version, { method: 'POST' });
        showMessage('已回滚「' + source + '」到 v' + result.version + '，共 ' + result.chunks + ' 块');
        loadDocuments(docsPage);
    } catch (e) {
        showMessage(e.message);
    }
}

async function loadUsers() {
    try {
        const users = await api('/api/admin/users');
        const tbody = $('usersTable').querySelector('tbody');
        tbody.textContent = '';
        users.forEach((user) => {
            const row = document.createElement('tr');
            const name = document.createElement('td');
            name.textContent = user.username;
            const role = document.createElement('td');
            role.textContent = user.role;
            const department = document.createElement('td');
            department.textContent = user.department || '—';
            const created = document.createElement('td');
            created.textContent = user.createdAt ? user.createdAt.slice(0, 19).replace('T', ' ') : '—';
            row.append(name, role, department, created);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

/* ---------------- 通知 ---------------- */

async function loadNotifications(page) {
    notifPage = page;
    try {
        const data = await api('/api/notifications?page=' + page + '&size=' + DOCS_PAGE_SIZE);
        const listEl = $('notifList');
        listEl.textContent = '';
        (data.notifications || []).forEach((n) => {
            const item = document.createElement('li');
            item.className = 'list-group-item' + (n.read ? '' : ' list-group-item-warning');
            if (!n.read) {
                item.style.cursor = 'pointer';
            }
            const title = document.createElement('div');
            title.textContent = (n.read ? '' : '● ') + n.title;
            const body = document.createElement('div');
            body.className = 'text-secondary';
            body.textContent = n.body || '';
            const time = document.createElement('div');
            time.className = 'text-secondary';
            time.textContent = n.createdAt ? n.createdAt.slice(0, 19).replace('T', ' ') : '';
            item.append(title, body, time);
            if (!n.read) {
                item.addEventListener('click', async () => {
                    await api('/api/notifications/' + n.id + '/read', { method: 'POST' });
                    loadNotifications(notifPage);
                });
            }
            listEl.appendChild(item);
        });
        const totalPages = Math.max(1, Math.ceil(data.total / DOCS_PAGE_SIZE));
        $('notifPageInfo').textContent = '第 ' + (page + 1) + ' / ' + totalPages + ' 页，共 ' + data.total + ' 条，未读 ' + data.unread + ' 条';
        $('prevNotifBtn').disabled = page <= 0;
        $('nextNotifBtn').disabled = (page + 1) * DOCS_PAGE_SIZE >= data.total;
        refreshNotifBadge();
    } catch (e) {
        showMessage(e.message);
    }
}

/** 侧栏通知角标：只读未读数，失败时静默（不打扰主流程）。 */
async function refreshNotifBadge() {
    try {
        const data = await api('/api/notifications?page=0&size=1');
        const badge = $('notifBadge');
        badge.hidden = !data.unread;
        badge.textContent = data.unread > 99 ? '99+' : String(data.unread);
    } catch (e) {
        // 忽略
    }
}

/* ---------------- API 密钥 ---------------- */

async function loadApiKeys() {
    try {
        const keys = await api('/api/admin/api-keys');
        const tbody = $('apiKeysTable').querySelector('tbody');
        tbody.textContent = '';
        keys.forEach((k) => {
            const row = document.createElement('tr');
            const name = document.createElement('td');
            name.textContent = k.name;
            const owner = document.createElement('td');
            owner.textContent = k.username;
            const scope = document.createElement('td');
            scope.textContent = k.scope === 'ask' ? '仅问答' : '全部';
            const expires = document.createElement('td');
            expires.textContent = k.expiresAt ? k.expiresAt.slice(0, 19).replace('T', ' ') : '永久';
            const created = document.createElement('td');
            created.textContent = k.createdAt ? k.createdAt.slice(0, 19).replace('T', ' ') : '—';
            const lastUsed = document.createElement('td');
            lastUsed.textContent = k.lastUsedAt ? k.lastUsedAt.slice(0, 19).replace('T', ' ') : '从未使用';
            const status = document.createElement('td');
            status.textContent = k.revoked ? '已吊销'
                : (k.expiresAt && new Date(k.expiresAt) <= new Date() ? '已过期' : '有效');
            const action = document.createElement('td');
            if (!k.revoked) {
                const revoke = document.createElement('button');
                revoke.type = 'button';
                revoke.className = 'btn btn-outline-danger btn-sm';
                revoke.textContent = '吊销';
                revoke.addEventListener('click', () => revokeApiKey(k.id, k.name));
                action.appendChild(revoke);
            }
            row.append(name, owner, scope, expires, created, lastUsed, status, action);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

async function revokeApiKey(id, name) {
    if (!window.confirm('确定吊销密钥「' + name + '」吗？吊销后用它调接口会报 401。')) {
        return;
    }
    try {
        await api('/api/admin/api-keys/' + id, { method: 'DELETE' });
        showMessage('密钥「' + name + '」已吊销');
        loadApiKeys();
    } catch (e) {
        showMessage(e.message);
    }
}

/* ---------------- 审批 / 部门 / 审计 ---------------- */

async function loadPending() {
    try {
        const data = await api('/api/documents?status=pending&page=0&size=100');
        const tbody = $('approvalTable').querySelector('tbody');
        tbody.textContent = '';
        (data.documents || []).forEach((doc) => {
            const row = document.createElement('tr');
            const source = document.createElement('td');
            source.textContent = doc.source;
            const chunks = document.createElement('td');
            chunks.textContent = doc.chunks;
            const visibility = document.createElement('td');
            visibility.textContent = doc.visibility;
            const version = document.createElement('td');
            version.textContent = 'v' + doc.version;
            const action = document.createElement('td');
            const approve = document.createElement('button');
            approve.type = 'button';
            approve.className = 'btn btn-primary btn-sm me-1';
            approve.textContent = '批准';
            approve.addEventListener('click', () => reviewDocument(doc.source, 'approve'));
            const reject = document.createElement('button');
            reject.type = 'button';
            reject.className = 'btn btn-outline-danger btn-sm';
            reject.textContent = '驳回';
            reject.addEventListener('click', () => reviewDocument(doc.source, 'reject'));
            action.append(approve, reject);
            row.append(source, chunks, visibility, version, action);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

async function reviewDocument(source, decision) {
    try {
        await api('/api/document/' + decision + '?source=' + encodeURIComponent(source), { method: 'POST' });
        showMessage('「' + source + '」已' + (decision === 'approve' ? '批准发布' : '驳回'));
        loadPending();
        loadDocuments(docsPage);
    } catch (e) {
        showMessage(e.message);
    }
}

async function loadDepartments() {
    try {
        const departments = await api('/api/admin/departments');
        const tbody = $('departmentsTable').querySelector('tbody');
        tbody.textContent = '';
        departments.forEach((dept) => {
            const row = document.createElement('tr');
            const code = document.createElement('td');
            code.textContent = dept.code;
            const name = document.createElement('td');
            name.textContent = dept.name;
            const parent = document.createElement('td');
            parent.textContent = dept.parentCode || '—';
            const action = document.createElement('td');
            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'btn btn-outline-danger btn-sm';
            del.textContent = '删除';
            del.addEventListener('click', () => deleteDepartment(dept.code));
            action.appendChild(del);
            row.append(code, name, parent, action);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

async function deleteDepartment(code) {
    if (!window.confirm('确定删除部门「' + code + '」吗？（仍有文档在用时删不掉）')) {
        return;
    }
    try {
        await api('/api/admin/departments?code=' + encodeURIComponent(code), { method: 'DELETE' });
        showMessage('部门已删除');
        loadDepartments();
    } catch (e) {
        showMessage(e.message);
    }
}

async function loadAuditLogs(page) {
    auditPage = page;
    try {
        const data = await api('/api/admin/audit-logs?page=' + page + '&size=20');
        const tbody = $('auditTable').querySelector('tbody');
        tbody.textContent = '';
        (data.logs || []).forEach((entry) => {
            const row = document.createElement('tr');
            const time = document.createElement('td');
            time.textContent = entry.createdAt ? entry.createdAt.slice(0, 19).replace('T', ' ') : '—';
            const user = document.createElement('td');
            user.textContent = entry.username;
            const action = document.createElement('td');
            action.textContent = entry.action;
            const target = document.createElement('td');
            target.textContent = entry.target || '—';
            const detail = document.createElement('td');
            detail.textContent = entry.detail || '—';
            row.append(time, user, action, target, detail);
            tbody.appendChild(row);
        });
        const totalPages = Math.max(1, Math.ceil(data.total / 20));
        $('auditPageInfo').textContent = '第 ' + (page + 1) + ' / ' + totalPages + ' 页，共 ' + data.total + ' 条';
        $('prevAuditBtn').disabled = page <= 0;
        $('nextAuditBtn').disabled = (page + 1) * 20 >= data.total;
    } catch (e) {
        showMessage(e.message);
    }
}

/* ---------------- 事件绑定 ---------------- */

$('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    showMessage('');
    try {
        const login = await api('/api/auth/login', {
            method: 'POST',
            body: { username: $('loginUsername').value.trim(), password: $('loginPassword').value,
                totpCode: $('loginTotp').value.trim() || null },
        });
        saveSession(login);
        showApp();
    } catch (err) {
        showMessage(err.message);
    }
});

$('logoutBtn').addEventListener('click', () => logout('', true));

$('menuBtn').addEventListener('click', () => {
    $('appView').classList.toggle('sidebar-open');
});

$('quickAskBtn').addEventListener('click', () => showView('chat'));
$('quickUploadBtn').addEventListener('click', () => showView('manage'));
$('quickDocsBtn').addEventListener('click', () => showView('manage'));

$('suggestChips').addEventListener('click', (e) => {
    const chip = e.target.closest('button');
    if (!chip) {
        return;
    }
    $('question').value = chip.textContent;
    $('question').focus();
});

$('newSessionBtn').addEventListener('click', () => {
    currentSessionId = createSession().id;
    renderSessionList();
    renderMessages();
    showView('chat');
});

document.querySelectorAll('.sidebar-link').forEach((link) => {
    link.addEventListener('click', () => showView(link.dataset.view));
});

$('question').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        $('askForm').requestSubmit();
    }
});

$('askForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (asking) {
        return;
    }
    const question = $('question').value.trim();
    if (!question) {
        return;
    }
    asking = true;
    showMessage('');
    $('question').value = '';
    const container = $('messages');
    const empty = container.querySelector('.chat-empty');
    if (empty) {
        empty.remove();
    }
    storeMessage(currentSessionId, { role: 'user', text: question });
    container.appendChild(messageElement({ role: 'user', text: question }));
    nameSessionIfUntitled(question);
    const assistantEl = messageElement({ role: 'assistant', text: '', question });
    container.appendChild(assistantEl);
    scrollMessagesToBottom();
    try {
        const request = buildAskRequest(question);
        const bubble = assistantEl.querySelector('.msg-bubble');
        const result = $('askStream').checked
            ? await askStream(request, bubble)
            : await askOnce(request, bubble);
        storeMessage(currentSessionId, { role: 'assistant', text: result.answer, hits: result.hits || [], question });
    } catch (err) {
        assistantEl.querySelector('.msg-text').textContent = '出错了：' + err.message;
        showMessage(err.message);
    } finally {
        asking = false;
        scrollMessagesToBottom();
    }
});

$('uploadForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const files = Array.from($('uploadFile').files);
    if (files.length === 0) {
        return;
    }
    const listEl = $('uploadList');
    listEl.hidden = false;
    listEl.textContent = '';
    const visibility = $('uploadVisibility').value.trim() || 'all';
    let doneCount = 0;
    // 批量上传：逐个提交入库，一个完成再传下一个，避免多文件同时压向量接口
    for (const file of files) {
        const item = document.createElement('li');
        item.className = 'list-group-item';
        item.textContent = '「' + file.name + '」处理中…';
        listEl.appendChild(item);
        try {
            const form = new FormData();
            form.append('file', file);
            form.append('visibility', visibility);
            const job = await api('/api/ingest-async', { method: 'POST', body: form, isForm: true });
            const done = await pollIngestJob(job.id);
            if (done.status === 'DONE') {
                item.textContent = '「' + done.source + '」已入库，' + done.chunks + ' 块，可见性 ' + done.visibility;
                doneCount += 1;
            } else {
                item.textContent = '「' + done.source + '」入库失败：' + (done.error || '未知错误');
            }
        } catch (err) {
            item.textContent = '「' + file.name + '」失败：' + err.message;
        }
    }
    const resultEl = $('uploadResult');
    resultEl.hidden = false;
    resultEl.textContent = '已完成 ' + doneCount + ' / ' + files.length + ' 个文件';
    loadDocuments(0);
});

/** 每 2 秒轮询一次入库任务，直到 DONE 或 FAILED；最多等 10 分钟。 */
async function pollIngestJob(jobId) {
    for (let i = 0; i < 300; i++) {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        const response = await fetch('/api/ingest/jobs/' + jobId, {
            headers: { Authorization: 'Bearer ' + localStorage.getItem(TOKEN_KEY) },
        });
        if (response.status === 401 && await tryRefresh()) {
            continue;
        }
        if (!response.ok) {
            throw new Error('查询入库任务失败（' + response.status + '）');
        }
        const job = await response.json();
        if (job.status === 'DONE' || job.status === 'FAILED') {
            return job;
        }
    }
    throw new Error('入库超时，请在文档清单里确认是否已入库');
}

$('refreshDocsBtn').addEventListener('click', () => loadDocuments(docsPage));
$('prevPageBtn').addEventListener('click', () => loadDocuments(Math.max(0, docsPage - 1)));
$('nextPageBtn').addEventListener('click', () => loadDocuments(docsPage + 1));
$('refreshNotifBtn').addEventListener('click', () => loadNotifications(notifPage));
$('readAllNotifBtn').addEventListener('click', async () => {
    try {
        await api('/api/notifications/read-all', { method: 'POST' });
        loadNotifications(notifPage);
    } catch (e) {
        showMessage(e.message);
    }
});
$('prevNotifBtn').addEventListener('click', () => loadNotifications(Math.max(0, notifPage - 1)));
$('nextNotifBtn').addEventListener('click', () => loadNotifications(notifPage + 1));
$('refreshApprovalBtn').addEventListener('click', () => loadPending());
$('refreshAuditBtn').addEventListener('click', () => loadAuditLogs(auditPage));
$('prevAuditBtn').addEventListener('click', () => loadAuditLogs(Math.max(0, auditPage - 1)));
$('nextAuditBtn').addEventListener('click', () => loadAuditLogs(auditPage + 1));

$('createDepartmentForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/api/admin/departments', {
            method: 'POST',
            body: { code: $('deptCode').value.trim(), name: $('deptName').value.trim(),
                parentCode: $('deptParent').value.trim() || null },
        });
        showMessage('部门已创建');
        e.target.reset();
        loadDepartments();
    } catch (err) {
        showMessage(err.message);
    }
});

$('totpSetupBtn').addEventListener('click', async () => {
    try {
        const setup = await api('/api/auth/totp/setup', { method: 'POST' });
        $('totpSecret').textContent = setup.secret;
        $('totpUrl').textContent = setup.otpauthUrl;
        $('totpSecretBox').hidden = false;
    } catch (err) {
        showMessage(err.message);
    }
});

$('totpEnableForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/api/auth/totp/enable', { method: 'POST', body: { code: $('totpCode').value.trim() } });
        showMessage('双因素已启用，登录时需输入动态验证码');
        $('totpSecretBox').hidden = true;
        e.target.reset();
    } catch (err) {
        showMessage(err.message);
    }
});

$('totpDisableForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/api/auth/totp/disable', { method: 'POST', body: { password: $('totpDisablePassword').value } });
        showMessage('双因素已关闭');
        e.target.reset();
    } catch (err) {
        showMessage(err.message);
    }
});

$('createUserForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/api/admin/users', {
            method: 'POST',
            body: {
                username: $('newUsername').value.trim(),
                password: $('newPassword').value,
                role: $('newRole').value,
                department: $('newDepartment').value.trim() || null,
            },
        });
        showMessage('用户已创建');
        e.target.reset();
        loadUsers();
    } catch (err) {
        showMessage(err.message);
    }
});

$('changePasswordForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/api/auth/change-password', {
            method: 'POST',
            body: { oldPassword: $('oldPassword').value, newPassword: $('newPassword2').value },
        });
        showMessage('密码已修改');
        e.target.reset();
    } catch (err) {
        showMessage(err.message);
    }
});

/* OIDC 回调：地址里带一次性换码时先换 Token 再进应用；换完把码从地址栏清掉。 */
async function handleOidcCallback() {
    const params = new URLSearchParams(location.search);
    const code = params.get('oidcCode');
    if (!code) {
        return;
    }
    try {
        const response = await fetch('/api/auth/oidc/exchange', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ code }),
        });
        if (response.ok) {
            saveSession(await response.json());
        }
    } catch (e) {
        // 换码失败就走正常登录
    }
    history.replaceState(null, '', location.pathname);
}

$('createApiKeyForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        const expiresDays = $('apiKeyExpires').value.trim();
        const created = await api('/api/admin/api-keys', {
            method: 'POST',
            body: {
                name: $('apiKeyName').value.trim(),
                scope: $('apiKeyScope').value,
                expiresInDays: expiresDays === '' ? null : Number(expiresDays),
            },
        });
        $('newApiKey').textContent = created.key;
        $('newApiKeyBox').hidden = false;
        e.target.reset();
        loadApiKeys();
    } catch (err) {
        showMessage(err.message);
    }
});

$('copyApiKeyBtn').addEventListener('click', async () => {
    try {
        await navigator.clipboard.writeText($('newApiKey').textContent);
        showMessage('密钥已复制');
    } catch (e) {
        showMessage('复制失败，请手动选中复制');
    }
});

/* 已登录过则直接进应用（Token 8 小时有效，过期后接口 401 再回登录页）。 */
(async () => {
    await handleOidcCallback();
    // OIDC 开了才显示企业登录按钮
    try {
        const status = await (await fetch('/api/auth/oidc/enabled')).json();
        $('oidcLoginBtn').hidden = !status.enabled;
    } catch (e) {
        // 查不到就当没开
    }
    try {
        const saved = JSON.parse(localStorage.getItem(USER_KEY) || 'null');
        if (saved && localStorage.getItem(TOKEN_KEY)) {
            currentUser = saved;
            showApp();
        } else {
            $('loginView').hidden = false;
        }
    } catch (e) {
        logout();
    }
})();
