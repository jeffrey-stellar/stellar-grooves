(function() {
'use strict';

/**
 * Listening-history view: tabs for Recently Played / Top Tracks / Top Artists,
 * each filtered by an optional time window. Backed by /api/v1/library/history/*.
 * Clicking a track plays it; clicking an artist navigates to the albums view
 * scoped to that artist.
 */

const WINDOW_KEYS = ['all', 'week', 'month', 'year'];
const VALID_TABS = new Set(['recent', 'top-tracks', 'top-artists']);
const PAGE_SIZE = 50;

// ── Entry / render ─────────────────────────────────────────

async function renderHistoryView() {
    const tab = (SG.nav && SG.nav.historyTab) || 'recent';
    const window = (SG.nav && SG.nav.historyWindow) || 'all';

    document.getElementById('historyWindow').value = WINDOW_KEYS.includes(window) ? window : 'all';
    updateTabButtons(tab);

    const status = document.getElementById('historyStatus');
    const host = document.getElementById('historyContent');
    host.innerHTML = '';
    status.textContent = 'Loading…';

    try {
        if (tab === 'recent') await renderRecent(window, host, status);
        else if (tab === 'top-tracks') await renderTopTracks(window, host, status);
        else if (tab === 'top-artists') await renderTopArtists(window, host, status);
    } catch (e) {
        status.textContent = 'Failed to load history';
    }
}

function updateTabButtons(active) {
    document.querySelectorAll('[data-history-tab]').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.historyTab === active);
        btn.setAttribute('aria-selected', btn.dataset.historyTab === active ? 'true' : 'false');
    });
}

// ── Recently Played ───────────────────────────────────────

async function renderRecent(window, host, status) {
    const url = `/api/v1/library/history/recent?window=${encodeURIComponent(window)}&page=0&size=${PAGE_SIZE}`;
    const resp = await fetch(url, { headers: SG.csrfHeaders() });
    if (!resp.ok) { status.textContent = 'Failed to load recent plays'; return; }
    const data = await resp.json();
    const items = data.items || [];
    if (items.length === 0) {
        status.textContent = '';
        host.innerHTML = emptyState('\u23F3', 'No plays yet in this window', 'Play some tracks and they\u2019ll appear here.');
        return;
    }
    status.textContent = `${items.length} of ${data.total} play${data.total === 1 ? '' : 's'}`;
    host.appendChild(buildRecentTable(items));
}

function buildRecentTable(items) {
    const wrap = document.createElement('div');
    wrap.className = 'table-responsive';
    const table = document.createElement('table');
    table.className = 'table table-sm';
    table.setAttribute('aria-label', 'Recently played');
    table.innerHTML = `
        <thead><tr>
            <th scope="col" class="col-play"><span class="visually-hidden">Play</span></th>
            <th scope="col">When</th>
            <th scope="col">Title</th>
            <th scope="col">Artist</th>
            <th scope="col" class="hide-xs">Album</th>
            <th scope="col" class="hide-xs">Duration</th>
        </tr></thead>`;
    const tbody = document.createElement('tbody');
    items.forEach(entry => tbody.appendChild(buildRecentRow(entry)));
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
}

function buildRecentRow(entry) {
    const tr = document.createElement('tr');
    tr.dataset.fileId = entry.track.id;

    const tdPlay = document.createElement('td');
    tdPlay.className = 'col-play';
    const playBtn = document.createElement('button');
    playBtn.className = 'btn-play-row';
    playBtn.setAttribute('aria-label', 'Play ' + (entry.track.title || ''));
    playBtn.textContent = '\u25B6';
    playBtn.addEventListener('click', () => playFromHistory(entry.track));
    tdPlay.appendChild(playBtn);
    tr.appendChild(tdPlay);

    tr.appendChild(td(SGHelpers.formatRelativeTime(entry.playedAt), 'small text-secondary-themed'));
    tr.appendChild(td(entry.track.title || '\u2014'));
    tr.appendChild(td(entry.track.artist || '\u2014'));
    tr.appendChild(td(entry.track.album || '\u2014', 'hide-xs'));
    tr.appendChild(td(entry.completed ? 'Completed' : SGHelpers.formatMs(entry.listenedMs), 'hide-xs small'));
    return tr;
}

// ── Top Tracks ────────────────────────────────────────────

async function renderTopTracks(window, host, status) {
    const url = `/api/v1/library/history/top-tracks?window=${encodeURIComponent(window)}&limit=50`;
    const resp = await fetch(url, { headers: SG.csrfHeaders() });
    if (!resp.ok) { status.textContent = 'Failed to load top tracks'; return; }
    const data = await resp.json();
    const items = data.items || [];
    if (items.length === 0) {
        status.textContent = '';
        host.innerHTML = emptyState('\u2B50', 'No plays yet in this window', 'Top tracks will show once you play some music.');
        return;
    }
    status.textContent = `Top ${items.length} track${items.length === 1 ? '' : 's'}`;

    const wrap = document.createElement('div');
    wrap.className = 'table-responsive';
    const table = document.createElement('table');
    table.className = 'table table-sm';
    table.setAttribute('aria-label', 'Top tracks');
    table.innerHTML = `
        <thead><tr>
            <th scope="col" class="col-play"><span class="visually-hidden">Play</span></th>
            <th scope="col">#</th>
            <th scope="col">Title</th>
            <th scope="col">Artist</th>
            <th scope="col" class="hide-xs">Album</th>
            <th scope="col" class="text-end">Plays</th>
        </tr></thead>`;
    const tbody = document.createElement('tbody');
    items.forEach((entry, i) => tbody.appendChild(buildTopTrackRow(entry, i + 1)));
    table.appendChild(tbody);
    wrap.appendChild(table);
    host.appendChild(wrap);
}

