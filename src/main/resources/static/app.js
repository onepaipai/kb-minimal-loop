'use strict';

/* 聊天式前端：左侧会话列表 + 中央对话（消息气泡，流式逐字出），管理功能在侧边栏切换视图。
   登录 / 上传 / 清单 / 用户管理仍走后端 /api/**。样式底子是 Tabler，布局见 styles.css。 */

const TOKEN_KEY = 'kb.token';
const REFRESH_KEY = 'kb.refreshToken';
const USER_KEY = 'kb.user';
const DOCS_PAGE_SIZE = 20;

const $ = (id) => document.getElementById(id);
const messageEl = $('message');

let currentUser = null;
let docsPage = 0;
let auditPage = 0;
let notifPage = 0;
let asking = false;
let activeAskController = null;
let refreshPromise = null;
let currentSessionId = null;
let sessionRequestToken = 0;
/* 会话与消息缓存在内存里，唯一事实来源是服务端（跨设备同步） */
let cachedSessions = [];
let cachedMessages = [];
let editingEntryId = null;
let editingFaqId = null;

/** 顶部提示条：type 为 success / danger / warning / info，默认 info；旧调用只传文本仍然兼容。 */
function showMessage(text, type) {
    messageEl.textContent = text;
    messageEl.className = 'alert alert-' + (type || 'info');
    messageEl.hidden = !text;
}

function showSuccess(text) {
    showMessage(text, 'success');
}

