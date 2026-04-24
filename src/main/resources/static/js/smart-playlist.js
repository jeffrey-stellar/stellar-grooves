/**
 * Smart Playlists UI — sidebar list, detail view, preview/save/materialize.
 * Depends on SG globals from app.js: csrfHeaders, showToast, navigate, escapeHtml, guardClick.
 */
(function() {
'use strict';

const SG = window.SG;

// Ceiling on how many tracks we pull for "Play" — keeps the first fetch
// cheap and the player queue sane. Materialize is the tool for huge sets.
const PLAY_MAX = 500;
// Debounce for the live-count-while-typing request.
const COUNT_DEBOUNCE_MS = 350;

let smartPlaylists = [];
let currentMatches = [];
let countAbort = null;
let countTimer = null;
// When Duplicate is clicked we navigate to a new (unsaved) smart playlist
// and need to seed the form with the cloned values instead of the default
// "rating:>=4" starter. One-shot: consumed by renderView() on next render.
let pendingClone = null;

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
    // Alphabetical sort \u2014 the server returns in creation order, which gets
    // unusable as soon as a user has more than a handful. Shallow copy so
    // other code can keep relying on the original list order if needed.
    const sorted = [...smartPlaylists].sort((a, b) =>
        (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }));
    sorted.forEach(sp => {
        const li = document.createElement('li');
        const isActive = nav.view === 'smartPlaylist' && nav.smartPlaylistId === sp.id;
        li.className = 'playlist-item' + (isActive ? ' active' : '');
        const tip = sp.updatedAt
            ? `${sp.name}\nUpdated ${new Date(sp.updatedAt).toISOString().slice(0, 10)}`
            : sp.name;
        li.innerHTML =
            `<span class="playlist-item-name" title="${SG.escapeHtml(tip)}">${SG.escapeHtml(sp.name)}</span>` +
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
    const duplicateBtn = document.getElementById('spDuplicateBtn');
    const materializeBtn = document.getElementById('spMaterializeBtn');

    resultsEl.innerHTML = '';
    matchCountEl.textContent = '';
    statusEl.textContent = '';
    currentMatches = [];
    setQueryInvalid(false);
    if (countAbort) countAbort.abort();
    if (countTimer) clearTimeout(countTimer);

    const playBtn = document.getElementById('spPlayBtn');
    if (playBtn) playBtn.disabled = true;

    if (nav.smartPlaylistId) {
        const existing = smartPlaylists.find(sp => sp.id === nav.smartPlaylistId);
        if (!existing) {
            // Not loaded yet or deleted — fall back to new
            nameEl.value = '';
            queryEl.value = '';
            deleteBtn.classList.add('d-none');
            if (duplicateBtn) duplicateBtn.classList.add('d-none');
            materializeBtn.disabled = true;
            return;
        }
        nameEl.value = existing.name;
        queryEl.value = existing.queryString;
        deleteBtn.classList.remove('d-none');
        if (duplicateBtn) duplicateBtn.classList.remove('d-none');
        materializeBtn.disabled = false;
    } else if (pendingClone) {
        nameEl.value = pendingClone.name;
        queryEl.value = pendingClone.query;
        deleteBtn.classList.add('d-none');
        if (duplicateBtn) duplicateBtn.classList.add('d-none');
        materializeBtn.disabled = true;
        pendingClone = null;
    } else {
        nameEl.value = '';
        queryEl.value = 'rating:>=4';
        deleteBtn.classList.add('d-none');
        if (duplicateBtn) duplicateBtn.classList.add('d-none');
        materializeBtn.disabled = true;
    }
    // Kick an initial count so users see the match total on load
    // (programmatic value assignment doesn't fire the 'input' event).
    scheduleLiveCount();
}

async function preview() {
    const query = document.getElementById('spQuery').value.trim();
    const matchCountEl = document.getElementById('spMatchCount');
    const resultsEl = document.getElementById('spResultsBody');
    const statusEl = document.getElementById('spStatus');
    const truncatedEl = document.getElementById('spTruncated');

    if (!query) {
        statusEl.textContent = 'Enter a query to preview.';
        statusEl.className = 'sp-status status-error';
        return;
    }

    statusEl.textContent = 'Running…';
    statusEl.className = 'sp-status';
    if (truncatedEl) truncatedEl.hidden = true;
    try {
        const r = await fetch('/api/v1/smart-playlists/preview?page=0&size=100', {
            method: 'POST',
            headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ queryString: query })
        });
        const data = await r.json();
        if (!r.ok) {
            setQueryInvalid(true, data.detail || data.error || 'Query failed');
            matchCountEl.textContent = '';
            resultsEl.innerHTML = '';
            return;
        }
        currentMatches = data.content || [];
        matchCountEl.textContent = `${data.totalElements} match${data.totalElements === 1 ? '' : 'es'}` +
                (data.totalElements > currentMatches.length ? ` (showing ${currentMatches.length})` : '');
        if (truncatedEl) truncatedEl.hidden = !data.truncated;
        resultsEl.innerHTML = '';
        currentMatches.forEach(t => resultsEl.appendChild(buildResultRow(t)));
        const playBtn = document.getElementById('spPlayBtn');
        if (playBtn) playBtn.disabled = currentMatches.length === 0;
        statusEl.textContent = '';
        setQueryInvalid(false);
    } catch (e) {
        statusEl.textContent = 'Network error';
        statusEl.className = 'sp-status status-error';
    }
}

