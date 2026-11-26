/* 新增功能的交互模块：导出、批量审批/改可见性、版本对比、收藏视图、标签筛选、主题切换。
   依赖 app.js 的 api/$/showSuccess/showError/currentUser/loadDocuments/loadFaqs/loadEntries，
   以及 features.js 的 runBatchDelete/selectedIds/previewDocument。在 app.js 之后加载。 */

/* ---------------- 主题 ---------------- */

/** 明暗主题：Tabler 自带 data-bs-theme 变量，localStorage 记住选择。 */
function initTheme() {
    const saved = localStorage.getItem('kb.theme') || 'light';
    document.documentElement.dataset.bsTheme = saved;
    syncThemeButton(saved);
}

function toggleTheme() {
    const next = document.documentElement.dataset.bsTheme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.bsTheme = next;
    localStorage.setItem('kb.theme', next);
    syncThemeButton(next);
}

function syncThemeButton(theme) {
    const button = $('themeBtn');
    if (button) {
        button.textContent = theme === 'dark' ? '☀ 浅色' : '☾ 深色';
        button.setAttribute('aria-label', theme === 'dark' ? '切换到浅色主题' : '切换到深色主题');
    }
}

/* ---------------- 导出 ---------------- */

/** 导出管理数据：CSV / JSON / Markdown 走 /api/admin/export，浏览器直接下载。 */
async function exportDataset(dataset, format) {
    try {
        const response = await doApi('/api/admin/export?dataset=' + encodeURIComponent(dataset)
            + '&format=' + encodeURIComponent(format), 'GET');
        if (!response.ok) {
            throw new Error('导出失败（' + response.status + '）');
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = dataset + '.' + (format === 'md' ? 'md' : format);
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
        showSuccess('已导出 ' + dataset + '.' + format);
    } catch (e) {
        showError(e.message);
    }
}

/* ---------------- 批量审批 / 批量改可见性 ---------------- */

/** 审批页勾选多项后批量通过/驳回；驳回时让管理员填理由。 */
async function batchReview(status) {
    const sources = Array.from(document.querySelectorAll('.approval-checkbox:checked'))
        .map((box) => box.dataset.source);
    if (sources.length === 0) {
        showError('请先勾选要审批的文档');
        return;
    }
    let reason = null;
    if (status === 'rejected') {
        reason = window.prompt('请输入驳回理由（可留空）：');
        if (reason === null) {
            return;
        }
    }
    const label = status === 'approved' ? '通过' : '驳回';
    if (!window.confirm('确定批量' + label + ' ' + sources.length + ' 篇文档吗？')) {
        return;
    }
    try {
        const result = await api('/api/document/batch-review', {
            method: 'POST', body: { sources, status, reason },
        });
        let message = '已' + label + ' ' + result.reviewed.length + ' 篇';
        if (result.failed.length > 0) {
            message += '，失败 ' + result.failed.length + ' 篇：' + result.failed.join('；');
        }
        showSuccess(message);
        loadPending();
        loadDocuments(0);
    } catch (e) {
        showError(e.message);
    }
}

/** 批量改可见性：统一目标部门代号，先校验再逐条改。 */
async function batchVisibility() {
    const sources = Array.from(document.querySelectorAll('.doc-checkbox:checked'))
        .map((box) => box.dataset.source);
    const visibility = $('batchVisibilityInput').value.trim();
    if (sources.length === 0 || !visibility) {
        showError('请勾选文档并填写目标可见性（all 或部门代号）');
        return;
    }
    if (!window.confirm('确定把 ' + sources.length + ' 篇文档改为「' + visibility + '」吗？')) {
        return;
    }
    try {
        const result = await api('/api/document/batch-visibility', {
            method: 'POST', body: { sources, visibility },
        });
        let message = '已更新 ' + result.updated.length + ' 篇';
        if (result.failed.length > 0) {
            message += '，失败 ' + result.failed.length + ' 篇：' + result.failed.join('；');
        }
        showSuccess(message);
        loadDocuments(docsPage);
    } catch (e) {
        showError(e.message);
    }
}

/* ---------------- 版本对比 ---------------- */

/** 取两个版本的全文做逐行 diff，结果放进抽屉。 */
async function compareDocumentVersions(source, versionA, versionB) {
    try {
        const [a, b] = await Promise.all([
            api('/api/document/preview?source=' + encodeURIComponent(source) + '&version=' + versionA),
            api('/api/document/preview?source=' + encodeURIComponent(source) + '&version=' + versionB),
        ]);
        const left = (a.text || '').split('\n');
        const right = (b.text || '').split('\n');
        const body = $('diffBody');
        body.textContent = '';
        const max = Math.max(left.length, right.length);
        for (let i = 0; i < max; i++) {
            const l = left[i] === undefined ? '' : left[i];
            const r = right[i] === undefined ? '' : right[i];
            const row = document.createElement('div');
            row.className = 'diff-row' + (l === r ? '' : ' diff-changed');
            const oldCell = document.createElement('span');
            oldCell.className = 'diff-old';
            oldCell.textContent = l;
            const newCell = document.createElement('span');
            newCell.className = 'diff-new';
            newCell.textContent = r;
            row.append(oldCell, newCell);
            body.appendChild(row);
        }
        $('diffTitle').textContent = '「' + source + '」 v' + versionA + ' → v' + versionB;
        $('diffDrawer').hidden = false;
    } catch (e) {
        showError(e.message);
    }
}

/** 版本对比入口：先取版本列表，让管理员选两个版本。 */
async function openVersionCompare(source, currentVersion) {
    try {
        const data = await api('/api/document/versions?source=' + encodeURIComponent(source));
        const versions = (data.versions || []).filter((v) => v < currentVersion);
        if (versions.length === 0) {
            showError('没有可对比的旧版');
            return;
        }
        const from = window.prompt('对比起始版本（可选：' + versions.join(', ') + '）', String(versions[0]));
        if (from === null) {
            return;
        }
        compareDocumentVersions(source, Number(from), currentVersion);
    } catch (e) {
        showError(e.message);
    }
}

/* ---------------- 收藏视图 ---------------- */

/** 收藏清单：显示收藏的回答，可跳回原会话、取消收藏。 */
async function loadStarred() {
    try {
        const items = await api('/api/messages/starred');
        const container = $('starredList');
        container.textContent = '';
        if (items.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'state-empty';
            empty.textContent = '还没有收藏的回答。';
            container.appendChild(empty);
            return;
        }
        items.forEach((item) => {
            const card = document.createElement('div');
            card.className = 'card mb-2';
            const body = document.createElement('div');
            body.className = 'card-body';
            const title = document.createElement('div');
            title.className = 'text-secondary small mb-1';
            title.textContent = (item.createdAt || '').slice(0, 19).replace('T', ' ');
            const text = document.createElement('div');
            text.className = 'starred-text';
            text.textContent = item.content;
            const actions = document.createElement('div');
            actions.className = 'mt-2 d-flex gap-2';
            const open = document.createElement('button');
            open.type = 'button';
            open.className = 'btn btn-outline-secondary btn-sm';
            open.textContent = '打开会话';
            open.addEventListener('click', () => {
                showView('chat');
                openSession(item.sessionId);
            });
            const unstar = document.createElement('button');
            unstar.type = 'button';
            unstar.className = 'btn btn-ghost-danger btn-sm';
            unstar.textContent = '取消收藏';
            unstar.addEventListener('click', async () => {
                try {
                    await api('/api/messages/' + item.id + '/star?starred=false', { method: 'PUT' });
                    loadStarred();
                } catch (e) {
                    showError(e.message);
                }
            });
            actions.append(open, unstar);
            body.append(title, text, actions);
            card.appendChild(body);
            container.appendChild(card);
        });
    } catch (e) {
        showError(e.message);
    }
}

/* ---------------- 标签筛选 ---------------- */

/** 载入分类/标签字典，填进知识条目视图的筛选下拉。 */
async function loadTagOptions() {
    try {
        const data = await api('/api/admin/tags');
        const cat = $('entryCategoryFilter');
        const tag = $('entryTagFilter');
        if (!cat || !tag) {
            return;
        }
        const catValue = cat.value;
        const tagValue = tag.value;
        cat.innerHTML = '<option value="">全部分类</option>';
        tag.innerHTML = '<option value="">全部标签</option>';
        (data.categories || []).forEach((row) => {
            const option = document.createElement('option');
            option.value = row.name;
            option.textContent = row.name + '（' + row.cnt + '）';
            cat.appendChild(option);
        });
        (data.tags || []).forEach((row) => {
            const option = document.createElement('option');
            option.value = row.name;
            option.textContent = row.name + '（' + row.cnt + '）';
            tag.appendChild(option);
        });
        cat.value = catValue;
        tag.value = tagValue;
    } catch (e) {
        // 标签字典拿不到不影响清单本身
    }
}

/* ---------------- 侧栏遮罩与 Esc ---------------- */

function initSidebarBackdrop() {
    const shell = $('appView');
    const backdrop = $('sidebarBackdrop');
    const closeSidebar = () => {
        shell.classList.remove('sidebar-open');
        $('menuBtn').setAttribute('aria-expanded', 'false');
    };
    if (backdrop) {
        backdrop.addEventListener('click', closeSidebar);
    }
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeSidebar();
            $('previewDrawer').hidden = true;
            $('diffDrawer').hidden = true;
        }
    });
}

