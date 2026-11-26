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
            { key: 'feedback', color: '#206bc4', label: '反馈' },
            { key: 'misses', color: '#d63965', label: '盲区' },
            { key: 'ingests', color: '#2fb344', label: '入库' },
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
            label.setAttribute('fill', '#8a94a6');
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
        const hits = await api('/api/search', { method: 'POST', body: request });
        const bubble = resultEl.querySelector('.msg-bubble');
        bubble.querySelector('.msg-text').textContent = '命中 ' + hits.length + ' 块（未生成答案）';
        renderHitsInto(appendHitsContainer(bubble), hits);
        scrollMessagesToBottom();
    } catch (e) {
        resultEl.querySelector('.msg-text').textContent = e.message;
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

/** 在线文本预览：把拼回的全文放进一个弹层，不做原格式渲染。 */
async function previewDocument(source) {
    try {
        const data = await api('/api/document/preview?source=' + encodeURIComponent(source));
        showMessage('「' + source + '」全文预览');
        window.alert('「' + source + '」\n\n' + (data.text || '（无正文）'));
    } catch (e) {
        showMessage(e.message);
    }
}

/** 批量删文档：勾选的行一起提交，返回里失败项单独提示。 */
async function batchDeleteDocuments() {
    const sources = Array.from(document.querySelectorAll('.doc-checkbox:checked')).map((box) => box.dataset.source);
    if (sources.length === 0) {
        showMessage('请先勾选要删除的文档');
        return;
    }
    if (!window.confirm('确定删除勾选的 ' + sources.length + ' 篇文档吗？')) {
        return;
    }
    try {
        const result = await api('/api/document/batch-delete', { method: 'POST', body: { sources } });
        let message = '已删除 ' + result.deleted.length + ' 篇';
        if (result.failed.length > 0) {
            message += '，失败 ' + result.failed.length + ' 篇：' + result.failed.join('；');
        }
        showMessage(message);
        loadDocuments(docsPage);
    } catch (e) {
        showMessage(e.message);
    }
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

