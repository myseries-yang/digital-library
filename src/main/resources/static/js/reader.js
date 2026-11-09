import {api, byId, notice, showError, dateTime, element, emptyRows, pagination, loanBadge} from './shared.js';

const state = {accountId: null, accounts: [], policy: null, books: null, loans: null,
    bookPage: 0, loanPage: 0, keyword: '', busy: false, generation: 0};

async function refresh() {
    const generation = ++state.generation;
    const accountId = state.accountId;
    const params = new URLSearchParams({page: state.bookPage, size: 6, keyword: state.keyword});
    const [accounts, policy, books, loans] = await Promise.all([
        api('/api/accounts'), api('/api/borrowing-policy'), api('/api/books?' + params),
        api('/api/loans/current?page=' + state.loanPage + '&size=20', {accountId})
    ]);
    if (generation !== state.generation) return;
    Object.assign(state, {accounts, policy, books, loans});
    render();
}

function render() {
    const select = byId('account-select');
    select.replaceChildren(...state.accounts.map(account => {
        const option = element('option', account.accountNo + ' · ' + account.accountName);
        option.value = account.id;
        return option;
    }));
    select.value = String(state.accountId);
    select.disabled = state.busy;
    const account = state.accounts.find(value => value.id === state.accountId);
    byId('account-count').textContent = account.activeLoanCount;
    byId('account-limit').textContent = '/ ' + state.policy.maxActiveLoans + ' 本在借';
    byId('policy-note').textContent = '借期 ' + state.policy.loanDays + ' 天 · 同书最多一本 · 逾期仍占用额度';
    renderBooks(account);
    renderLoans();
}

function renderBooks(account) {
    const currentlyBorrowed = new Set(state.loans.items.map(loan => loan.bookId));
    const cards = state.books.items.map(book => {
        const card = element('article', null, 'book-card');
        const top = element('div', null, 'book-top');
        top.append(element('span', String(book.id).padStart(2, '0'), 'book-number'),
            element('span', book.availableLicenses > 0 ? '可借 ' + book.availableLicenses + ' / ' + book.totalLicenses : '暂无名额',
                'pill' + (book.availableLicenses ? '' : ' unavailable')));
        const actions = element('div', null, 'book-actions');
        const detail = element('button', '查看详情', 'text-button');
        detail.addEventListener('click', () => showDetail(book.id).catch(showError));
        const borrow = element('button', currentlyBorrowed.has(book.id) ? '已在借' : '借阅');
        borrow.disabled = state.busy || book.availableLicenses === 0 || currentlyBorrowed.has(book.id)
            || account.activeLoanCount >= state.policy.maxActiveLoans;
        borrow.addEventListener('click', () => write(
            () => api('/api/loans', {method: 'POST', accountId: state.accountId, body: {bookId: book.id}}),
            '借阅成功，图书已加入你的当前借阅。'));
        actions.append(detail, borrow);
        card.append(top, element('h3', book.title), element('p', book.author, 'book-author'),
            element('p', book.description || '暂无简介', 'book-description'), actions);
        return card;
    });
    byId('books').replaceChildren(...(cards.length ? cards : [element('p', '没有找到匹配的图书。', 'empty')]));
    pagination('books', state.bookPage, state.books);
}

function renderLoans() {
    const rows = state.loans.items.map(loan => {
        const row = element('tr');
        const title = element('td');
        title.append(element('strong', loan.bookTitle), element('small', '借阅 #' + loan.id));
        const action = element('td', null, 'right');
        const button = element('button', '归还', 'secondary');
        button.disabled = state.busy;
        button.addEventListener('click', () => write(
            () => api('/api/loans/' + loan.id + '/return', {method: 'POST', accountId: state.accountId}),
            '归还成功，借阅额度已释放。'));
        action.append(button);
        const status = element('td');
        status.append(loanBadge(loan));
        row.append(title, element('td', dateTime(loan.borrowedAt), 'time'),
            element('td', dateTime(loan.dueAt), 'time'), status, action);
        return row;
    });
    if (rows.length) byId('loan-rows').replaceChildren(...rows);
    else emptyRows(byId('loan-rows'), 5, '当前没有借阅，去发现一本好书吧。');
    pagination('loans', state.loanPage, state.loans);
}

async function write(action, successMessage) {
    if (state.busy) return;
    state.busy = true;
    render();
    let committed = false;
    try {
        await action();
        committed = true;
        notice(successMessage);
    } catch (error) { showError(error); }
    finally {
        state.busy = false;
        try { await refresh(); }
        catch (error) {
            if (committed) notice(successMessage + ' 列表暂时无法刷新，请点击“刷新数据”。', 'warning');
            else showError(error);
            render();
        }
    }
}

async function showDetail(id) {
    const book = await api('/api/books/' + id);
    byId('detail-title').textContent = book.title;
    byId('detail-author').textContent = book.author;
    byId('detail-description').textContent = book.description || '暂无简介';
    byId('detail-licenses').textContent = '当前可借 ' + book.availableLicenses + ' 本 / 总授权 ' + book.totalLicenses + ' 本';
    byId('book-dialog').showModal();
}

byId('account-select').addEventListener('change', event => {
    state.accountId = Number(event.target.value);
    localStorage.setItem('library-account-id', String(state.accountId));
    state.loanPage = 0;
    refresh().catch(showError);
});
byId('search-form').addEventListener('submit', event => {
    event.preventDefault(); state.keyword = byId('keyword').value.trim(); state.bookPage = 0;
    refresh().catch(showError);
});
for (const [id, field, change] of [
    ['books-prev', 'bookPage', -1], ['books-next', 'bookPage', 1],
    ['loans-prev', 'loanPage', -1], ['loans-next', 'loanPage', 1]
]) byId(id).addEventListener('click', () => { state[field] += change; refresh().catch(showError); });
byId('refresh').addEventListener('click', () => refresh().catch(showError));
byId('close-dialog').addEventListener('click', () => byId('book-dialog').close());

async function initialize() {
    const accounts = await api('/api/accounts');
    const saved = Number(localStorage.getItem('library-account-id'));
    state.accountId = accounts.some(account => account.id === saved) ? saved : accounts[0]?.id;
    if (!state.accountId) throw new Error('没有可用的图书馆账号');
    await refresh();
}
initialize().catch(showError);
