import {api, byId, notice, showError, dateTime, element, emptyRows, pagination, loanBadge} from './shared.js';

const state = {policy: null, page: 0, generation: 0, saving: false};

function fillPolicy(policy) {
    state.policy = policy;
    byId('max-loans').value = policy.maxActiveLoans;
    byId('loan-days').value = policy.loanDays;
    byId('policy-version').textContent = '当前规则版本 ' + policy.version;
}

async function loadRecords() {
    const generation = ++state.generation;
    const params = new URLSearchParams({page: state.page, size: 20, scope: byId('filter-scope').value});
    if (byId('filter-account').value) params.set('accountId', byId('filter-account').value);
    if (byId('filter-book').value) params.set('bookId', byId('filter-book').value);
    const data = await api('/api/admin/loans?' + params);
    if (generation !== state.generation) return;
    const rows = data.items.map(loan => {
        const row = element('tr');
        const account = element('td', loan.accountNo);
        account.append(element('small', loan.accountName));
        const status = element('td');
        status.append(loanBadge(loan));
        row.append(element('td', '#' + loan.id), account, element('td', loan.bookTitle),
            element('td', dateTime(loan.borrowedAt), 'time'), element('td', dateTime(loan.dueAt), 'time'),
            element('td', dateTime(loan.returnedAt), 'time'), status);
        return row;
    });
    if (rows.length) byId('loan-rows').replaceChildren(...rows);
    else emptyRows(byId('loan-rows'), 7, '没有符合条件的借阅记录。');
    pagination('loans', state.page, data);
}

byId('policy-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (state.saving || !state.policy) return;
    state.saving = true; byId('save-policy').disabled = true;
    try {
        const policy = await api('/api/admin/borrowing-policy', {method: 'PUT', body: {
            maxActiveLoans: Number(byId('max-loans').value), loanDays: Number(byId('loan-days').value),
            expectedVersion: state.policy.version
        }});
        fillPolicy(policy);
        notice('规则已保存。已有借阅的应还日期保持不变。');
    } catch (error) {
        showError(error);
        if (error.code === 'POLICY_VERSION_CONFLICT') {
            try { fillPolicy(await api('/api/borrowing-policy')); notice('规则已被其他操作修改，已加载最新值，请确认后重新编辑。', 'warning'); }
            catch { /* Preserve the original conflict explanation. */ }
        }
    } finally { state.saving = false; byId('save-policy').disabled = false; }
});

byId('filter-form').addEventListener('submit', event => {
    event.preventDefault(); state.page = 0; loadRecords().catch(showError);
});
byId('loans-prev').addEventListener('click', () => { state.page--; loadRecords().catch(showError); });
byId('loans-next').addEventListener('click', () => { state.page++; loadRecords().catch(showError); });
byId('refresh').addEventListener('click', async () => {
    try { fillPolicy(await api('/api/borrowing-policy')); await loadRecords(); }
    catch (error) { showError(error); }
});

async function initialize() {
    const [accounts, books, policy] = await Promise.all([
        api('/api/accounts'), api('/api/books?size=100'), api('/api/borrowing-policy')
    ]);
    for (const account of accounts) {
        const option = element('option', account.accountNo + ' · ' + account.accountName);
        option.value = account.id; byId('filter-account').append(option);
    }
    for (const book of books.items) {
        const option = element('option', book.title);
        option.value = book.id; byId('filter-book').append(option);
    }
    fillPolicy(policy);
    await loadRecords();
}
initialize().catch(showError);
