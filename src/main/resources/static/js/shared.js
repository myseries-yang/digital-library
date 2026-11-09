export const byId = id => document.getElementById(id);

export async function api(path, options = {}) {
    const headers = {Accept: 'application/json'};
    if (options.accountId) headers['X-Library-Account-Id'] = String(options.accountId);
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, {
        method: options.method || 'GET', headers, cache: 'no-store',
        body: options.body === undefined ? undefined : JSON.stringify(options.body)
    });
    let body;
    try { body = await response.json(); }
    catch { body = {detail: '服务返回了无法识别的响应'}; }
    if (!response.ok) {
        const error = new Error(body.detail || '请求未完成');
        error.code = body.code;
        error.requestId = body.requestId || response.headers.get('X-Request-Id');
        error.status = response.status;
        throw error;
    }
    return body;
}

export function notice(message, kind = 'success') {
    const element = byId('notice');
    element.hidden = false;
    element.className = 'notice' + (kind === 'success' ? '' : ' is-' + kind);
    element.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    element.textContent = message;
}

export function showError(error) {
    let message = error.message || '网络暂时不可用，请刷新数据确认';
    if (error.requestId) message += '（请求编号：' + error.requestId + '）';
    notice(message, 'error');
}

export function dateTime(value) {
    if (!value) return '—';
    return new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', hour12: false
    }).format(new Date(value));
}

export function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined && text !== null) node.textContent = String(text);
    if (className) node.className = className;
    return node;
}

export function emptyRows(target, columns, text) {
    const row = element('tr');
    const cell = element('td', text, 'empty');
    cell.colSpan = columns;
    row.append(cell);
    target.replaceChildren(row);
}

export function pagination(prefix, page, data) {
    byId(prefix + '-page').textContent = '第 ' + (page + 1) + ' 页';
    byId(prefix + '-prev').disabled = page === 0;
    byId(prefix + '-next').disabled = !data.hasNext;
}

export function loanBadge(loan) {
    return element('span', loan.returnedAt ? '已归还' : loan.overdue ? '逾期未还' : '借阅中',
        'pill ' + (loan.returnedAt ? 'returned' : loan.overdue ? 'overdue' : ''));
}
