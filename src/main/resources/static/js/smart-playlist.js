/**
 * Smart Playlists UI — sidebar list, detail view, preview/save/materialize.
 * Depends on SG globals from app.js: csrfHeaders, showToast, navigate, escapeHtml, guardClick.
 */
(function() {
'use strict';

const SG = window.SG;

let smartPlaylists = [];
let currentMatches = [];

// ── Data loading ────────────────────────────────────────
async function loadSmartPlaylists() {
    try {
        const r = await fetch('/api/v1/smart-playlists');
        smartPlaylists = await r.json();
        renderSidebar();
    } catch (e) {
        console.error('Failed to load smart playlists', e);
    }
}

function renderSidebar() {
    const ul = document.getElementById('smartPlaylistList');
    if (!ul) return;
    ul.innerHTML = '';
    if (smartPlaylists.length === 0) {
        ul.innerHTML = '<li class="playlist-empty-hint">No smart playlists yet</li>';
        return;
    }
    const nav = window.nav || {};
    smartPlaylists.forEach(sp => {
        const li = document.createElement('li');
        const isActive = nav.view === 'smartPlaylist' && nav.smartPlaylistId === sp.id;
        li.className = 'playlist-item' + (isActive ? ' active' : '');
        li.innerHTML =
            `<span class="playlist-item-name" title="${SG.escapeHtml(sp.name)}">${SG.escapeHtml(sp.name)}</span>` +
            `<button class="playlist-item-del" data-id="${sp.id}" title="Delete smart playlist" ` +
            `aria-label="Delete smart playlist ${SG.escapeHtml(sp.name)}">\u2715</button>`;
        li.querySelector('.playlist-item-name').addEventListener('click', () => {
            SG.navigate({ view: 'smartPlaylist', smartPlaylistId: sp.id });
        });
        li.querySelector('.playlist-item-del').addEventListener('click', async function(e) {
            e.stopPropagation();
            if (!confirm(`Delete smart playlist "${sp.name}"?`)) return;
            await SG.guardClick(this, () => deleteSmartPlaylist(sp.id));
        });
        ul.appendChild(li);
    });
}

// ── Detail view ─────────────────────────────────────────
function renderView() {
    const nav = window.nav || {};
    const nameEl = document.getElementById('spName');
    const queryEl = document.getElementById('spQuery');
    const matchCountEl = document.getElementById('spMatchCount');
    const resultsEl = document.getElementById('spResultsBody');
    const statusEl = document.getElementById('spStatus');
    const deleteBtn = document.getElementById('spDeleteBtn');
    const materializeBtn = document.getElementById('spMaterializeBtn');

    resultsEl.innerHTML = '';
    matchCountEl.textContent = '';
    statusEl.textContent = '';
    currentMatches = [];

    if (nav.smartPlaylistId) {
        const existing = smartPlaylists.find(sp => sp.id === nav.smartPlaylistId);
        if (!existing) {
            // Not loaded yet or deleted — fall back to new
            nameEl.value = '';
            queryEl.value = '';
            deleteBtn.classList.add('d-none');
            materializeBtn.disabled = true;
            return;
        }
        nameEl.value = existing.name;
        queryEl.value = existing.queryString;
        deleteBtn.classList.remove('d-none');
        materializeBtn.disabled = false;
    } else {
        nameEl.value = '';
        queryEl.value = 'rating:>=4';
        deleteBtn.classList.add('d-none');
        materializeBtn.disabled = true;
    }
}

async function preview() {
    const query = document.getElementById('spQuery').value.trim();
    const matchCountEl = document.getElementById('spMatchCount');
    const resultsEl = document.getElementById('spResultsBody');
    const statusEl = document.getElementById('spStatus');

    if (!query) {
        statusEl.textContent = 'Enter a query to preview.';
        statusEl.className = 'sp-status status-error';
        return;
    }

    statusEl.textContent = 'Running…';
    statusEl.className = 'sp-status';
    try {
        const r = await fetch('/api/v1/smart-playlists/preview?page=0&size=100', {
            method: 'POST',
            headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ queryString: query })
        });
        const data = await r.json();
        if (!r.ok) {
            statusEl.textContent = data.detail || data.error || 'Query failed';
            statusEl.className = 'sp-status status-error';
            matchCountEl.textContent = '';
            resultsEl.innerHTML = '';
            return;
        }
        currentMatches = data.content || [];
        matchCountEl.textContent = `${data.totalElements} match${data.totalElements === 1 ? '' : 'es'}` +
                (data.totalElements > currentMatches.length ? ` (showing ${currentMatches.length})` : '');
        resultsEl.innerHTML = '';
        currentMatches.forEach(t => {
            const tr = document.createElement('tr');
            tr.innerHTML =
                `<td>${SG.escapeHtml(t.title || '\u2014')}</td>` +
                `<td>${SG.escapeHtml(t.artist || '\u2014')}</td>` +
                `<td class="hide-xs">${SG.escapeHtml(t.album || '\u2014')}</td>` +
                `<td>${SG.escapeHtml(t.genre || '\u2014')}</td>` +
                `<td class="hide-xs">${t.rating ? '\u2605'.repeat(t.rating) : ''}</td>` +
                `<td class="hide-xs">${SG.escapeHtml(t.year || '\u2014')}</td>`;
            resultsEl.appendChild(tr);
        });
        statusEl.textContent = '';
    } catch (e) {
        statusEl.textContent = 'Network error';
        statusEl.className = 'sp-status status-error';
    }
}