function buildResultRow(t) {
    const tr = document.createElement('tr');
    tr.dataset.fileId = t.id;
    const tdPlay = document.createElement('td');
    tdPlay.className = 'col-play';
    const btn = document.createElement('button');
    btn.className = 'btn-play-row';
    btn.setAttribute('aria-label', 'Play ' + (t.title || ''));
    btn.textContent = '\u25B6';
    btn.addEventListener('click', () => playFromMatch(t));
    tdPlay.appendChild(btn);
    tr.appendChild(tdPlay);
    tr.insertAdjacentHTML('beforeend',
        `<td>${SG.escapeHtml(t.title || '\u2014')}</td>` +
        `<td>${SG.escapeHtml(t.artist || '\u2014')}</td>` +
        `<td class="hide-xs">${SG.escapeHtml(t.album || '\u2014')}</td>` +
        `<td>${SG.escapeHtml(t.genre || '\u2014')}</td>` +
        `<td class="hide-xs">${t.rating ? '\u2605'.repeat(t.rating) : ''}</td>` +
        `<td class="hide-xs">${SG.escapeHtml(t.year || '\u2014')}</td>`);
    return tr;
}

// ── Play ───────────────────────────────────────────────
// Fetch up to PLAY_MAX matches fresh (sort+limit in the DSL are respected
// server-side), set playlist context so next/shuffle/up-next work, and
// kick off playback on the first track.
async function playAll() {
    const nav = window.nav || {};
    const query = document.getElementById('spQuery').value.trim();
    const statusEl = document.getElementById('spStatus');
    if (!query) return;
    try {
        const r = await fetch(`/api/v1/smart-playlists/preview?page=0&size=${PLAY_MAX}`, {
            method: 'POST',
            headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ queryString: query })
        });
        const data = await r.json();
        if (!r.ok) {
            statusEl.textContent = data.detail || data.error || 'Query failed';
            statusEl.className = 'sp-status status-error';
            return;
        }
        const tracks = data.content || [];
        if (tracks.length === 0) {
            SG.showToast('No matches to play', 'error');
            return;
        }
        const ctxId = nav.smartPlaylistId ? ('smart:' + nav.smartPlaylistId) : 'smart:adhoc';
        const name = (document.getElementById('spName').value || 'Smart Playlist').trim();
        SG.setPlaylistContext(ctxId, name, tracks);
        SG.playTrack(tracks[0]);
    } catch (e) {
        statusEl.textContent = 'Network error';
        statusEl.className = 'sp-status status-error';
    }
}