/* ---------------- 初始化 ---------------- */

function initUiEnhance() {
    initTheme();
    initSidebarBackdrop();
    $('themeBtn').addEventListener('click', toggleTheme);
    $('previewCloseBtn').addEventListener('click', closePreviewDrawer);
    $('diffCloseBtn').addEventListener('click', () => { $('diffDrawer').hidden = true; });
    $('exportAuditBtn').addEventListener('click', () => exportDataset('audit', 'csv'));
    $('exportMissBtn').addEventListener('click', () => exportDataset('miss', 'csv'));
    $('exportDocsBtn').addEventListener('click', () => exportDataset('docs', 'csv'));
    $('exportSessionsBtn').addEventListener('click', () => exportDataset('sessions', 'md'));
    $('batchApproveBtn').addEventListener('click', () => batchReview('approved'));
    $('batchRejectBtn').addEventListener('click', () => batchReview('rejected'));
    $('batchVisibilityBtn').addEventListener('click', () => batchVisibility());
    $('refreshStarredBtn').addEventListener('click', () => loadStarred());
    $('entryCategoryFilter').addEventListener('change', () => loadEntriesWithTags());
    $('entryTagFilter').addEventListener('change', () => loadEntriesWithTags());
}

/** 分类/标签筛选：设置全局筛选值后复用 app.js 的 loadEntries，不重写清单渲染。 */
function loadEntriesWithTags() {
    entryCategoryFilterValue = $('entryCategoryFilter').value;
    entryTagFilterValue = $('entryTagFilter').value;
    loadEntries();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initUiEnhance);
} else {
    initUiEnhance();
}
