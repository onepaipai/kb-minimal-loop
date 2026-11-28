/* 新增功能模块：原件下载/预览、批量删除、仅搜索、趋势图。
   依赖 app.js 的全局 api/showMessage/$/currentUser/docsPage/loadDocuments 等。 */

/** 近 7 天趋势：纯 SVG 柱状图，无图表库；反馈、盲区、入库三组按天分组。 */
async function loadTrend() {
    $('trendCard').hidden = false;
    try {
        const rows = await api('/api/admin/stats/trend?days=7');
        const svg = $('trendChart');
        svg.textContent = '';
        const series = [
            { key: 'feedback', color: 'var(--kb-brand, #206bc4)', label: '反馈' },
            { key: 'misses', color: 'var(--kb-danger, #d63965)', label: '盲区' },
            { key: 'ingests', color: 'var(--kb-success, #2fb344)', label: '入库' },
        ];
        const max = Math.max(1, ...rows.flatMap((row) => series.map((item) => row[item.key] || 0)));
        const groupWidth = 700 / Math.max(1, rows.length);
        const barWidth = Math.min(14, groupWidth / (series.length + 1));
        rows.forEach((row, index) => {
            series.forEach((item, seriesIndex) => {
                const value = row[item.key] || 0;
                const height = (value / max) * 130;
                const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                rect.setAttribute('x', String(index * groupWidth + groupWidth / 2 - barWidth * 1.5
                    + seriesIndex * barWidth));
                rect.setAttribute('y', String(145 - height));
                rect.setAttribute('width', String(barWidth - 2));
                rect.setAttribute('height', String(height));
                rect.setAttribute('fill', item.color);
                rect.setAttribute('rx', '2');
                svg.appendChild(rect);
            });
            const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            label.setAttribute('x', String(index * groupWidth + groupWidth / 2));
            label.setAttribute('y', '165');
            label.setAttribute('text-anchor', 'middle');
            label.setAttribute('fill', 'var(--kb-text-muted, #8a94a6)');
            label.setAttribute('font-size', '10');
            label.textContent = (row.date || '').slice(5);
            svg.appendChild(label);
        });
        $('trendLegend').textContent = series.map((item) => item.label).join(' / ') + '（峰值 ' + max + '）';
        $('trendEmpty').hidden = true;
    } catch (e) {
        $('trendEmpty').textContent = '趋势加载失败：' + e.message;
        $('trendEmpty').hidden = false;
    }
}

/** 仅搜索：不调聊天模型，把命中块渲染成一条结果气泡；不写会话历史。 */
async function searchOnly() {
    const question = $('question').value.trim();
    if (!question) {
        showMessage('请输入要检索的问题');
        return;
    }
    $('question').value = '';
    const container = $('messages');
    const empty = container.querySelector('.chat-empty');
    if (empty) {
        empty.remove();
    }
    container.appendChild(messageElement({ role: 'user', text: '仅搜索：' + question }));
    const resultEl = messageElement({ role: 'assistant', text: '' });
    container.appendChild(resultEl);
    scrollMessagesToBottom();
    try {
        const request = buildAskRequest(question);
        const grouped = await api('/api/search/grouped', { method: 'POST', body: request });
        const bubble = resultEl.querySelector('.msg-bubble');
        const textEl = bubble.querySelector('.msg-text');
        const sources = Object.keys(grouped);
        textEl.textContent = '命中 ' + sources.length + ' 篇文档、'
            + Object.values(grouped).reduce((n, list) => n + list.length, 0) + ' 块（未生成答案）';
        const container = appendHitsContainer(bubble);
        sources.forEach((source) => {
            const group = document.createElement('details');
            group.className = 'hit-group';
            group.open = true;
            const title = document.createElement('summary');
            title.className = 'hit-group-title';
            title.textContent = source + '（' + grouped[source].length + ' 块）';
            group.appendChild(title);
            const inner = document.createElement('div');
            renderHitsInto(inner, grouped[source]);
            group.appendChild(inner);
            container.appendChild(group);
        });
        scrollMessagesToBottom();
    } catch (e) {
        showError(e.message);
    }
}