async function save() {
    const nav = window.nav || {};
    const name = document.getElementById('spName').value.trim();
    const query = document.getElementById('spQuery').value.trim();
    const statusEl = document.getElementById('spStatus');

    if (!name) { statusEl.textContent = 'Name is required'; statusEl.className = 'sp-status status-error'; return; }
    if (!query) { statusEl.textContent = 'Query is required'; statusEl.className = 'sp-status status-error'; return; }

    const payload = JSON.stringify({ name, queryString: query });
    const isUpdate = !!nav.smartPlaylistId;
    const url = isUpdate ? `/api/v1/smart-playlists/${nav.smartPlaylistId}` : '/api/v1/smart-playlists';
    const method = isUpdate ? 'PUT' : 'POST';

    try {
        const r = await fetch(url, {
            method,
            headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: payload
        });
        const data = await r.json();
        if (!r.ok) {
            statusEl.textContent = data.detail || data.error || 'Save failed';
            statusEl.className = 'sp-status status-error';
            return;
        }
        statusEl.textContent = isUpdate ? 'Saved' : 'Created';
        statusEl.className = 'sp-status status-success';
        await loadSmartPlaylists();
        if (!isUpdate) {
            SG.navigate({ view: 'smartPlaylist', smartPlaylistId: data.id });
        }
    } catch (e) {
        statusEl.textContent = 'Network error';
        statusEl.className = 'sp-status status-error';
    }
}

async function deleteSmartPlaylist(id) {
    const nav = window.nav || {};
    try {
        const r = await fetch(`/api/v1/smart-playlists/${id}`, {
            method: 'DELETE', headers: SG.csrfHeaders()
        });
        if (r.ok) {
            if (nav.view === 'smartPlaylist' && nav.smartPlaylistId === id) {
                SG.navigate({ view: 'library' });
            }
            await loadSmartPlaylists();
        } else {
            SG.showToast('Failed to delete smart playlist');
        }
    } catch (e) {
        SG.showToast('Failed to delete smart playlist');
    }
}

async function materialize() {
    const nav = window.nav || {};
    if (!nav.smartPlaylistId) return;
    const sp = smartPlaylists.find(x => x.id === nav.smartPlaylistId);
    if (!sp) return;
    const suggested = `${sp.name} — ${new Date().toISOString().slice(0, 10)}`;
    const name = prompt('Name for the new playlist:', suggested);
    if (!name || !name.trim()) return;

    const statusEl = document.getElementById('spStatus');
    try {
        const r = await fetch(
            `/api/v1/smart-playlists/${nav.smartPlaylistId}/materialize?name=${encodeURIComponent(name.trim())}`,
            { method: 'POST', headers: SG.csrfHeaders() }
        );
        const data = await r.json();
        if (!r.ok) {
            statusEl.textContent = data.detail || data.error || 'Materialize failed';
            statusEl.className = 'sp-status status-error';
            return;
        }
        statusEl.textContent = `Created playlist "${data.name}" with ${data.trackCount} tracks` +
                (data.truncated ? ' (capped)' : '');
        statusEl.className = 'sp-status status-success';
        // Refresh the regular playlist sidebar so the new one shows up
        if (typeof SG.loadPlaylists === 'function') await SG.loadPlaylists();
    } catch (e) {
        statusEl.textContent = 'Network error';
        statusEl.className = 'sp-status status-error';
    }
}

function toggleSyntaxHelp() {
    const panel = document.getElementById('spSyntaxHelp');
    panel.classList.toggle('d-none');
}

// ── Wire events once DOM is ready ───────────────────────
function init() {
    const newBtn = document.getElementById('showNewSmartPlaylistBtn');
    if (newBtn) newBtn.addEventListener('click', () => SG.navigate({ view: 'smartPlaylist', smartPlaylistId: null }));
    const saveBtn = document.getElementById('spSaveBtn');
    if (saveBtn) saveBtn.addEventListener('click', () => SG.guardClick(saveBtn, save));
    const previewBtn = document.getElementById('spPreviewBtn');
    if (previewBtn) previewBtn.addEventListener('click', () => SG.guardClick(previewBtn, preview));
    const delBtn = document.getElementById('spDeleteBtn');
    if (delBtn) delBtn.addEventListener('click', async () => {
        const nav = window.nav || {};
        if (!nav.smartPlaylistId) return;
        const sp = smartPlaylists.find(x => x.id === nav.smartPlaylistId);
        if (!sp) return;
        if (!confirm(`Delete smart playlist "${sp.name}"?`)) return;
        await SG.guardClick(delBtn, () => deleteSmartPlaylist(sp.id));
    });
    const matBtn = document.getElementById('spMaterializeBtn');
    if (matBtn) matBtn.addEventListener('click', () => SG.guardClick(matBtn, materialize));
    const helpBtn = document.getElementById('spSyntaxHelpBtn');
    if (helpBtn) helpBtn.addEventListener('click', toggleSyntaxHelp);

    const query = document.getElementById('spQuery');
    if (query) {
        query.addEventListener('keydown', e => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                preview();
            }
        });
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

// Export for app.js to call
SG.loadSmartPlaylists = loadSmartPlaylists;
SG.renderSmartPlaylistSidebar = renderSidebar;
SG.renderSmartPlaylistView = renderView;

})();