function showError(text) {
    showMessage(text, 'danger');
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
    // 成功响应可能是空体（无内容端点），直接 json() 会抛语法错
    const text = await response.text();
    if (!text) {
        return null;
    }
    return JSON.parse(text);
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
    if (refreshPromise) {
        return refreshPromise;
    }
    refreshPromise = (async () => {
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
    })();
    try {
        return await refreshPromise;
    } finally {
        refreshPromise = null;
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

/** clearChat 只在主动点退出时清本页的会话缓存；服务端历史保留，重新登录还能看到。 */
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
        cachedSessions = [];
        cachedMessages = [];
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
    $('entriesNavBtn').hidden = currentUser.role === 'USER';
    $('faqNavBtn').hidden = !(currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN');
    $('missNavBtn').hidden = !(currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN');
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
        loadTrend();
    }
}


/* ---------------- 会话（服务端保存，跨设备同步） ---------------- */

function newSessionId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return 'web-' + window.crypto.randomUUID();
    }
    return 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

/** 从服务端拉会话清单；当前会话不在清单里时自动切到最近更新的那个。 */
async function refreshSessionList() {
    try {
        cachedSessions = await api('/api/sessions');
    } catch (e) {
        showMessage(e.message);
        return;
    }
    const currentExists = cachedSessions.some((s) => s.id === currentSessionId);
    if (cachedSessions.length > 0 && !currentExists && cachedMessages.length === 0 && !asking) {
        await openSession(cachedSessions[0].id);
        return;
    }
    renderSessionList();
}

/** 打开某个会话：从服务端拉消息并渲染。 */
async function openSession(sessionId) {
    const requestToken = ++sessionRequestToken;
    currentSessionId = sessionId;
    try {
        const messages = await api('/api/sessions/' + encodeURIComponent(sessionId) + '/messages');
        if (requestToken !== sessionRequestToken || currentSessionId !== sessionId) {
            return;
        }
        cachedMessages = mapServerMessages(messages);
    } catch (e) {
        if (requestToken !== sessionRequestToken || currentSessionId !== sessionId) {
            return;
        }
        cachedMessages = [];
        showMessage(e.message);
    }
    if (requestToken !== sessionRequestToken || currentSessionId !== sessionId) {
        return;
    }
    renderSessionList();
    renderMessages();
}

/** 服务端消息转 UI 结构：assistant 消息带上追问定位用的 question（取它前面最近的用户消息）。 */
function mapServerMessages(items) {
    const messages = [];
    let lastQuestion = '';
    (items || []).forEach((item) => {
        if (item.role === 'user') {
            lastQuestion = item.content;
            messages.push({ role: 'user', text: item.content });
        } else {
            messages.push({ role: 'assistant', text: item.content, hits: item.refs || [],
                question: lastQuestion, messageId: item.id, starred: item.starred });
        }
    });
    return messages;
}

/** 进应用时调用：先渲染空态，服务端清单到了再刷新并选中最近的会话。 */
function ensureSession() {
    renderSessionList();
    renderMessages();
    refreshSessionList();
}

async function switchSession(id) {
    await openSession(id);
    showView('chat');
}

async function deleteSession(id, event) {
    event.stopPropagation();
    try {
        await api('/api/sessions/' + encodeURIComponent(id), { method: 'DELETE' });
    } catch (e) {
        showMessage(e.message);
        return;
    }
    cachedSessions = cachedSessions.filter((s) => s.id !== id);
    if (currentSessionId === id) {
        cachedMessages = [];
        if (cachedSessions.length > 0) {
            await openSession(cachedSessions[0].id);
        } else {
            currentSessionId = newSessionId();
            renderSessionList();
            renderMessages();
        }
        return;
    }
    renderSessionList();
}

function renderSessionList() {
    const list = $('sessionList');
    list.textContent = '';
    cachedSessions.forEach((session) => {
        const item = document.createElement('div');
        item.className = 'session-item' + (session.id === currentSessionId ? ' active' : '');
        const check = document.createElement('input');
        check.type = 'checkbox';
        check.className = 'session-checkbox me-1';
        check.dataset.id = session.id;
        check.addEventListener('click', (e) => e.stopPropagation());
        const open = document.createElement('button');
        open.type = 'button';
        open.className = 'session-title';
        open.textContent = session.title || '新对话';
        open.addEventListener('click', () => switchSession(session.id));
        const del = document.createElement('span');
        del.className = 'session-del';
        del.textContent = '×';
        del.addEventListener('click', (e) => deleteSession(session.id, e));
        item.append(check, open, del);
        list.appendChild(item);
    });
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
    if (message.messageId) {
        wrap.dataset.messageId = message.messageId;
    }
    const meta = document.createElement('div');
    meta.className = 'msg-meta';
    meta.textContent = message.role === 'user' ? '我' : '知识库助手';
    const bubble = document.createElement('div');
    bubble.className = 'msg-bubble';
    const text = document.createElement('div');
    text.className = 'msg-text';
    text.textContent = message.text;
    bubble.appendChild(text);
    if (message.role === 'assistant' && message.hits && message.hits.length > 0) {
        const hits = document.createElement('div');
        hits.className = 'hits';
        renderHitsInto(hits, message.hits);
        bubble.appendChild(hits);
    }
    if (message.role === 'assistant') {
        const feedback = document.createElement('div');
        feedback.className = 'feedback-row';
        const up = document.createElement('button');
        up.type = 'button';
        up.className = 'btn btn-ghost-secondary btn-sm fb-btn';
        up.textContent = '👍 有用';
        up.addEventListener('click', () => submitFeedback(wrap, up, true));
        const down = document.createElement('button');
        down.type = 'button';
        down.className = 'btn btn-ghost-secondary btn-sm fb-btn';
        down.textContent = '👎 没用';
        down.addEventListener('click', () => submitFeedback(wrap, down, false));
        feedback.append(up, down);
        const copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'btn btn-ghost-secondary btn-sm';
        copy.textContent = '复制';
        copy.addEventListener('click', () => copyAnswer(wrap));
        feedback.appendChild(copy);
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'btn btn-ghost-secondary btn-sm';
        retry.textContent = '重新生成';
        retry.addEventListener('click', () => regenerateAnswer(wrap));
        feedback.appendChild(retry);
        const star = document.createElement('button');
        star.type = 'button';
        star.className = 'btn btn-ghost-secondary btn-sm';
        star.textContent = message.starred ? '★ 已收藏' : '☆ 收藏';
        star.dataset.starred = String(Boolean(message.starred));
        star.addEventListener('click', () => toggleStar(wrap, star));
        feedback.appendChild(star);
        if (currentUser && (currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN')) {
            const toFaq = document.createElement('button');
            toFaq.type = 'button';
            toFaq.className = 'btn btn-ghost-secondary btn-sm';
            toFaq.textContent = '转标准答案';
            toFaq.addEventListener('click', () => prefillFaq(wrap.dataset.question || ''));
            feedback.appendChild(toFaq);
        }
        bubble.appendChild(feedback);
    }
    wrap.append(meta, bubble);
    return wrap;
}

/** 复制回答到剪贴板；浏览器不支持时降级为提示。 */
async function copyAnswer(wrap) {
    const text = wrap.querySelector('.msg-text').textContent;
    if (!text) {
        return;
    }
    try {
        await navigator.clipboard.writeText(text);
        showSuccess('已复制回答');
    } catch (e) {
        showError('复制失败：' + e.message);
    }
}

/** 重新生成：把原问题回填并直接重发一次（走当前流式/整段设置）。 */
function regenerateAnswer(wrap) {
    const question = wrap.dataset.question;
    if (!question) {
        return;
    }
    $('question').value = question;
    $('askForm').requestSubmit();
}

/** 收藏/取消收藏当前回答：气泡记住服务端消息 id 后切换。 */
async function toggleStar(wrap, button) {
    const id = wrap.dataset.messageId;
    if (!id) {
        showError('这条回答还没有保存，稍后再试');
        return;
    }
    const next = button.dataset.starred !== 'true';
    try {
        await api('/api/messages/' + id + '/star?starred=' + next, { method: 'PUT' });
        button.dataset.starred = String(next);
        button.textContent = next ? '★ 已收藏' : '☆ 收藏';
        showSuccess(next ? '已收藏' : '已取消收藏');
    } catch (e) {
        showError(e.message);
    }
}

/** 回答反馈：按原问答提交好评/差评，提交后禁用评分两个按钮（转标准答案保留可用）。 */
async function submitFeedback(wrap, button, helpful) {
    const question = wrap.dataset.question || '';
    if (!question) {
        return;
    }
    const answer = wrap.querySelector('.msg-text').textContent;
    try {
        await api('/api/feedback', { method: 'POST', body: { question, answer, helpful } });
        wrap.querySelectorAll('.feedback-row .fb-btn').forEach((b) => { b.disabled = true; });
        button.classList.add('btn-primary');
        button.classList.remove('btn-outline-secondary');
    } catch (e) {
        showMessage(e.message);
    }
}

function renderMessages() {
    const container = $('messages');
    container.textContent = '';
    if (cachedMessages.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'chat-empty';
        empty.textContent = '在下面输入问题开始对话，回答会带资料来源引用。';
        container.appendChild(empty);
        return;
    }
    cachedMessages.forEach((m) => container.appendChild(messageElement(m)));
    scrollMessagesToBottom();
}

/** 引用命中渲染：默认折叠，编号徽章 + 来源 chip + 高亮命中词 + 跳转原文按钮。 */
function renderHitsInto(container, hits) {
    container.textContent = '';
    container.classList.add('hits');
    (hits || []).forEach((hit) => {
        const block = document.createElement('details');
        block.className = 'hit';
        const summary = document.createElement('summary');
        summary.className = 'hit-meta';
        const badge = document.createElement('span');
        badge.className = 'hit-badge';
        badge.textContent = '[' + hit.index + ']';
        const source = document.createElement('span');
        source.className = 'hit-source';
        source.textContent = hit.source;
        const score = document.createElement('span');
        score.className = 'hit-score';
        score.textContent = '综合分 ' + hit.score.toFixed(3);
        summary.append(badge, source, score);
        const jump = document.createElement('button');
        jump.type = 'button';
        jump.className = 'btn btn-ghost-secondary btn-sm hit-jump';
        jump.textContent = '查看原文';
        jump.addEventListener('click', (e) => {
            e.preventDefault();
            previewDocument(hit.source);
        });
        summary.appendChild(jump);
        block.appendChild(summary);
        const text = document.createElement('div');
        text.className = 'hit-text';
        highlightTerms(text, hit.text, hit.matchedTerms);
        block.appendChild(text);
        container.appendChild(block);
    });
}

/** 纯 DOM 高亮：把命中词包进 <mark>，不用 innerHTML，避免注入。 */
function highlightTerms(container, text, terms) {
    const value = text || '';
    if (!terms || terms.length === 0) {
        container.textContent = value;
        return;
    }
    const escaped = terms.filter(Boolean).map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    if (escaped.length === 0) {
        container.textContent = value;
        return;
    }
    const regex = new RegExp('(' + escaped.join('|') + ')', 'gi');
    let last = 0;
    for (const match of value.matchAll(regex)) {
        container.appendChild(document.createTextNode(value.slice(last, match.index)));
        const mark = document.createElement('mark');
        mark.textContent = match[0];
        container.appendChild(mark);
        last = match.index + match[0].length;
    }
    container.appendChild(document.createTextNode(value.slice(last)));
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
    const textEl = bubble.querySelector('.msg-text');
    textEl.classList.add('typing');
    textEl.textContent = '检索并生成中…';
    try {
        const response = await api('/api/ask', { method: 'POST', body: request });
        textEl.classList.remove('typing');
        textEl.textContent = response.answer;
        renderHitsInto(bubble.querySelector('.hits') || appendHitsContainer(bubble), response.hits);
        scrollMessagesToBottom();
        return response;
    } catch (e) {
        textEl.classList.remove('typing');
        throw e;
    }
}

function fetchAskStream(request, signal) {
    const token = localStorage.getItem(TOKEN_KEY);
    return fetch('/api/ask-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(request),
        signal,
    });
}

function appendHitsContainer(bubble) {
    const hits = document.createElement('div');
    hits.className = 'hits';
    bubble.appendChild(hits);
    return hits;
}

/** 手写 SSE 解析：fetch 不能用 EventSource（POST + Authorization 头只能走 fetch）。 */
async function askStream(request, bubble, signal) {
    let response = await fetchAskStream(request, signal);
    if (response.status === 401 && await tryRefresh()) {
        response = await fetchAskStream(request, signal);
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
    textEl.classList.add('typing');
    textEl.textContent = '检索中…';
    const hitsEl = appendHitsContainer(bubble);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let answer = '';
    let hits = [];
    let firstToken = false;
    let scrollQueued = false;
    const queueScroll = () => {
        if (scrollQueued) {
            return;
        }
        scrollQueued = true;
        requestAnimationFrame(() => {
            scrollMessagesToBottom();
            scrollQueued = false;
        });
    };
    try {
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
                    textEl.textContent = '生成中…';
                    queueScroll();
                } else if (event === 'token') {
                    if (!firstToken) {
                        firstToken = true;
                        textEl.classList.remove('typing');
                        textEl.textContent = '';
                    }
                    answer += data;
                    textEl.appendChild(document.createTextNode(data));
                    queueScroll();
                } else if (event === 'final') {
                    firstToken = true;
                    answer = data;
                    textEl.classList.remove('typing');
                    textEl.textContent = data;
                    queueScroll();
                } else if (event === 'error') {
                    throw new Error(data || '流式回答失败');
                }
            }
        }
    } catch (e) {
        if (e.name === 'AbortError') {
            reader.cancel().catch(() => {});
            if (!firstToken) {
                textEl.classList.remove('typing');
                textEl.textContent = '已停止生成';
            }
            return { answer, hits, stopped: true };
        }
        throw e;
    } finally {
        try {
            reader.releaseLock();
        } catch (e) {
            // 流已关闭时忽略
        }
    }
    if (!firstToken) {
        textEl.classList.remove('typing');
        textEl.textContent = answer;
    }
    return { answer, hits, stopped: false };
}