function playFromMatch(track) {
    // Single-row play: reuse the already-loaded preview as context,
    // so next/shuffle stays within the smart-playlist results.
    const nav = window.nav || {};
    if (currentMatches.length > 0) {
        const ctxId = nav.smartPlaylistId ? ('smart:' + nav.smartPlaylistId) : 'smart:adhoc';
        const name = (document.getElementById('spName').value || 'Smart Playlist').trim();
        SG.setPlaylistContext(ctxId, name, currentMatches);
    }
    SG.playTrack(track);
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

// ── Live count (debounced while typing) ─────────────────
// The /count endpoint is cheap (no full page, just a Mongo count).
// We use it to give immediate feedback on query edits without committing
// to a full preview render. Preview's "X matches (showing Y)" stays the
// authoritative display after the user clicks Preview; this updates the
// same slot while they're still editing.
function scheduleLiveCount() {
    if (countTimer) clearTimeout(countTimer);
    countTimer = setTimeout(runLiveCount, COUNT_DEBOUNCE_MS);
}

async function runLiveCount() {
    const queryEl = document.getElementById('spQuery');
    const matchCountEl = document.getElementById('spMatchCount');
    if (!queryEl || !matchCountEl) return;
    const query = queryEl.value.trim();

    if (countAbort) countAbort.abort();
    if (!query) {
        matchCountEl.textContent = '';
        setQueryInvalid(false);
        return;
    }
    countAbort = new AbortController();
    try {
        const r = await fetch('/api/v1/smart-playlists/count', {
            method: 'POST',
            headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ queryString: query }),
            signal: countAbort.signal
        });
        const data = await r.json();
        if (!r.ok) {
            // Parse/validation error — mark the field invalid and surface the
            // message quietly on the status line. Count slot gets a muted dash.
            matchCountEl.textContent = '—';
            setQueryInvalid(true, data.detail || data.error);
            return;
        }
        const n = data.count;
        matchCountEl.textContent = `${n} match${n === 1 ? '' : 'es'}`;
        setQueryInvalid(false);
    } catch (e) {
        if (e.name === 'AbortError') return;
        matchCountEl.textContent = '';
    }
}

// Toggle the Bootstrap is-invalid state on #spQuery. When invalid, we also
// put the parser message in the status line and flip aria-invalid for SRs.
// Preview success resets both — a single source of truth for "is the current
// query well-formed".
function setQueryInvalid(invalid, message) {
    const queryEl = document.getElementById('spQuery');
    const statusEl = document.getElementById('spStatus');
    if (!queryEl) return;
    if (invalid) {
        queryEl.classList.add('is-invalid');
        queryEl.setAttribute('aria-invalid', 'true');
        if (message && statusEl) {
            statusEl.textContent = message;
            statusEl.className = 'sp-status status-error';
        }
    } else {
        queryEl.classList.remove('is-invalid');
        queryEl.removeAttribute('aria-invalid');
    }
}

// Duplicate: clone the current editor state into a new unsaved smart
// playlist. Uses live form values (not the stored record) so any in-flight
// edits come along for the ride — matches how users expect copy/paste to
// behave in a form.
function duplicate() {
    const nameEl = document.getElementById('spName');
    const queryEl = document.getElementById('spQuery');
    if (!nameEl || !queryEl) return;
    const baseName = nameEl.value.trim() || 'Untitled';
    pendingClone = {
        name: `${baseName} (copy)`,
        query: queryEl.value
    };
    SG.navigate({ view: 'smartPlaylist', smartPlaylistId: null });
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
    const dupBtn = document.getElementById('spDuplicateBtn');
    if (dupBtn) dupBtn.addEventListener('click', duplicate);
    const playBtn = document.getElementById('spPlayBtn');
    if (playBtn) playBtn.addEventListener('click', () => SG.guardClick(playBtn, playAll));
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
        query.addEventListener('input', scheduleLiveCount);
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