function buildTopTrackRow(entry, rank) {
    const tr = document.createElement('tr');
    tr.dataset.fileId = entry.track.id;

    const tdPlay = document.createElement('td');
    tdPlay.className = 'col-play';
    const playBtn = document.createElement('button');
    playBtn.className = 'btn-play-row';
    playBtn.setAttribute('aria-label', 'Play ' + (entry.track.title || ''));
    playBtn.textContent = '\u25B6';
    playBtn.addEventListener('click', () => playFromHistory(entry.track));
    tdPlay.appendChild(playBtn);
    tr.appendChild(tdPlay);

    tr.appendChild(td(String(rank), 'small text-secondary-themed'));
    tr.appendChild(td(entry.track.title || '\u2014'));
    tr.appendChild(td(entry.track.artist || '\u2014'));
    tr.appendChild(td(entry.track.album || '\u2014', 'hide-xs'));
    tr.appendChild(td(String(entry.plays), 'text-end'));
    return tr;
}

// ── Top Artists ───────────────────────────────────────────

async function renderTopArtists(window, host, status) {
    const url = `/api/v1/library/history/top-artists?window=${encodeURIComponent(window)}&limit=50`;
    const resp = await fetch(url, { headers: SG.csrfHeaders() });
    if (!resp.ok) { status.textContent = 'Failed to load top artists'; return; }
    const data = await resp.json();
    const items = data.items || [];
    if (items.length === 0) {
        status.textContent = '';
        host.innerHTML = emptyState('\uD83C\uDFB8', 'No plays yet in this window', 'Top artists will show once you play some music.');
        return;
    }
    status.textContent = `Top ${items.length} artist${items.length === 1 ? '' : 's'}`;

    const wrap = document.createElement('div');
    wrap.className = 'table-responsive';
    const table = document.createElement('table');
    table.className = 'table table-sm';
    table.setAttribute('aria-label', 'Top artists');
    table.innerHTML = `
        <thead><tr>
            <th scope="col">#</th>
            <th scope="col">Artist</th>
            <th scope="col" class="text-end">Plays</th>
        </tr></thead>`;
    const tbody = document.createElement('tbody');
    items.forEach((entry, i) => {
        const tr = document.createElement('tr');
        tr.className = 'drill-row';
        tr.setAttribute('role', 'button');
        tr.setAttribute('tabindex', '0');
        const go = () => SG.navigate({ view: 'albums', artist: entry.artist });
        tr.addEventListener('click', go);
        tr.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
        tr.appendChild(td(String(i + 1), 'small text-secondary-themed'));
        const artistCell = document.createElement('td');
        const drill = document.createElement('span');
        drill.className = 'drill-link';
        drill.textContent = entry.artist;
        artistCell.appendChild(drill);
        tr.appendChild(artistCell);
        tr.appendChild(td(String(entry.plays), 'text-end'));
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
    host.appendChild(wrap);
}

// ── Helpers ───────────────────────────────────────────────

function td(text, className) {
    const cell = document.createElement('td');
    if (className) cell.className = className;
    cell.textContent = text;
    return cell;
}

function emptyState(icon, title, subtitle) {
    return `<div class="empty-state"><div class="empty-state-icon">${icon}</div><p><strong>${title}</strong><br><span class="small text-secondary-themed">${subtitle}</span></p></div>`;
}

// Relative-time / duration formatters live in helpers.js (SGHelpers) \u2014 see
// formatRelativeTime() and formatMs() there. Referenced at the call sites above.

/**
 * Play a track from history. The DTO we receive from /history/* has all the fields
 * the player needs, but the shared allFiles cache may not include it (e.g. soft-deleted).
 * SG.playTrack accepts a file-shaped object.
 */
function playFromHistory(track) {
    if (typeof SG.playTrack === 'function') SG.playTrack(track);
}

// ── Wire DOM once ─────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const historyBtn = document.getElementById('showHistoryBtn');
    if (historyBtn) historyBtn.addEventListener('click', (e) => {
        e.preventDefault();
        SG.navigate({ view: 'history', historyTab: 'recent', historyWindow: 'all' });
    });

    document.querySelectorAll('[data-history-tab]').forEach(btn => {
        btn.addEventListener('click', () => {
            const tab = btn.dataset.historyTab;
            if (!VALID_TABS.has(tab)) return;
            SG.navigate({ view: 'history', historyTab: tab, historyWindow: (SG.nav && SG.nav.historyWindow) || 'all' });
        });
    });

    const windowSelect = document.getElementById('historyWindow');
    if (windowSelect) windowSelect.addEventListener('change', () => {
        const win = windowSelect.value;
        if (!WINDOW_KEYS.includes(win)) return;
        SG.navigate({ view: 'history', historyTab: (SG.nav && SG.nav.historyTab) || 'recent', historyWindow: win });
    });
});

// Expose entry point for app.js
window.SG = window.SG || {};
SG.renderHistoryView = renderHistoryView;

})();
