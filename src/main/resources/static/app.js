'use strict';

/* 聊天式前端：左侧会话列表 + 中央对话（消息气泡，流式逐字出），管理功能在侧边栏切换视图。
   登录 / 上传 / 清单 / 用户管理仍走后端 /api/**。样式底子是 Tabler，布局见 styles.css。 */

const TOKEN_KEY = 'kb.token';
const USER_KEY = 'kb.user';
const SESSIONS_KEY = 'kb.sessions';
const DOCS_PAGE_SIZE = 20;
const MAX_STORED_MESSAGES = 100;

const $ = (id) => document.getElementById(id);
const messageEl = $('message');

let currentUser = null;
let docsPage = 0;
let asking = false;
let currentSessionId = null;

function showMessage(text) {
    messageEl.textContent = text;
    messageEl.hidden = !text;
}

/** 带 Token 的请求；401 时回登录页，其余错误抛中文 message。 */
async function api(path, { method = 'GET', body = null, isForm = false } = {}) {
    const headers = {};
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) {
        headers.Authorization = 'Bearer ' + token;
    }
    if (body && !isForm) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(body);
    }
    const response = await fetch(path, { method, headers, body });
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

function saveSession(login) {
    localStorage.setItem(TOKEN_KEY, login.token);
    localStorage.setItem(USER_KEY, JSON.stringify(login));
    currentUser = login;
}

/** clearChat 只在主动点退出时清本地对话记录；Token 过期回登录页时保留。 */
function logout(message, clearChat = false) {
    localStorage.removeItem(TOKEN_KEY);
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
    $('userInfo').textContent = currentUser.username + '（' + currentUser.role
        + (currentUser.department ? '，' + currentUser.department : '') + '）';
    $('adminNavBtn').hidden = currentUser.role !== 'ADMIN';
    ensureSession();
    showView('chat');
    loadDocuments(0);
    if (currentUser.role === 'ADMIN') {
        loadUsers();
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
    wrap.appendChild(bubble);
    return wrap;
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
        meta.textContent = '[' + hit.index + '] ' + hit.source + ' · 相关度 ' + hit.score.toFixed(2);
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

function appendHitsContainer(bubble) {
    const hits = document.createElement('div');
    bubble.appendChild(hits);
    return hits;
}

/** 手写 SSE 解析：fetch 不能用 EventSource（POST + Authorization 头只能走 fetch）。 */
async function askStream(request, bubble) {
    const token = localStorage.getItem(TOKEN_KEY);
    const response = await fetch('/api/ask-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(request),
    });
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

const VIEW_TITLES = { chat: '对话', manage: '文档管理', admin: '用户管理', password: '改密码' };

function showView(name) {
    $('chatView').hidden = name !== 'chat';
    $('manageView').hidden = name !== 'manage';
    $('adminView').hidden = name !== 'admin';
    $('passwordView').hidden = name !== 'password';
    document.querySelectorAll('.sidebar-link').forEach((link) => {
        link.classList.toggle('active', link.dataset.view === name);
    });
    $('viewTitle').textContent = VIEW_TITLES[name] || '对话';
    $('appView').classList.remove('sidebar-open');
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
            const action = document.createElement('td');
            if (currentUser && currentUser.role === 'ADMIN') {
                const del = document.createElement('button');
                del.type = 'button';
                del.className = 'btn btn-outline-danger btn-sm';
                del.textContent = '删除';
                del.addEventListener('click', () => deleteDocument(doc.source));
                action.appendChild(del);
            }
            row.append(source, chunks, visibility, action);
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

/* ---------------- 事件绑定 ---------------- */

$('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    showMessage('');
    try {
        const login = await api('/api/auth/login', {
            method: 'POST',
            body: { username: $('loginUsername').value.trim(), password: $('loginPassword').value },
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
    const assistantEl = messageElement({ role: 'assistant', text: '' });
    container.appendChild(assistantEl);
    scrollMessagesToBottom();
    try {
        const request = buildAskRequest(question);
        const bubble = assistantEl.querySelector('.msg-bubble');
        const result = $('askStream').checked
            ? await askStream(request, bubble)
            : await askOnce(request, bubble);
        storeMessage(currentSessionId, { role: 'assistant', text: result.answer, hits: result.hits || [] });
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
    const file = $('uploadFile').files[0];
    if (!file) {
        return;
    }
    const form = new FormData();
    form.append('file', file);
    form.append('visibility', $('uploadVisibility').value.trim() || 'all');
    try {
        const result = await api('/api/ingest', { method: 'POST', body: form, isForm: true });
        const resultEl = $('uploadResult');
        resultEl.hidden = false;
        resultEl.textContent = '「' + result.file + '」已入库，' + result.chunks + ' 块，可见性 ' + result.visibility;
        loadDocuments(0);
    } catch (err) {
        showMessage(err.message);
    }
});

$('refreshDocsBtn').addEventListener('click', () => loadDocuments(docsPage));
$('prevPageBtn').addEventListener('click', () => loadDocuments(Math.max(0, docsPage - 1)));
$('nextPageBtn').addEventListener('click', () => loadDocuments(docsPage + 1));

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

/* 已登录过则直接进应用（Token 8 小时有效，过期后接口 401 再回登录页）。 */
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