/* ---------------- 视图切换 ---------------- */

const VIEW_TITLES = { dashboard: '总览', chat: '智能问答', manage: '文档管理', entries: '知识条目',
    faq: '标准答案', notifications: '通知', approval: '审批', miss: '知识盲区', departments: '部门管理',
    audit: '审计日志', admin: '用户管理', apikeys: 'API 密钥', password: '改密码', starred: '我的收藏' };

function showView(name) {
    $('dashboardView').hidden = name !== 'dashboard';
    $('chatView').hidden = name !== 'chat';
    $('manageView').hidden = name !== 'manage';
    $('entriesView').hidden = name !== 'entries';
    $('faqView').hidden = name !== 'faq';
    $('notificationsView').hidden = name !== 'notifications';
    $('approvalView').hidden = name !== 'approval';
    $('missView').hidden = name !== 'miss';
    $('departmentsView').hidden = name !== 'departments';
    $('auditView').hidden = name !== 'audit';
    $('adminView').hidden = name !== 'admin';
    $('apikeysView').hidden = name !== 'apikeys';
    $('passwordView').hidden = name !== 'password';
    $('starredView').hidden = name !== 'starred';
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
    } else if (name === 'entries') {
        loadEntries();
        loadTagOptions();
    } else if (name === 'starred') {
        loadStarred();
    } else if (name === 'faq') {
        loadFaqs();
    } else if (name === 'miss') {
        loadMissStats();
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
            const select = document.createElement('td');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'doc-checkbox';
            checkbox.dataset.source = doc.source;
            const canManage = currentUser && (currentUser.role === 'ADMIN'
                || (currentUser.role === 'DEPT_ADMIN' && currentUser.department === doc.visibility));
            checkbox.disabled = !canManage;
            select.appendChild(checkbox);
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
            const canDelete = canManage;
            if (doc.hasBlob) {
                const download = document.createElement('button');
                download.type = 'button';
                download.className = 'btn btn-outline-primary btn-sm';
                download.textContent = '下载';
                download.addEventListener('click', () => downloadDocument(doc.source));
                action.appendChild(download);
            }
            const preview = document.createElement('button');
            preview.type = 'button';
            preview.className = 'btn btn-outline-secondary btn-sm ms-1';
            preview.textContent = '预览';
            preview.addEventListener('click', () => previewDocument(doc.source));
            action.appendChild(preview);
            if (canDelete) {
                const del = document.createElement('button');
                del.type = 'button';
                del.className = 'btn btn-outline-danger btn-sm ms-1';
                del.textContent = '删除';
                del.addEventListener('click', () => deleteDocument(doc.source));
                action.appendChild(del);
            }
            const canApprove = canManage;
            if (canApprove && doc.version > 1) {
                const rollback = document.createElement('button');
                rollback.type = 'button';
                rollback.className = 'btn btn-outline-secondary btn-sm ms-1';
                rollback.textContent = '回滚';
                rollback.addEventListener('click', () => rollbackDocument(doc.source, doc.version));
                action.appendChild(rollback);
                const compare = document.createElement('button');
                compare.type = 'button';
                compare.className = 'btn btn-ghost-secondary btn-sm ms-1';
                compare.textContent = '版本对比';
                compare.addEventListener('click', () => openVersionCompare(doc.source, doc.version));
                action.appendChild(compare);
            }
            row.append(select, source, chunks, visibility, status, version, action);
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
            const select = document.createElement('td');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'approval-checkbox';
            checkbox.dataset.source = doc.source;
            select.appendChild(checkbox);
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
            row.append(select, source, chunks, visibility, version, action);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

async function reviewDocument(source, decision) {
    try {
        let path = '/api/document/' + decision + '?source=' + encodeURIComponent(source);
        if (decision === 'reject') {
            const reason = window.prompt('请输入驳回理由（可留空）：');
            if (reason === null) {
                return;
            }
            if (reason.trim()) {
                path += '&reason=' + encodeURIComponent(reason.trim());
            }
        }
        await api(path, { method: 'POST' });
        showSuccess('「' + source + '」已' + (decision === 'approve' ? '批准发布' : '驳回'));
        loadPending();
        loadDocuments(docsPage);
    } catch (e) {
        showError(e.message);
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

/* ---------------- 知识条目（在线撰写） ---------------- */

function statusText(status) {
    if (status === 'pending') {
        return '待审批';
    }
    if (status === 'rejected') {
        return '已驳回';
    }
    return '已发布';
}

let entryCategoryFilterValue = '';
let entryTagFilterValue = '';

async function loadEntries() {
    try {
        const params = new URLSearchParams();
        if (entryCategoryFilterValue) {
            params.set('category', entryCategoryFilterValue);
        }
        if (entryTagFilterValue) {
            params.set('tag', entryTagFilterValue);
        }
        const query = params.toString();
        const entries = await api('/api/knowledge' + (query ? '?' + query : ''));
        $('entriesInfo').textContent = '共 ' + entries.length + ' 条';
        const tbody = $('entriesTable').querySelector('tbody');
        tbody.textContent = '';
        (entries || []).forEach((entry) => {
            const row = document.createElement('tr');
            const select = document.createElement('td');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'entry-checkbox';
            checkbox.dataset.id = entry.id;
            checkbox.disabled = !(currentUser && (currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN'
                || entry.username === currentUser.username));
            select.appendChild(checkbox);
            const title = document.createElement('td');
            title.textContent = entry.title;
            const category = document.createElement('td');
            category.textContent = entry.category || '—';
            const tags = document.createElement('td');
            tags.textContent = entry.tags || '—';
            const visibility = document.createElement('td');
            visibility.textContent = entry.visibility;
            const status = document.createElement('td');
            status.textContent = statusText(entry.status);
            const updated = document.createElement('td');
            updated.textContent = entry.updatedAt ? entry.updatedAt.slice(0, 19).replace('T', ' ') : '—';
            const action = document.createElement('td');
            // 编辑/删除后端有权限校验（管理员全部、部门管理员本部门、本人可改自己的），无权限就不渲染按钮
            const canManage = currentUser && (currentUser.role === 'ADMIN' || currentUser.role === 'DEPT_ADMIN'
                || entry.username === currentUser.username);
            if (canManage) {
                const edit = document.createElement('button');
                edit.type = 'button';
                edit.className = 'btn btn-outline-secondary btn-sm';
                edit.textContent = '编辑';
                edit.addEventListener('click', () => editEntry(entry.id));
                const del = document.createElement('button');
                del.type = 'button';
                del.className = 'btn btn-outline-danger btn-sm ms-1';
                del.textContent = '删除';
                del.addEventListener('click', () => deleteEntry(entry.id, entry.title));
                action.append(edit, del);
            }
            row.append(select, title, category, tags, visibility, status, updated, action);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

/** 点编辑：拉全文回填表单，标题锁定（标题即来源，改名等于新建）。 */
async function editEntry(id) {
    try {
        const entry = await api('/api/knowledge/' + id);
        editingEntryId = id;
        $('entryTitle').value = entry.title;
        $('entryTitle').disabled = true;
        $('entryCategory').value = entry.category || '';
        $('entryTags').value = entry.tags || '';
        $('entryVisibility').value = entry.visibility || 'all';
        $('entryContent').value = entry.content || '';
        $('entrySubmitBtn').textContent = '保存修改';
        $('entryCancelEditBtn').hidden = false;
        showView('entries');
    } catch (e) {
        showMessage(e.message);
    }
}

function resetEntryForm() {
    editingEntryId = null;
    $('entryForm').reset();
    $('entryVisibility').value = 'all';
    $('entryTitle').disabled = false;
    $('entrySubmitBtn').textContent = '保存入库';
    $('entryCancelEditBtn').hidden = true;
}

async function deleteEntry(id, title) {
    if (!window.confirm('确定删除知识条目「' + title + '」吗？它的检索块会一起删除。')) {
        return;
    }
    try {
        await api('/api/knowledge/' + id, { method: 'DELETE' });
        showMessage('已删除「' + title + '」');
        loadEntries();
    } catch (e) {
        showMessage(e.message);
    }
}

$('entryForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const body = {
        title: $('entryTitle').value.trim(),
        content: $('entryContent').value,
        category: $('entryCategory').value.trim() || null,
        tags: $('entryTags').value.trim() || null,
        visibility: $('entryVisibility').value.trim() || 'all',
    };
    try {
        let result;
        if (editingEntryId) {
            delete body.title;
            result = await api('/api/knowledge/' + editingEntryId, { method: 'PUT', body });
        } else {
            result = await api('/api/knowledge', { method: 'POST', body });
        }
        showMessage('「' + result.title + '」已入库，' + result.chunks + ' 块，状态：' + statusText(result.status));
        resetEntryForm();
        loadEntries();
    } catch (err) {
        showMessage(err.message);
    }
});

$('entryCancelEditBtn').addEventListener('click', resetEntryForm);


$('refreshEntriesBtn').addEventListener('click', () => loadEntries());

/* ---------------- 标准答案（置顶 FAQ） ---------------- */

function faqStatusText(enabled) {
    if (enabled) {
        return '启用中';
    }
    return '已停用';
}

async function loadFaqs() {
    try {
        const faqs = await api('/api/faq');
        $('faqInfo').textContent = '共 ' + faqs.length + ' 条';
        const tbody = $('faqTable').querySelector('tbody');
        tbody.textContent = '';
        (faqs || []).forEach((faq) => {
            const row = document.createElement('tr');
            const select = document.createElement('td');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'faq-checkbox';
            checkbox.dataset.id = faq.id;
            select.appendChild(checkbox);
            const question = document.createElement('td');
            question.textContent = faq.question;
            const keywords = document.createElement('td');
            keywords.textContent = faq.keywords || '—';
            const department = document.createElement('td');
            department.textContent = faq.department;
            const status = document.createElement('td');
            status.textContent = faqStatusText(faq.enabled);
            const updated = document.createElement('td');
            updated.textContent = faq.updatedAt ? faq.updatedAt.slice(0, 19).replace('T', ' ') : '—';
            const action = document.createElement('td');
            const edit = document.createElement('button');
            edit.type = 'button';
            edit.className = 'btn btn-outline-secondary btn-sm';
            edit.textContent = '编辑';
            edit.addEventListener('click', () => editFaq(faq));
            const toggle = document.createElement('button');
            toggle.type = 'button';
            toggle.className = 'btn btn-outline-secondary btn-sm ms-1';
            if (faq.enabled) {
                toggle.textContent = '停用';
            } else {
                toggle.textContent = '启用';
            }
            toggle.addEventListener('click', () => toggleFaq(faq));
            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'btn btn-outline-danger btn-sm ms-1';
            del.textContent = '删除';
            del.addEventListener('click', () => deleteFaq(faq.id, faq.question));
            action.append(edit, toggle, del);
            row.append(select, question, keywords, department, status, updated, action);
            tbody.appendChild(row);
        });
    } catch (e) {
        showMessage(e.message);
    }
}

function editFaq(faq) {
    editingFaqId = faq.id;
    $('faqQuestion').value = faq.question;
    $('faqQuestion').disabled = true;
    $('faqKeywords').value = faq.keywords || '';
    $('faqDepartment').value = faq.department || 'all';
    $('faqEnabled').checked = faq.enabled;
    $('faqAnswer').value = faq.answer || '';
    $('faqSubmitBtn').textContent = '保存修改';
    $('faqCancelEditBtn').hidden = false;
    $('faqEnabledBox').hidden = false;
}

function resetFaqForm() {
    editingFaqId = null;
    $('faqForm').reset();
    $('faqDepartment').value = 'all';
    $('faqEnabled').checked = true;
    $('faqQuestion').disabled = false;
    $('faqSubmitBtn').textContent = '保存';
    $('faqCancelEditBtn').hidden = true;
    $('faqEnabledBox').hidden = true;
}

/** 把某个问题预填进标准答案表单（差评回答/盲区问题一键转入）。 */
function prefillFaq(question) {
    resetFaqForm();
    $('faqQuestion').value = question;
    showView('faq');
}

async function toggleFaq(faq) {
    try {
        await api('/api/faq/' + faq.id, { method: 'PUT', body: { enabled: !faq.enabled } });
        if (faq.enabled) {
            showMessage('「' + faq.question + '」已停用');
        } else {
            showMessage('「' + faq.question + '」已启用');
        }
        loadFaqs();
    } catch (e) {
        showMessage(e.message);
    }
}

async function deleteFaq(id, question) {
    if (!window.confirm('确定删除标准答案「' + question + '」吗？')) {
        return;
    }
    try {
        await api('/api/faq/' + id, { method: 'DELETE' });
        showMessage('已删除「' + question + '」');
        loadFaqs();
    } catch (e) {
        showMessage(e.message);
    }
}

$('faqForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        let result;
        if (editingFaqId) {
            result = await api('/api/faq/' + editingFaqId, {
                method: 'PUT',
                body: {
                    answer: $('faqAnswer').value,
                    keywords: $('faqKeywords').value.trim() || null,
                    department: $('faqDepartment').value.trim() || 'all',
                    enabled: $('faqEnabled').checked,
                },
            });
        } else {
            result = await api('/api/faq', {
                method: 'POST',
                body: {
                    question: $('faqQuestion').value.trim(),
                    answer: $('faqAnswer').value,
                    keywords: $('faqKeywords').value.trim() || null,
                    department: $('faqDepartment').value.trim() || 'all',
                },
            });
        }
        showMessage('标准答案「' + result.question + '」已保存');
        resetFaqForm();
        loadFaqs();
    } catch (err) {
        showMessage(err.message);
    }
});

$('faqCancelEditBtn').addEventListener('click', resetFaqForm);
$('refreshFaqBtn').addEventListener('click', () => loadFaqs());

/* ---------------- 知识盲区看板 ---------------- */

async function loadMissStats() {
    try {
        let query = '?limit=50';
        const keyword = $('missKeyword').value.trim();
        if (keyword) {
            query += '&q=' + encodeURIComponent(keyword);
        }
        const stats = await api('/api/admin/miss-stats' + query);
        const tbody = $('missTable').querySelector('tbody');
        tbody.textContent = '';
        (stats || []).forEach((stat) => {
            const row = document.createElement('tr');
            const question = document.createElement('td');
            question.textContent = stat.question;
            const count = document.createElement('td');
            count.textContent = stat.count;
            const last = document.createElement('td');
            last.textContent = stat.lastAskedAt ? stat.lastAskedAt.slice(0, 19).replace('T', ' ') : '—';
            const action = document.createElement('td');
            const toFaq = document.createElement('button');
            toFaq.type = 'button';
            toFaq.className = 'btn btn-outline-primary btn-sm';
            toFaq.textContent = '转标准答案';
            toFaq.addEventListener('click', () => prefillFaq(stat.question));
            const toEntry = document.createElement('button');
            toEntry.type = 'button';
            toEntry.className = 'btn btn-outline-secondary btn-sm ms-1';
            toEntry.textContent = '补知识条目';
            toEntry.addEventListener('click', () => prefillEntry(stat.question));
            action.append(toFaq, toEntry);
            row.append(question, count, last, action);
            tbody.appendChild(row);
        });
        if ((stats || []).length === 0) {
            const emptyRow = document.createElement('tr');
            const emptyCell = document.createElement('td');
            emptyCell.colSpan = 4;
            emptyCell.className = 'text-secondary';
            emptyCell.textContent = '还没有没答上的问题。';
            emptyRow.appendChild(emptyCell);
            tbody.appendChild(emptyRow);
        }
    } catch (e) {
        showMessage(e.message);
    }
}

/** 从盲区问题预填知识条目表单：标题带过去，正文留占位待补。 */
function prefillEntry(question) {
    resetEntryForm();
    $('entryTitle').value = question;
    $('entryContent').value = '待补充：' + question + '\n';
    showView('entries');
}

$('refreshMissBtn').addEventListener('click', () => loadMissStats());

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
    const shell = $('appView');
    shell.classList.toggle('sidebar-open');
    $('menuBtn').setAttribute('aria-expanded', String(shell.classList.contains('sidebar-open')));
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
    currentSessionId = newSessionId();
    cachedMessages = [];
    // 先放一个占位项，首次提问落库后服务端清单里就会出现它
    cachedSessions.unshift({ id: currentSessionId, title: '新对话', updatedAt: null });
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

$('searchOnlyBtn').addEventListener('click', () => searchOnly());
$('askForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (asking) {
        return;
    }
    const question = $('question').value.trim();
    if (!question) {
        return;
    }
    const sendBtn = $('askForm').querySelector('button[type="submit"]');
    const controller = new AbortController();
    activeAskController = controller;
    asking = true;
    sendBtn.disabled = true;
    $('stopAskBtn').hidden = false;
    showMessage('');
    $('question').value = '';
    const container = $('messages');
    const empty = container.querySelector('.chat-empty');
    if (empty) {
        empty.remove();
    }
    const askedSessionId = currentSessionId;
    cachedMessages.push({ role: 'user', text: question });
    container.appendChild(messageElement({ role: 'user', text: question }));
    const assistantEl = messageElement({ role: 'assistant', text: '', question });
    container.appendChild(assistantEl);
    scrollMessagesToBottom();
    try {
        const request = buildAskRequest(question);
        const bubble = assistantEl.querySelector('.msg-bubble');
        const result = $('askStream').checked
            ? await askStream(request, bubble, controller.signal)
            : await askOnce(request, bubble);
        // 流式期间用户可能已切到别的会话：答案只进发起提问的那个会话的缓存
        if (askedSessionId === currentSessionId && !result.stopped) {
            cachedMessages.push({ role: 'assistant', text: result.answer,
                hits: result.hits || [], question });
        }
        // 服务端已落库会话与消息，刷新左侧清单让新会话/新标题出现
        refreshSessionList();
    } catch (err) {
        const textEl = assistantEl.querySelector('.msg-text');
        textEl.classList.remove('typing');
        textEl.classList.add('state-error');
        textEl.textContent = '出错了：' + err.message;
        showError(err.message);
    } finally {
        asking = false;
        activeAskController = null;
        sendBtn.disabled = false;
        $('stopAskBtn').hidden = true;
        scrollMessagesToBottom();
    }
});

/** 停止生成：中断当前 SSE 请求，已收到的 token 保留在气泡里。 */
$('stopAskBtn').addEventListener('click', () => {
    if (activeAskController) {
        activeAskController.abort();
        activeAskController = null;
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
$('batchDeleteDocsBtn').addEventListener('click', () => batchDeleteDocuments());
$('batchDeleteFaqBtn').addEventListener('click', () =>
    runBatchDelete('/api/faq/batch-delete', selectedIds('faq-checkbox'), '标准答案', () => loadFaqs()));
$('batchDeleteEntriesBtn').addEventListener('click', () =>
    runBatchDelete('/api/knowledge/batch-delete', selectedIds('entry-checkbox'), '知识条目', () => loadEntries()));
$('batchDeleteSessionsBtn').addEventListener('click', () =>
    runBatchDelete('/api/sessions/batch-delete', selectedIds('session-checkbox'), '会话', () => refreshSessionList()));
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
    // 会话历史已改存服务端，清掉旧版本浏览器里的遗留键
    localStorage.removeItem('kb.sessions');
    Object.keys(localStorage).forEach((key) => {
        if (key.indexOf('kb.messages.') === 0) {
            localStorage.removeItem(key);
        }
    });
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