/** 下载原件：api() 会 JSON.parse，这里直接用 fetch 拿 blob 再触发浏览器下载。 */
async function downloadDocument(source) {
    try {
        let response = await doApi('/api/document/download?source=' + encodeURIComponent(source), 'GET');
        if (response.status === 401 && await tryRefresh()) {
            response = await doApi('/api/document/download?source=' + encodeURIComponent(source), 'GET');
        }
        if (!response.ok) {
            throw new Error('下载失败（' + response.status + '）');
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = source;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
    } catch (e) {
        showMessage(e.message);
    }
}

/** 在线文本预览：纯 CSS 抽屉展示拼回的全文，替换原来的 window.alert。 */
async function previewDocument(source, version) {
    try {
        let path = '/api/document/preview?source=' + encodeURIComponent(source);
        if (version !== undefined && version !== null && version !== 'current') {
            path += '&version=' + encodeURIComponent(version);
        }
        const data = await api(path);
        $('previewTitle').textContent = '「' + source + '」'
            + (data.version === 'current' ? ' 当前版' : ' v' + data.version);
        $('previewText').textContent = data.text || '（无正文）';
        $('previewDrawer').hidden = false;
    } catch (e) {
        showError(e.message);
    }
}

function closePreviewDrawer() {
    $('previewDrawer').hidden = true;
}

/** 批量删文档：勾选的行一起提交，返回里失败项单独提示。 */
async function batchDeleteDocuments() {
    const sources = Array.from(document.querySelectorAll('.doc-checkbox:checked')).map((box) => box.dataset.source);
    if (sources.length === 0) {
        showMessage('请先勾选要删除的文档');
        return;
    }
    if (!window.confirm('确定将勾选的 ' + sources.length + ' 篇文档移入回收站吗？')) {
        return;
    }
    try {
        const result = await api('/api/document/batch-delete', { method: 'POST', body: { sources } });
        let message = '已移入回收站 ' + result.deleted.length + ' 篇';
        if (result.failed.length > 0) {
            message += '，失败 ' + result.failed.length + ' 篇：' + result.failed.join('；');
        }
        showMessage(message);
        loadDocuments(docsPage);
    } catch (e) {
        showMessage(e.message);
    }
}

/** 加载回收站：服务端已按当前用户部门范围过滤。 */
async function loadTrash(page) {
    trashPage = page;
    try {
        const data = await api('/api/documents/trash?page=' + page + '&size=' + DOCS_PAGE_SIZE);
        const tbody = $('trashTable').querySelector('tbody');
        tbody.textContent = '';
        (data.documents || []).forEach((doc) => {
            const row = document.createElement('tr');
            const select = document.createElement('td');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'trash-checkbox';
            checkbox.dataset.source = doc.source;
            select.appendChild(checkbox);
            const source = document.createElement('td');
            source.textContent = doc.source;
            const chunks = document.createElement('td');
            chunks.textContent = doc.chunks;
            const visibility = document.createElement('td');
            visibility.textContent = doc.visibility;
            const status = document.createElement('td');
            status.textContent = doc.status === 'approved' ? '已发布' : doc.status === 'pending' ? '待审批' : '已驳回';
            const version = document.createElement('td');
            version.textContent = 'v' + doc.version;
            const deletedAt = document.createElement('td');
            deletedAt.textContent = doc.deletedAt ? new Date(doc.deletedAt).toLocaleString() : '—';
            const deletedBy = document.createElement('td');
            deletedBy.textContent = doc.deletedBy || '—';
            const action = document.createElement('td');
            const restore = document.createElement('button');
            restore.type = 'button';
            restore.className = 'btn btn-outline-primary btn-sm';
            restore.textContent = '恢复';
            restore.addEventListener('click', () => restoreTrash(doc.source));
            action.appendChild(restore);
            if (currentUser && currentUser.role === 'ADMIN') {
                const purge = document.createElement('button');
                purge.type = 'button';
                purge.className = 'btn btn-outline-danger btn-sm ms-1';
                purge.textContent = '永久删除';
                purge.addEventListener('click', () => purgeTrash(doc.source));
                action.appendChild(purge);
            }
            row.append(select, source, chunks, visibility, status, version, deletedAt, deletedBy, action);
            tbody.appendChild(row);
        });
        const totalPages = Math.max(1, Math.ceil(data.total / DOCS_PAGE_SIZE));
        $('trashPageInfo').textContent = '第 ' + (page + 1) + ' / ' + totalPages + ' 页，共 ' + data.total + ' 篇';
        $('prevTrashBtn').disabled = page <= 0;
        $('nextTrashBtn').disabled = (page + 1) * DOCS_PAGE_SIZE >= data.total;
    } catch (e) {
        showMessage(e.message);
    }
}

async function restoreTrash(source) {
    if (!window.confirm('确定恢复「' + source + '」吗？')) {
        return;
    }
    try {
        await api('/api/document/restore?source=' + encodeURIComponent(source), { method: 'POST' });
        showMessage('已恢复「' + source + '」');
        loadTrash(trashPage);
        loadDocuments(0);
    } catch (e) {
        showMessage(e.message);
    }
}

async function purgeTrash(source) {
    if (!window.confirm('永久删除后无法恢复，确定删除「' + source + '」吗？')) {
        return;
    }
    try {
        await api('/api/document/permanent?source=' + encodeURIComponent(source), { method: 'DELETE' });
        showMessage('已永久删除「' + source + '」');
        loadTrash(trashPage);
    } catch (e) {
        showMessage(e.message);
    }
}

async function batchRestoreTrash() {
    const sources = selectedTrashSources();
    if (sources.length === 0) {
        showMessage('请先勾选要恢复的文档');
        return;
    }
    if (!window.confirm('确定恢复勾选的 ' + sources.length + ' 篇文档吗？')) {
        return;
    }
    await runTrashBatch('/api/document/batch-restore', sources, '恢复', 'restored');
}

async function batchPurgeTrash() {
    const sources = selectedTrashSources();
    if (sources.length === 0) {
        showMessage('请先勾选要永久删除的文档');
        return;
    }
    if (!window.confirm('永久删除后无法恢复，确定删除勾选的 ' + sources.length + ' 篇文档吗？')) {
        return;
    }
    await runTrashBatch('/api/document/batch-permanent-delete', sources, '永久删除', 'deleted');
}

async function runTrashBatch(path, sources, action, resultKey) {
    try {
        const result = await api(path, { method: 'POST', body: { sources } });
        let message = '已' + action + ' ' + result[resultKey].length + ' 篇';
        if (result.failed.length > 0) {
            message += '，失败 ' + result.failed.length + ' 篇：' + result.failed.join('；');
        }
        showMessage(message);
        loadTrash(trashPage);
        loadDocuments(0);
    } catch (e) {
        showMessage(e.message);
    }
}

function selectedTrashSources() {
    return Array.from(document.querySelectorAll('.trash-checkbox:checked')).map((box) => box.dataset.source);
}

/** 批量删除通用流程：勾选校验、确认、提交、结果提示与刷新。 */
async function runBatchDelete(path, ids, label, refresh) {
    if (ids.length === 0) {
        showMessage('请先勾选要删除的' + label);
        return;
    }
    if (!window.confirm('确定删除勾选的 ' + ids.length + ' 项' + label + '吗？')) {
        return;
    }
    try {
        const result = await api(path, { method: 'POST', body: { ids } });
        let message = '已删除 ' + result.deleted.length + ' 项';
        if (result.failed.length > 0) {
            message += '，失败 ' + result.failed.length + ' 项：' + result.failed.join('；');
        }
        showMessage(message);
        refresh();
    } catch (e) {
        showMessage(e.message);
    }
}

function selectedIds(className) {
    return Array.from(document.querySelectorAll('.' + className + ':checked')).map((box) => box.dataset.id);
}

