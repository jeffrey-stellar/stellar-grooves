(function() {
'use strict';

function csrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.getAttribute('content') : '';
}
function csrfHeaderName() {
    const meta = document.querySelector('meta[name="_csrf_header"]');
    return meta ? meta.getAttribute('content') : 'X-CSRF-TOKEN';
}
function csrfHeaders() { const h = {}; h[csrfHeaderName()] = csrfToken(); return h; }
function escapeHtml(str) {
    const d = document.createElement('div');
    d.appendChild(document.createTextNode(str || ''));
    return d.innerHTML;
}

let currentPage = 0;
let totalPages = 0;

async function loadStats() {
    try {
        const resp = await fetch('/api/v1/admin/stats');
        const data = await resp.json();
        document.getElementById('statUsers').textContent = data.totalUsers;
        document.getElementById('statFiles').textContent = data.totalFiles;
        document.getElementById('statPlaylists').textContent = data.totalPlaylists;
    } catch (e) { console.error(e); }
}

async function loadUsers(page) {
    try {
        const resp = await fetch(`/api/v1/admin/users?page=${page}&size=25`);
        const data = await resp.json();
        currentPage = data.page;
        totalPages = data.totalPages;

        const tbody = document.getElementById('usersBody');
        tbody.innerHTML = '';
        data.content.forEach(u => {
            const tr = document.createElement('tr');
            const roles = (u.roles || []).map(r => r.replace('ROLE_', '')).join(', ');
            tr.innerHTML = `
                <td>${escapeHtml(u.username)}</td>
                <td class="hide-xs">${escapeHtml(u.email || '')}</td>
                <td><span class="badge genre-OTHER">${roles}</span></td>
                <td class="text-end">${u.fileCount || 0}</td>
                <td class="hide-xs">${u.enabled ? '<span class="status-success">Active</span>' : '<span class="status-error">Disabled</span>'}</td>
                <td><button class="btn-action-sm btn-action-remove" data-id="${u.id}" data-name="${escapeHtml(u.username)}" aria-label="Delete user ${escapeHtml(u.username)}">&times;</button></td>
            `;
            tbody.appendChild(tr);
        });

        document.getElementById('pageInfo').textContent =
            `Page ${currentPage + 1} of ${Math.max(totalPages, 1)}`;
        document.getElementById('prevPage').disabled = currentPage === 0;
        document.getElementById('nextPage').disabled = currentPage >= totalPages - 1;
    } catch (e) { console.error(e); }
}

document.getElementById('usersBody').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-id]');
    if (!btn) return;
    const id = btn.dataset.id;
    const name = btn.dataset.name;
    if (!confirm(`Delete user "${name}" and all their data? This cannot be undone.`)) return;
    btn.disabled = true;
    try {
        const resp = await fetch(`/api/v1/admin/users/${id}`, { method: 'DELETE', headers: csrfHeaders() });
        if (resp.ok) {
            await loadStats();
            await loadUsers(currentPage);
        }
    } finally { btn.disabled = false; }
});

document.getElementById('prevPage').addEventListener('click', () => {
    if (currentPage > 0) loadUsers(currentPage - 1);
});
document.getElementById('nextPage').addEventListener('click', () => {
    if (currentPage < totalPages - 1) loadUsers(currentPage + 1);
});

// Theme toggle (shared logic with app.js)
(function initTheme() {
    const saved = localStorage.getItem('sg-theme');
    const btn = document.getElementById('themeToggle');
    function applyTheme(theme) {
        if (theme === 'light') { document.documentElement.setAttribute('data-theme', 'light'); if (btn) btn.textContent = '\u263E'; }
        else if (theme === 'dark') { document.documentElement.setAttribute('data-theme', 'dark'); if (btn) btn.textContent = '\u2606'; }
        else { document.documentElement.removeAttribute('data-theme'); if (btn) btn.textContent = window.matchMedia('(prefers-color-scheme: dark)').matches ? '\u2606' : '\u263E'; }
    }
    applyTheme(saved);
    if (btn) btn.addEventListener('click', () => { const c = document.documentElement.getAttribute('data-theme'); const n = c === 'light' ? 'dark' : 'light'; localStorage.setItem('sg-theme', n); applyTheme(n); });
})();

loadStats();
loadUsers(0);
})();
