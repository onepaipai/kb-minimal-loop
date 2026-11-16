'use strict';

/* 单页前端：登录 / 问答（含流式）/ 上传 / 清单 / 用户管理，全部走后端 /api/**。
   无构建步骤，Spring Boot 直接 serving static/ 下的文件。 */

const TOKEN_KEY = 'kb.token';
const USER_KEY = 'kb.user';
const DOCS_PAGE_SIZE = 20;

const $ = (id) => document.getElementById(id);
const messageEl = $('message');

let currentUser = null;
let docsPage = 0;
let asking = false;

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

function logout(message) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    currentUser = null;
    $('appView').hidden = true;
    $('loginView').hidden = false;
    $('userBar').hidden = true;
    showMessage(message || '');
}

function showApp() {
    $('loginView').hidden = true;
    $('appView').hidden = false;
    $('userBar').hidden = false;
    $('userInfo').textContent = currentUser.username + '（' + currentUser.role
        + (currentUser.department ? '，' + currentUser.department : '') + '）';
    $('adminSection').hidden = currentUser.role !== 'ADMIN';
    newSessionId();
    loadDocuments(0);
    if (currentUser.role === 'ADMIN') {
        loadUsers();
    }
}

function newSessionId() {
    $('sessionId').value = 'web-' + Math.random().toString(36).slice(2, 10);
}

function buildAskRequest() {
    const request = {
        question: $('question').value.trim(),
        sessionId: $('sessionId').value.trim() || undefined,
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

function renderHits(hits) {
    const container = $('hits');
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

async function askOnce(request) {
    const response = await api('/api/ask', { method: 'POST', body: request });
    $('answer').hidden = false;
    $('answer').textContent = response.answer;
    renderHits(response.hits);
}

/** 手写 SSE 解析：fetch 不能用 EventSource（POST + Authorization 头只能走 fetch）。 */
async function askStream(request) {
    const token = localStorage.getItem(TOKEN_KEY);
    const response = await fetch('/api/ask-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(request),
    });
    if (response.status === 401) {
        logout('登录已过期，请重新登录');
        return;
    }
    if (!response.ok) {
        const err = await response.json().catch(() => ({}));
        throw new Error(err.message || '请求失败（' + response.status + '）');
    }
    const answerEl = $('answer');
    answerEl.hidden = false;
    answerEl.textContent = '';
    $('hits').textContent = '';
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
        const { done, value } = await reader.read();
        if (done) {
            break;
        }
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split('\n\n');
        buffer = events.pop();
        for (const raw of events) {
            handleSseEvent(raw);
        }
    }
}

function handleSseEvent(raw) {
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
        renderHits(JSON.parse(data));
    } else if (event === 'token') {
        $('answer').textContent += data;
    }
}

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

$('logoutBtn').addEventListener('click', () => logout());

$('newSessionBtn').addEventListener('click', newSessionId);

$('askForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (asking) {
        return;
    }
    asking = true;
    showMessage('');
    try {
        const request = buildAskRequest();
        if ($('askStream').checked) {
            await askStream(request);
        } else {
            await askOnce(request);
        }
    } catch (err) {
        showMessage(err.message);
    } finally {
        asking = false;
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
    }
} catch (e) {
    logout();
}
