(function() {
'use strict';

const GENRE_CLASSES = {
    CLASSIC_ROCK: 'genre-CLASSIC_ROCK', HARD_ROCK: 'genre-HARD_ROCK',
    HAIR_METAL: 'genre-HAIR_METAL', HEAVY_METAL: 'genre-HEAVY_METAL',
    THRASH_METAL: 'genre-THRASH_METAL', OTHER: 'genre-OTHER'
};
const GENRE_LABELS = {
    CLASSIC_ROCK: 'Classic Rock', HARD_ROCK: 'Hard Rock', HAIR_METAL: 'Hair Metal',
    HEAVY_METAL: 'Heavy Metal', THRASH_METAL: 'Thrash Metal', OTHER: 'Other'
};
const VIRTUAL_ROW_HEIGHT = 42, VIRTUAL_BUFFER = 20, VIRTUAL_THRESHOLD = 500;

// ── State ────────────────────────────────────────────────
let allFiles = [], sortState = { col: null, dir: 'asc' };
let nav = { view: 'library', artist: null, album: null, playlistId: null, playlistName: null };
let playlists = [], selectedFileForPlaylist = null, addToPlaylistModal = null;
let cachedVisibleTracks = [], currentFileId = null, shuffleEnabled = false;
let selectedIds = new Set(); // bulk selection
let queue = []; // play queue

const audioEl = document.getElementById('audioPlayer');
const playerBar = document.getElementById('jukeboxPlayer');
const eqCanvas = document.getElementById('eqCanvas');

// ── CSRF + Helpers ───────────────────────────────────────
function csrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.getAttribute('content') : '';
}
function csrfHeaderName() {
    const meta = document.querySelector('meta[name="_csrf_header"]');
    return meta ? meta.getAttribute('content') : 'X-CSRF-TOKEN';
}
function csrfHeaders(extra = {}) { const h = {}; h[csrfHeaderName()] = csrfToken(); return Object.assign(h, extra); }
function text(v) { return (v && v.trim()) ? v.trim() : '\u2014'; }
function escapeHtml(s) { const d = document.createElement('div'); d.appendChild(document.createTextNode(s || '')); return d.innerHTML; }
function genreBadge(g) { const s = document.createElement('span'); s.className = 'badge ' + (GENRE_CLASSES[g] || 'genre-OTHER'); s.textContent = GENRE_LABELS[g] || 'Other'; return s; }
function decadeFromYear(y) { if (!y || y.length < 4) return '\u2014'; const n = parseInt(y.substring(0, 4), 10); return isNaN(n) ? '\u2014' : Math.floor(n / 10) * 10 + 's'; }
async function guardClick(btn, fn) { if (btn.disabled) return; btn.disabled = true; try { await fn(); } finally { btn.disabled = false; } }
function showToast(message, type = 'error', durationMs = 3500) {
    const container = document.getElementById('toastContainer'); if (!container) return;
    const el = document.createElement('div'); el.className = 'toast-msg toast-' + type; el.textContent = message;
    container.appendChild(el);
    setTimeout(() => { el.classList.add('toast-fade-out'); el.addEventListener('animationend', () => el.remove()); }, durationMs);
}
function fileById(id) { return allFiles.find(f => f.id === id); }
function fileForRow(tr) { return tr ? fileById(tr.dataset.fileId) : null; }
function formatTime(s) { if (isNaN(s) || s < 0) return '0:00'; return Math.floor(s / 60) + ':' + Math.floor(s % 60).toString().padStart(2, '0'); }

// ── Star rating widget ───────────────────────────────────
function buildStarRating(fileId, currentRating) {
    const div = document.createElement('span');
    div.className = 'star-rating';
    for (let i = 1; i <= 5; i++) {
        const btn = document.createElement('button');
        btn.className = 'star' + (i <= currentRating ? ' filled' : '');
        btn.textContent = '\u2605';
        btn.dataset.action = 'rate';
        btn.dataset.rating = i;
        btn.setAttribute('aria-label', `Rate ${i} star${i > 1 ? 's' : ''}`);
        div.appendChild(btn);
    }
    return div;
}

// ── Stats ────────────────────────────────────────────────
function updateStats() {
    const artists = new Set(allFiles.map(f => f.artist).filter(Boolean));
    const albums = new Set(allFiles.map(f => f.album).filter(Boolean));
    const genres = new Set(allFiles.map(f => f.genre || 'OTHER'));
    const mc = {};
    allFiles.forEach(f => { if (f.title && f.artist) { const k = f.title.toLowerCase() + '|' + f.artist.toLowerCase(); mc[k] = (mc[k] || 0) + 1; } });
    const dupes = Object.values(mc).reduce((s, n) => s + (n > 1 ? n : 0), 0);

    document.getElementById('statTotal').textContent = allFiles.length;
    document.getElementById('statArtists').textContent = artists.size;
    document.getElementById('statAlbums').textContent = albums.size;
    document.getElementById('statGenres').textContent = genres.size;
    document.getElementById('statDupes').textContent = dupes;
    const show = allFiles.length > 0;
    document.getElementById('statsRow').classList.toggle('d-none', !show);
    document.getElementById('clearBtn').classList.toggle('d-none', !show);

    // Populate artist/album filter dropdowns
    const af = document.getElementById('artistFilter');
    const prevArtist = af.value;
    af.innerHTML = '<option value="">All Artists</option>';
    [...artists].sort().forEach(a => { const o = document.createElement('option'); o.value = a; o.textContent = a; af.appendChild(o); });
    af.value = prevArtist;

    const abf = document.getElementById('albumFilter');
    const prevAlbum = abf.value;
    abf.innerHTML = '<option value="">All Albums</option>';
    [...albums].sort().forEach(a => { const o = document.createElement('option'); o.value = a; o.textContent = a; abf.appendChild(o); });
    abf.value = prevAlbum;
}

// ── Navigation ───────────────────────────────────────────
function navigate(newNav) { nav = newNav; selectedIds.clear(); updateBulkBar(); renderBreadcrumb(); renderCurrentView(); renderPlaylistSidebar(); }

function renderBreadcrumb() {
    const ol = document.getElementById('breadcrumbList'); ol.innerHTML = '';
    function crumb(label, fn) {
        const li = document.createElement('li'); li.className = 'breadcrumb-item' + (fn ? '' : ' active');
        if (fn) { const a = document.createElement('a'); a.href = '#'; a.textContent = label; a.addEventListener('click', e => { e.preventDefault(); fn(); }); li.appendChild(a); }
        else { li.textContent = label; li.setAttribute('aria-current', 'page'); }
        ol.appendChild(li);
    }
    const home = nav.view === 'library';
    const hc = home ? null : () => navigate({ view: 'library', artist: null, album: null });
    if (home) crumb('My Music Library', null);
    else if (nav.view === 'artists') { crumb('My Music Library', hc); crumb('Artists', null); }
    else if (nav.view === 'albums' && !nav.artist) { crumb('My Music Library', hc); crumb('Albums', null); }
    else if (nav.view === 'albums' && nav.artist) { crumb('My Music Library', hc); crumb('Artists', () => navigate({ view: 'artists' })); crumb(nav.artist, null); }
    else if (nav.view === 'tracks' && nav.album && nav.artist) { crumb('My Music Library', hc); crumb('Artists', () => navigate({ view: 'artists' })); crumb(nav.artist, () => navigate({ view: 'albums', artist: nav.artist })); crumb(nav.album, null); }
    else if (nav.view === 'tracks' && nav.album) { crumb('My Music Library', hc); crumb('Albums', () => navigate({ view: 'albums' })); crumb(nav.album, null); }
    else if (nav.view === 'playlist') { crumb('My Music Library', hc); crumb(nav.playlistName || 'Playlist', null); }
    else if (nav.view === 'duplicates') { crumb('My Music Library', hc); crumb('Duplicates', null); }
    else crumb('My Music Library', null);

    const showFilters = nav.view === 'library' || (nav.view === 'tracks' && !nav.album && !nav.artist);
    document.getElementById('trackFilters').classList.toggle('d-none', !showFilters);
}

// ── View rendering ───────────────────────────────────────
function renderCurrentView() {
    ['viewArtists','viewAlbums','viewTracks','viewPlaylist','viewDuplicates','emptyState'].forEach(id => document.getElementById(id).classList.add('d-none'));
    document.getElementById('bulkBar').classList.add('d-none');
    if (allFiles.length === 0) { document.getElementById('emptyState').classList.remove('d-none'); return; }
    if (nav.view === 'artists') { document.getElementById('viewArtists').classList.remove('d-none'); renderArtistsView(); }
    else if (nav.view === 'albums') { document.getElementById('viewAlbums').classList.remove('d-none'); renderAlbumsView(); }
    else if (nav.view === 'playlist') { document.getElementById('viewPlaylist').classList.remove('d-none'); renderPlaylistView(); }
    else if (nav.view === 'duplicates') { document.getElementById('viewDuplicates').classList.remove('d-none'); renderDuplicatesView(); }
    else { document.getElementById('viewTracks').classList.remove('d-none'); document.getElementById('bulkBar').classList.remove('d-none'); renderTracksView(); }
}

function renderArtistsView() {
    const m = {}; allFiles.forEach(f => { const a = f.artist || '(Unknown)'; if (!m[a]) m[a] = { t: 0, al: new Set() }; m[a].t++; if (f.album) m[a].al.add(f.album); });
    const tbody = document.getElementById('artistsBody'); tbody.innerHTML = '';
    Object.entries(m).sort((a, b) => a[0].localeCompare(b[0])).forEach(([artist, d]) => {
        const tr = document.createElement('tr'); tr.className = 'drill-row';
        tr.innerHTML = `<td><span class="drill-link">${escapeHtml(artist)}</span></td><td class="text-end hide-xs">${d.al.size}</td><td class="text-end">${d.t}</td>`;
        tr.addEventListener('click', () => navigate({ view: 'albums', artist, album: null }));
        tbody.appendChild(tr);
    });
}

let albumViewMode = 'grid';

function renderAlbumsView() {
    const src = nav.artist ? allFiles.filter(f => (f.artist || '(Unknown)') === nav.artist) : allFiles;
    const m = {}; src.forEach(f => {
        const al = f.album || '(Unknown)'; const ar = f.artist || '\u2014'; const k = al + '||' + ar;
        if (!m[k]) m[k] = { album: al, artist: ar, tracks: 0, coverFileId: null };
        m[k].tracks++;
        if (!m[k].coverFileId && f.hasCoverArt) m[k].coverFileId = f.id;
    });
    const sorted = Object.values(m).sort((a, b) => a.album.localeCompare(b.album));

    // Grid view
    const grid = document.getElementById('albumsGrid');
    const tableWrap = document.getElementById('albumsTableWrap');
    if (albumViewMode === 'grid') {
        grid.classList.remove('d-none'); tableWrap.classList.add('d-none');
        grid.innerHTML = '';
        sorted.forEach(({ album, artist, tracks, coverFileId }) => {
            const card = document.createElement('div'); card.className = 'album-card';
            if (coverFileId) {
                const img = document.createElement('img'); img.className = 'album-card-art';
                img.src = `/api/v1/library/files/${coverFileId}/cover`; img.alt = escapeHtml(album);
                img.loading = 'lazy';
                card.appendChild(img);
            } else {
                const ph = document.createElement('div'); ph.className = 'album-card-placeholder';
                ph.textContent = '\uD83C\uDFB5';
                card.appendChild(ph);
            }
            const info = document.createElement('div'); info.className = 'album-card-info';
            info.innerHTML = `<div class="album-card-title" title="${escapeHtml(album)}">${escapeHtml(album)}</div>`
                + `<div class="album-card-artist">${escapeHtml(artist)}</div>`
                + `<div class="album-card-count">${tracks} track${tracks !== 1 ? 's' : ''}</div>`;
            card.appendChild(info);
            card.addEventListener('click', () => navigate({ view: 'tracks', artist: nav.artist || artist, album }));
            grid.appendChild(card);
        });
    } else {
        grid.classList.add('d-none'); tableWrap.classList.remove('d-none');
        const tbody = document.getElementById('albumsBody'); tbody.innerHTML = '';
        sorted.forEach(({ album, artist, tracks }) => {
            const tr = document.createElement('tr'); tr.className = 'drill-row';
            tr.innerHTML = `<td><span class="drill-link">${escapeHtml(album)}</span></td><td class="hide-xs">${escapeHtml(artist)}</td><td class="text-end">${tracks}</td>`;
            tr.addEventListener('click', () => navigate({ view: 'tracks', artist: nav.artist || artist, album }));
            tbody.appendChild(tr);
        });
    }
}

// Album view toggle
document.getElementById('albumViewGrid').addEventListener('click', function() {
    albumViewMode = 'grid'; this.classList.add('active'); this.setAttribute('aria-pressed', 'true'); const other = document.getElementById('albumViewList'); other.classList.remove('active'); other.setAttribute('aria-pressed', 'false'); renderAlbumsView();
});
document.getElementById('albumViewList').addEventListener('click', function() {
    albumViewMode = 'list'; this.classList.add('active'); this.setAttribute('aria-pressed', 'true'); const other = document.getElementById('albumViewGrid'); other.classList.remove('active'); other.setAttribute('aria-pressed', 'false'); renderAlbumsView();
});

// ── Tracks view ──────────────────────────────────────────
function getVisibleTracks() {
    let f = allFiles;
    if (nav.artist) f = f.filter(x => (x.artist || '(Unknown)') === nav.artist);
    if (nav.album) f = f.filter(x => (x.album || '(Unknown)') === nav.album);
    if (!nav.artist && !nav.album) {
        const g = document.getElementById('genreFilter').value;
        if (g) f = f.filter(x => x.genre === g);
        const ar = document.getElementById('artistFilter').value;
        if (ar) f = f.filter(x => x.artist === ar);
        const al = document.getElementById('albumFilter').value;
        if (al) f = f.filter(x => x.album === al);
        const q = document.getElementById('searchInput').value.toLowerCase();
        if (q) f = f.filter(x => (x.title||'').toLowerCase().includes(q) || (x.artist||'').toLowerCase().includes(q) || (x.album||'').toLowerCase().includes(q));
    }
    if (sortState.col) {
        f = [...f].sort((a, b) => {
            let av, bv;
            if (sortState.col === 'rating') { av = a.rating || 0; bv = b.rating || 0; return sortState.dir === 'asc' ? av - bv : bv - av; }
            av = a[sortState.col] || ''; bv = b[sortState.col] || '';
            const c = av.localeCompare(bv); return sortState.dir === 'asc' ? c : -c;
        });
    }
    return f;
}

function buildTrackRow(file) {
    const tr = document.createElement('tr'); tr.dataset.fileId = file.id;
    if (currentFileId === file.id) tr.classList.add('table-active');

    // Checkbox
    const tdChk = document.createElement('td'); tdChk.className = 'col-check';
    const chk = document.createElement('input'); chk.type = 'checkbox'; chk.checked = selectedIds.has(file.id);
    chk.dataset.action = 'select'; chk.setAttribute('aria-label', 'Select ' + text(file.title));
    tdChk.appendChild(chk); tr.appendChild(tdChk);

    // Play
    const tdPlay = document.createElement('td'); tdPlay.className = 'col-play';
    const bp = document.createElement('button'); bp.className = 'btn-play-row' + (currentFileId === file.id ? ' playing' : '');
    bp.setAttribute('aria-label', 'Play ' + text(file.title)); bp.textContent = (currentFileId === file.id && !activeAudio.paused) ? '\u23F8' : '\u25B6';
    bp.dataset.action = 'play'; tdPlay.appendChild(bp); tr.appendChild(tdPlay);

    // Title, Artist, Album
    const tdT = document.createElement('td'); tdT.textContent = text(file.title); tr.appendChild(tdT);
    const tdAr = document.createElement('td'); tdAr.textContent = text(file.artist); tr.appendChild(tdAr);
    const tdAl = document.createElement('td'); tdAl.className = 'hide-xs'; tdAl.textContent = text(file.album); tr.appendChild(tdAl);

    // Genre
    const tdG = document.createElement('td');
    if (file.genre === 'OTHER') {
        const sel = document.createElement('select'); sel.className = 'form-select form-select-sm genre-select';
        sel.setAttribute('aria-label', 'Change genre'); sel.dataset.action = 'genre';
        Object.keys(GENRE_LABELS).forEach(k => { const o = document.createElement('option'); o.value = k; o.textContent = GENRE_LABELS[k]; if (k === file.genre) o.selected = true; sel.appendChild(o); });
        tdG.appendChild(sel);
    } else { tdG.appendChild(genreBadge(file.genre)); }
    tr.appendChild(tdG);

    // Rating
    const tdR = document.createElement('td'); tdR.className = 'hide-xs';
    tdR.appendChild(buildStarRating(file.id, file.rating || 0));
    tr.appendChild(tdR);

    // Decade
    const tdD = document.createElement('td'); tdD.className = 'hide-xs'; tdD.textContent = decadeFromYear(file.year); tr.appendChild(tdD);

    // Actions
    const tdA = document.createElement('td'); tdA.className = 'col-actions';
    const bq = document.createElement('button'); bq.className = 'btn-action-sm'; bq.setAttribute('aria-label', 'Add to queue'); bq.textContent = '\u23ED'; bq.dataset.action = 'queue'; tdA.appendChild(bq);
    const ba = document.createElement('button'); ba.className = 'btn-action-sm'; ba.setAttribute('aria-label', 'Add to playlist'); ba.textContent = '+'; ba.dataset.action = 'add-to-playlist'; tdA.appendChild(ba);
    const bd = document.createElement('button'); bd.className = 'btn-action-sm btn-action-remove'; bd.setAttribute('aria-label', 'Delete'); bd.textContent = '\u2715'; bd.dataset.action = 'delete'; tdA.appendChild(bd);
    tr.appendChild(tdA);
    return tr;
}

function updateTrackCount() {
    const el = document.getElementById('trackCount'); if (!el) return;
    const t = allFiles.length, v = cachedVisibleTracks.length;
    el.textContent = t === 0 ? '' : v === t ? `${t} tracks` : `Showing ${v} of ${t} tracks`;
}

function renderTracksView() {
    cachedVisibleTracks = getVisibleTracks();
    const tbody = document.getElementById('musicTableBody'); tbody.innerHTML = '';
    updateTrackCount();
    if (cachedVisibleTracks.length <= VIRTUAL_THRESHOLD) { cachedVisibleTracks.forEach(f => tbody.appendChild(buildTrackRow(f))); return; }
    renderVirtualSlice();
}

function renderVirtualSlice() {
    const c = document.getElementById('tracksScrollContainer'), tbody = document.getElementById('musicTableBody');
    const total = cachedVisibleTracks.length, st = c.scrollTop, vh = c.clientHeight;
    const si = Math.max(0, Math.floor(st / VIRTUAL_ROW_HEIGHT) - VIRTUAL_BUFFER);
    const ei = Math.min(total, Math.ceil((st + vh) / VIRTUAL_ROW_HEIGHT) + VIRTUAL_BUFFER);
    tbody.innerHTML = '';
    if (si > 0) { const sp = document.createElement('tr'); const td = document.createElement('td'); td.colSpan = 9; td.style.cssText = `height:${si * VIRTUAL_ROW_HEIGHT}px;padding:0;border:none;`; sp.appendChild(td); tbody.appendChild(sp); }
    for (let i = si; i < ei; i++) tbody.appendChild(buildTrackRow(cachedVisibleTracks[i]));
    const bh = (total - ei) * VIRTUAL_ROW_HEIGHT;
    if (bh > 0) { const sp = document.createElement('tr'); const td = document.createElement('td'); td.colSpan = 9; td.style.cssText = `height:${bh}px;padding:0;border:none;`; sp.appendChild(td); tbody.appendChild(sp); }
}

// ── Bulk selection ───────────────────────────────────────
function updateBulkBar() {
    document.getElementById('bulkCount').textContent = selectedIds.size + ' selected';
    const ha = document.getElementById('selectAllHead'); if (ha) ha.checked = selectedIds.size > 0 && selectedIds.size === cachedVisibleTracks.length;
}

document.getElementById('selectAllHead').addEventListener('change', function() {
    if (this.checked) cachedVisibleTracks.forEach(f => selectedIds.add(f.id));
    else selectedIds.clear();
    updateBulkBar(); renderTracksView();
});

document.getElementById('bulkDeleteBtn').addEventListener('click', async function() {
    if (selectedIds.size === 0) return;
    if (!confirm(`Delete ${selectedIds.size} selected track(s)?`)) return;
    this.classList.add('btn-loading');
    await guardClick(this, async () => {
        const resp = await fetch('/api/v1/library/files/bulk-delete', {
            method: 'POST', headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ fileIds: [...selectedIds] })
        });
        if (resp.ok) { allFiles = allFiles.filter(f => !selectedIds.has(f.id)); selectedIds.clear(); updateStats(); updateBulkBar(); renderTracksView(); await loadPlaylists(); }
        else showToast('Failed to delete selected tracks');
    });
    this.classList.remove('btn-loading');
});

document.getElementById('bulkPlaylistBtn').addEventListener('click', () => {
    if (selectedIds.size === 0) return;
    // Reuse the add-to-playlist modal for bulk
    selectedFileForPlaylist = { id: '__bulk__', title: `${selectedIds.size} tracks`, artist: '' };
    openAddToPlaylistModal(selectedFileForPlaylist);
});

// ── Event delegation: tracks tbody ───────────────────────
document.getElementById('musicTableBody').addEventListener('click', function(e) {
    const el = e.target.closest('[data-action]'); if (!el) return;
    const tr = el.closest('tr'), file = fileForRow(tr); if (!file && el.dataset.action !== 'select') return;
    switch (el.dataset.action) {
        case 'play': playTrack(file); break;
        case 'queue': addToQueue(file); break;
        case 'add-to-playlist': openAddToPlaylistModal(file); break;
        case 'delete': guardClick(el, async () => {
            if (!confirm(`Delete "${file.title || file.fileName}"?`)) return;
            const r = await fetch(`/api/v1/library/files/${file.id}`, { method: 'DELETE', headers: csrfHeaders() });
            if (r.ok) { allFiles = allFiles.filter(f => f.id !== file.id); selectedIds.delete(file.id); updateStats(); updateBulkBar(); renderCurrentView(); await loadPlaylists(); }
            else showToast('Failed to delete track');
        }); break;
        case 'rate': {
            const rating = parseInt(el.dataset.rating);
            const starWrap = el.closest('.star-rating'); if (starWrap) starWrap.classList.add('loading');
            fetch(`/api/v1/library/files/${file.id}/rating`, {
                method: 'PATCH', headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ rating })
            }).then(r => { if (r.ok) { const i = allFiles.findIndex(f => f.id === file.id); if (i !== -1) allFiles[i].rating = rating; } else showToast('Failed to update rating'); })
              .catch(() => showToast('Failed to update rating'))
              .finally(() => { if (starWrap) starWrap.classList.remove('loading'); renderTracksView(); });
        } break;
        case 'select': {
            if (el.checked) selectedIds.add(tr.dataset.fileId); else selectedIds.delete(tr.dataset.fileId);
            updateBulkBar();
        } break;
    }
});

document.getElementById('musicTableBody').addEventListener('change', function(e) {
    const sel = e.target.closest('[data-action="genre"]'); if (!sel) return;
    const file = fileForRow(sel.closest('tr')); if (!file) return;
    const ng = sel.value;
    sel.classList.add('loading');
    fetch(`/api/v1/library/files/${file.id}/genre`, { method: 'PATCH', headers: csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ genre: ng }) })
        .then(r => { if (r.ok) { const i = allFiles.findIndex(f => f.id === file.id); if (i !== -1) allFiles[i].genre = ng; renderTracksView(); } else { sel.value = file.genre; showToast('Failed to update genre'); } })
        .catch(() => { sel.value = file.genre; showToast('Failed to update genre'); })
        .finally(() => sel.classList.remove('loading'));
});

// ── Event delegation: playlist tbody (with drag-drop reorder) ──
const plBody = document.getElementById('playlistTracksBody');
plBody.addEventListener('click', function(e) {
    const el = e.target.closest('[data-action]'); if (!el) return;
    const tr = el.closest('tr'), fid = tr ? tr.dataset.fileId : null; if (!fid) return;
    if (el.dataset.action === 'play') playTrack(fileById(fid) || { id: fid });
    else if (el.dataset.action === 'remove') guardClick(el, async () => {
        const r = await fetch(`/api/v1/playlists/${nav.playlistId}/tracks/${fid}`, { method: 'DELETE', headers: csrfHeaders() });
        if (r.ok) { await loadPlaylists(); await renderPlaylistView(); }
        else showToast('Failed to remove track');
    });
});

// Drag-drop reorder for playlist
let dragRow = null;
plBody.addEventListener('dragstart', e => { dragRow = e.target.closest('tr'); if (dragRow) dragRow.classList.add('dragging'); });
plBody.addEventListener('dragend', () => { if (dragRow) dragRow.classList.remove('dragging'); document.querySelectorAll('.drag-over').forEach(r => r.classList.remove('drag-over')); dragRow = null; });
plBody.addEventListener('dragover', e => { e.preventDefault(); const tr = e.target.closest('tr'); if (tr && tr !== dragRow) { document.querySelectorAll('.drag-over').forEach(r => r.classList.remove('drag-over')); tr.classList.add('drag-over'); } });
plBody.addEventListener('drop', async e => {
    e.preventDefault();
    const target = e.target.closest('tr');
    if (!target || !dragRow || target === dragRow) return;
    // Reorder in DOM
    const rows = [...plBody.querySelectorAll('tr[data-file-id]')];
    const fromIdx = rows.indexOf(dragRow), toIdx = rows.indexOf(target);
    if (fromIdx < toIdx) target.after(dragRow); else target.before(dragRow);
    // Persist new order
    const newOrder = [...plBody.querySelectorAll('tr[data-file-id]')].map(r => r.dataset.fileId);
    await fetch(`/api/v1/playlists/${nav.playlistId}/tracks/reorder`, {
        method: 'PUT', headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ trackIds: newOrder })
    });
    await loadPlaylists();
});

// Virtual scroll
(function() { let rid = null; const c = document.getElementById('tracksScrollContainer'); if (c) c.addEventListener('scroll', () => { if (cachedVisibleTracks.length <= VIRTUAL_THRESHOLD || rid) return; rid = requestAnimationFrame(() => { rid = null; renderVirtualSlice(); }); }); })();

// ── Sort headers ─────────────────────────────────────────
document.querySelectorAll('th.col-sortable').forEach(th => {
    const si = th.querySelector('.sort-icon'); si.setAttribute('aria-hidden', 'true');
    th.setAttribute('role', 'button'); th.tabIndex = 0;
    function doSort() {
        const c = th.dataset.col;
        if (sortState.col === c) sortState.dir = sortState.dir === 'asc' ? 'desc' : 'asc';
        else { sortState.col = c; sortState.dir = 'asc'; }
        document.querySelectorAll('th.col-sortable').forEach(h => { const ic = h.querySelector('.sort-icon'); const a = h.dataset.col === sortState.col; ic.textContent = a ? (sortState.dir === 'asc' ? ' \u25B2' : ' \u25BC') : ''; h.setAttribute('aria-sort', a ? sortState.dir + 'ending' : 'none'); });
        renderTracksView();
    }
    th.addEventListener('click', doSort);
    th.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); doSort(); } });
});

// ── Stat card clicks ─────────────────────────────────────
document.getElementById('statCardTotal').addEventListener('click', () => navigate({ view: 'library' }));
document.getElementById('statCardArtists').addEventListener('click', () => navigate({ view: 'artists' }));
document.getElementById('statCardAlbums').addEventListener('click', () => navigate({ view: 'albums' }));
document.getElementById('statCardGenres').addEventListener('click', () => navigate({ view: 'library' }));
document.getElementById('statCardDupes').addEventListener('click', () => navigate({ view: 'duplicates' }));

// ── Advanced filters ─────────────────────────────────────
let searchDebounce = null;
['searchInput', 'genreFilter', 'artistFilter', 'albumFilter'].forEach(id => {
    document.getElementById(id).addEventListener(id === 'searchInput' ? 'input' : 'change', () => {
        if (nav.view === 'library' || (nav.view === 'tracks' && !nav.artist && !nav.album)) {
            if (id === 'searchInput') {
                clearTimeout(searchDebounce);
                const q = document.getElementById('searchInput').value.trim();
                if (q.length >= 2) {
                    searchDebounce = setTimeout(() => serverSearch(q), 300);
                } else {
                    renderTracksView();
                }
            } else {
                renderTracksView();
            }
        }
    });
});

async function serverSearch(query) {
    const searchInput = document.getElementById('searchInput');
    searchInput.classList.add('loading');
    try {
        const r = await fetch(`/api/v1/library/search?q=${encodeURIComponent(query)}&size=200`);
        if (!r.ok) return;
        const d = await r.json();
        cachedVisibleTracks = d.content;
        const tbody = document.getElementById('musicTableBody'); tbody.innerHTML = '';
        updateTrackCount();
        if (cachedVisibleTracks.length <= VIRTUAL_THRESHOLD) { cachedVisibleTracks.forEach(f => tbody.appendChild(buildTrackRow(f))); }
        else { renderVirtualSlice(); }
        document.getElementById('trackCount').textContent = `${d.totalElements} result${d.totalElements !== 1 ? 's' : ''} for "${query}"`;
    } catch (e) { console.error('Search failed', e); showToast('Search failed'); }
    finally { searchInput.classList.remove('loading'); }
}

// ── Duplicates view ──────────────────────────────────────
async function renderDuplicatesView() {
    const container = document.getElementById('duplicatesContent');
    container.innerHTML = '<p class="small" style="color:var(--text-muted);">Loading...</p>';
    try {
        const resp = await fetch('/api/v1/library/duplicates');
        const groups = await resp.json();
        container.innerHTML = '';
        if (groups.length === 0) { container.innerHTML = '<p style="color:var(--text-secondary);">No duplicates found.</p>'; return; }
        groups.forEach(g => {
            const div = document.createElement('div'); div.className = 'dupe-group';
            div.innerHTML = `<div class="dupe-group-header">${escapeHtml(g.title)} &mdash; ${escapeHtml(g.artist)}</div>`;
            g.files.forEach((f, i) => {
                const row = document.createElement('div'); row.className = 'dupe-file';
                const label = document.createElement('span'); label.textContent = f.fileName + (f.genre ? ' [' + (GENRE_LABELS[f.genre] || f.genre) + ']' : '');
                row.appendChild(label);
                if (i > 0) { // Keep first, offer delete on rest
                    const btn = document.createElement('button'); btn.className = 'btn-action-sm btn-action-remove'; btn.textContent = 'Delete';
                    btn.setAttribute('aria-label', 'Delete duplicate ' + f.fileName);
                    btn.addEventListener('click', async () => {
                        await guardClick(btn, async () => {
                            const r = await fetch(`/api/v1/library/files/${f.id}`, { method: 'DELETE', headers: csrfHeaders() });
                            if (r.ok) { allFiles = allFiles.filter(x => x.id !== f.id); updateStats(); renderDuplicatesView(); await loadPlaylists(); }
                        });
                    });
                    row.appendChild(btn);
                } else {
                    const keep = document.createElement('span'); keep.className = 'badge genre-OTHER'; keep.textContent = 'Keep'; row.appendChild(keep);
                }
                div.appendChild(row);
            });
            container.appendChild(div);
        });
    } catch (e) { container.innerHTML = '<p class="status-error">Failed to load duplicates.</p>'; }
}

// ── Queue management ─────────────────────────────────────
let _queueSaveTimer = null;
function saveQueue() {
    // Save to localStorage immediately for fast reload
    try {
        const minimal = queue.map(f => ({ id: f.id, title: f.title, artist: f.artist, hasCoverArt: f.hasCoverArt }));
        localStorage.setItem('sg-queue', JSON.stringify(minimal));
    } catch (e) { /* quota exceeded */ }
    // Debounce server sync (2s) to avoid spamming on rapid changes
    clearTimeout(_queueSaveTimer);
    _queueSaveTimer = setTimeout(syncQueueToServer, 2000);
}

async function syncQueueToServer() {
    try {
        const currentTrack = window._currentTrack || null;
        await fetch('/api/v1/library/queue', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
            body: JSON.stringify({
                trackIds: queue.map(f => f.id),
                currentTrackId: currentTrack ? currentTrack.id : null,
                shuffle: !!window._shuffleEnabled
            })
        });
    } catch (e) { /* server sync failed, localStorage still has it */ }
}

function addToQueue(file) {
    queue.push(file);
    renderQueue();
}

function renderQueue() {
    const ul = document.getElementById('queueList'); ul.innerHTML = '';
    const clearBtn = document.getElementById('clearQueueBtn');
    if (queue.length === 0) {
        ul.innerHTML = '<li class="playlist-empty-hint">Queue is empty</li>';
        clearBtn.style.display = 'none';
        saveQueue();
        return;
    }
    clearBtn.style.display = '';
    queue.forEach((f, i) => {
        const li = document.createElement('li'); li.className = 'queue-item';
        const title = document.createElement('span'); title.className = 'queue-item-title'; title.textContent = text(f.title) + (f.artist ? ' \u00B7 ' + f.artist : '');
        const rm = document.createElement('button'); rm.className = 'queue-item-remove'; rm.textContent = '\u2715'; rm.setAttribute('aria-label', 'Remove from queue');
        rm.addEventListener('click', () => { queue.splice(i, 1); renderQueue(); });
        li.appendChild(title); li.appendChild(rm); ul.appendChild(li);
    });
    saveQueue();
}

async function loadSavedQueue() {
    // Try server first, fall back to localStorage
    try {
        const r = await fetch('/api/v1/library/queue');
        if (r.ok) {
            const data = await r.json();
            if (data.trackIds && data.trackIds.length > 0) {
                // Resolve track IDs to full objects from allFiles
                queue = data.trackIds.map(id => allFiles.find(f => f.id === id)).filter(Boolean);
                if (queue.length > 0) { renderQueue(); return; }
            }
        }
    } catch (e) { /* server unavailable */ }
    // Fallback to localStorage
    try {
        const saved = localStorage.getItem('sg-queue');
        if (saved) { const parsed = JSON.parse(saved); if (Array.isArray(parsed) && parsed.length > 0) { queue = parsed; renderQueue(); } }
    } catch (e) { /* corrupted */ }
}

document.getElementById('clearQueueBtn').addEventListener('click', async () => {
    queue = []; renderQueue();
    try { await fetch('/api/v1/library/queue', { method: 'DELETE', headers: csrfHeaders() }); } catch (e) {}
});

// ── Scan ─────────────────────────────────────────────────
document.getElementById('scanForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const pv = document.getElementById('path').value.trim(), sd = document.getElementById('scanStatus'), btn = document.getElementById('scanBtn'), sp = document.getElementById('scanSpinner');
    if (!pv) { document.getElementById('path').classList.add('is-invalid'); return; }
    document.getElementById('path').classList.remove('is-invalid');
    btn.disabled = true; sp.classList.remove('d-none');
    const ss = document.createElement('span'); ss.className = 'status-scanning'; ss.textContent = '\u23F3 Scanning\u2026'; sd.replaceChildren(ss);

    // Connect SSE for live progress before starting the scan
    let eventSource = null;
    try {
        eventSource = new EventSource('/api/v1/library/scan/progress');
        eventSource.addEventListener('progress', (ev) => {
            try {
                const p = JSON.parse(ev.data);
                const msg = `\u23F3 Scanning\u2026 ${p.saved} imported, ${p.skipped} skipped, ${p.errors} error(s)`;
                ss.textContent = msg;
            } catch (_) {}
        });
        eventSource.addEventListener('complete', (ev) => {
            try { eventSource.close(); } catch (_) {}
        });
        eventSource.addEventListener('error', () => {
            try { eventSource.close(); } catch (_) {}
        });
    } catch (_) { /* SSE not available, will fall back to final result */ }

    try {
        const r = await fetch('/api/v1/library/scan', { method: 'POST', headers: csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ path: pv }) });
        const d = await r.json();
        if (r.ok) { let m = d.filesFound > 0 ? `\u2713 ${d.filesFound} imported.` : '\u2713 No new files.'; if (d.skipped > 0) m += ` ${d.skipped} skipped.`; if (d.errors > 0) m += ` ${d.errors} error(s).`; const s = document.createElement('span'); s.className = 'status-success'; s.textContent = m; sd.replaceChildren(s); await loadLibrary(); }
        else { const s = document.createElement('span'); s.className = 'status-error'; s.textContent = '\u2717 ' + (d.error || d.detail || 'Scan failed'); sd.replaceChildren(s); }
    } catch (er) { const s = document.createElement('span'); s.className = 'status-error'; s.textContent = '\u2717 Network error'; sd.replaceChildren(s); }
    finally { btn.disabled = false; sp.classList.add('d-none'); if (eventSource) try { eventSource.close(); } catch (_) {} }
});

// ── Clear library ────────────────────────────────────────
document.getElementById('clearBtn').addEventListener('click', async function() {
    if (!confirm('Clear your entire library? This cannot be undone.')) return;
    await guardClick(this, async () => {
        const r = await fetch('/api/v1/library/files', { method: 'DELETE', headers: csrfHeaders() });
        if (r.ok) { allFiles = []; playlists = []; queue = []; navigate({ view: 'library' }); updateStats(); renderPlaylistSidebar(); renderQueue(); const s = document.createElement('span'); s.className = 'status-success'; s.textContent = 'Library cleared.'; document.getElementById('scanStatus').replaceChildren(s); }
    });
});

// ── Playlists ────────────────────────────────────────────
async function loadPlaylists() { try { const r = await fetch('/api/v1/playlists'); playlists = await r.json(); renderPlaylistSidebar(); } catch (e) { console.error(e); } }

function renderPlaylistSidebar() {
    const ul = document.getElementById('playlistList'); ul.innerHTML = '';
    if (playlists.length === 0) { ul.innerHTML = '<li class="playlist-empty-hint">No playlists yet</li>'; return; }
    playlists.forEach(pl => {
        const li = document.createElement('li'); li.className = 'playlist-item' + (nav.playlistId === pl.id ? ' active' : '');
        li.innerHTML = `<span class="playlist-item-name" title="${escapeHtml(pl.name)}">${escapeHtml(pl.name)}</span><span class="playlist-item-count">${pl.trackCount}</span><button class="playlist-item-del" data-id="${pl.id}" title="Delete playlist" aria-label="Delete playlist ${escapeHtml(pl.name)}">\u2715</button>`;
        li.querySelector('.playlist-item-name').addEventListener('click', () => navigate({ view: 'playlist', playlistId: pl.id, playlistName: pl.name }));
        li.querySelector('.playlist-item-del').addEventListener('click', async function(e) { e.stopPropagation(); if (!confirm(`Delete playlist "${pl.name}"?`)) return; await guardClick(this, () => deletePlaylist(pl.id)); });
        ul.appendChild(li);
    });
}

async function createPlaylist(name) { try { const r = await fetch('/api/v1/playlists', { method: 'POST', headers: csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ name }) }); if (r.ok) { document.getElementById('newPlaylistName').value = ''; document.getElementById('newPlaylistForm').classList.add('d-none'); await loadPlaylists(); } else showToast('Failed to create playlist'); } catch (e) { showToast('Failed to create playlist'); } }
async function deletePlaylist(id) { try { const r = await fetch(`/api/v1/playlists/${id}`, { method: 'DELETE', headers: csrfHeaders() }); if (r.ok) { if (nav.playlistId === id) navigate({ view: 'library' }); await loadPlaylists(); } else showToast('Failed to delete playlist'); } catch (e) { showToast('Failed to delete playlist'); } }

function openAddToPlaylistModal(file) {
    selectedFileForPlaylist = file;
    document.getElementById('addToPlaylistTrackName').textContent = (file.title || '\u2014') + (file.artist ? ' \u00B7 ' + file.artist : '');
    const sel = document.getElementById('addToPlaylistSelect'); sel.innerHTML = '';
    if (playlists.length === 0) { const o = document.createElement('option'); o.textContent = 'No playlists'; o.disabled = true; sel.appendChild(o); }
    else playlists.forEach(pl => { const o = document.createElement('option'); o.value = pl.id; o.textContent = pl.name + ' (' + pl.trackCount + ')'; sel.appendChild(o); });
    document.getElementById('addToPlaylistStatus').textContent = '';
    if (!addToPlaylistModal) addToPlaylistModal = new bootstrap.Modal(document.getElementById('addToPlaylistModal'));
    addToPlaylistModal.show();
}

async function addToPlaylist(playlistId, file) {
    const st = document.getElementById('addToPlaylistStatus');
    try {
        if (file.id === '__bulk__') {
            // Bulk add
            for (const fid of selectedIds) {
                await fetch(`/api/v1/playlists/${playlistId}/tracks`, { method: 'POST', headers: csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ fileId: fid }) });
            }
            st.className = 'mt-2 small status-success'; st.textContent = `\u2713 ${selectedIds.size} tracks added!`;
        } else {
            const r = await fetch(`/api/v1/playlists/${playlistId}/tracks`, { method: 'POST', headers: csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ fileId: file.id }) });
            const d = await r.json();
            if (r.ok) { st.className = 'mt-2 small status-success'; st.textContent = '\u2713 Added!'; }
            else { st.className = 'mt-2 small status-error'; st.textContent = d.error || 'Failed'; return; }
        }
        await loadPlaylists();
        setTimeout(() => addToPlaylistModal && addToPlaylistModal.hide(), 800);
    } catch (e) { st.className = 'mt-2 small status-error'; st.textContent = 'Network error'; }
}

async function renderPlaylistView() {
    const tbody = document.getElementById('playlistTracksBody'), empty = document.getElementById('playlistEmptyState');
    tbody.innerHTML = ''; empty.classList.add('d-none');
    try {
        const r = await fetch(`/api/v1/playlists/${nav.playlistId}/tracks`); if (!r.ok) return;
        let tracks = await r.json();
        if (tracks.length === 0) { empty.classList.remove('d-none'); return; }

        // Apply sort
        const sortBy = document.getElementById('playlistSort').value;
        if (sortBy !== 'custom') {
            tracks = [...tracks].sort((a, b) => {
                if (sortBy === 'rating') return (b.rating || 0) - (a.rating || 0);
                if (sortBy === 'year') return (b.year || '').localeCompare(a.year || '');
                const av = a[sortBy] || '', bv = b[sortBy] || '';
                return av.localeCompare(bv);
            });
        }

        const isDraggable = sortBy === 'custom';
        tracks.forEach(file => {
            const tr = document.createElement('tr'); tr.dataset.fileId = file.id; tr.draggable = isDraggable;
            if (currentFileId === file.id) tr.classList.add('table-active');

            // Drag handle + play
            const tdP = document.createElement('td'); tdP.className = 'col-play';
            if (isDraggable) {
                const dh = document.createElement('span'); dh.className = 'drag-handle'; dh.textContent = '\u2630'; dh.setAttribute('aria-label', 'Drag to reorder');
                tdP.appendChild(dh);
            }
            const bp = document.createElement('button'); bp.className = 'btn-play-row' + (currentFileId === file.id ? ' playing' : '');
            bp.setAttribute('aria-label', 'Play ' + text(file.title)); bp.textContent = (currentFileId === file.id && !activeAudio.paused) ? '\u23F8' : '\u25B6'; bp.dataset.action = 'play';
            tdP.appendChild(bp); tr.appendChild(tdP);

            const tdT = document.createElement('td'); tdT.textContent = text(file.title); tr.appendChild(tdT);
            const tdAr = document.createElement('td'); tdAr.textContent = text(file.artist); tr.appendChild(tdAr);
            const tdAl = document.createElement('td'); tdAl.className = 'hide-xs'; tdAl.textContent = text(file.album); tr.appendChild(tdAl);
            const tdG = document.createElement('td'); tdG.appendChild(genreBadge(file.genre)); tr.appendChild(tdG);

            // Rating stars (read-only in playlist view)
            const tdR = document.createElement('td'); tdR.className = 'hide-xs';
            const stars = document.createElement('span'); stars.className = 'star-rating';
            for (let i = 1; i <= 5; i++) {
                const s = document.createElement('span');
                s.className = 'star' + (i <= (file.rating || 0) ? ' filled' : '');
                s.textContent = '\u2605';
                stars.appendChild(s);
            }
            tdR.appendChild(stars); tr.appendChild(tdR);

            const tdD = document.createElement('td'); tdD.className = 'hide-xs'; tdD.textContent = decadeFromYear(file.year); tr.appendChild(tdD);

            const tdA = document.createElement('td'); tdA.className = 'col-actions';
            const br = document.createElement('button'); br.className = 'btn-action-sm btn-action-remove'; br.setAttribute('aria-label', 'Remove'); br.textContent = '\u2715'; br.dataset.action = 'remove';
            tdA.appendChild(br); tr.appendChild(tdA);
            tbody.appendChild(tr);
        });
    } catch (e) { console.error(e); }
}

// ── Playlist sort ────────────────────────────────────────
document.getElementById('playlistSort').addEventListener('change', () => { if (nav.view === 'playlist') renderPlaylistView(); });

// ── Playlist export ──────────────────────────────────────
document.getElementById('exportJsonBtn').addEventListener('click', () => { if (nav.playlistId) window.location = `/api/v1/playlists/${nav.playlistId}/export?format=json`; });
document.getElementById('exportM3uBtn').addEventListener('click', () => { if (nav.playlistId) window.location = `/api/v1/playlists/${nav.playlistId}/export?format=m3u`; });

// ── Playlist UI ──────────────────────────────────────────
document.getElementById('showNewPlaylistBtn').addEventListener('click', () => { const f = document.getElementById('newPlaylistForm'); f.classList.toggle('d-none'); if (!f.classList.contains('d-none')) document.getElementById('newPlaylistName').focus(); });
document.getElementById('createPlaylistBtn').addEventListener('click', function() { const n = document.getElementById('newPlaylistName').value.trim(); if (n) guardClick(this, () => createPlaylist(n)); });
document.getElementById('newPlaylistName').addEventListener('keydown', e => { if (e.key === 'Enter') { const n = e.target.value.trim(); if (n) createPlaylist(n); } });
document.getElementById('addToPlaylistConfirm').addEventListener('click', async function() {
    const s = document.getElementById('addToPlaylistSelect');
    if (s.value && selectedFileForPlaylist) {
        this.classList.add('btn-loading');
        await guardClick(this, () => addToPlaylist(s.value, selectedFileForPlaylist));
        this.classList.remove('btn-loading');
    }
});

// ── Load library ─────────────────────────────────────────
async function loadLibrary() {
    try {
        allFiles = []; let p = 0, tp = 1;
        while (p < tp) { const r = await fetch(`/api/v1/library/files?page=${p}&size=200`); const d = await r.json(); allFiles = allFiles.concat(d.content); tp = d.totalPages; p++; }
        updateStats(); renderCurrentView(); await loadPlaylists();
    } catch (e) { console.error(e); }
}

// ── Audio Player ─────────────────────────────────────────
let crossfadeEnabled = false;
const CROSSFADE_DURATION = 3; // seconds
const audioElB = document.getElementById('audioPlayerB');
let activeAudio = audioEl; // which audio element is currently playing
let _crossfadeTimer = null;
window._currentTrack = null;
window._shuffleEnabled = false;

document.getElementById('playerCrossfade').addEventListener('click', () => {
    crossfadeEnabled = !crossfadeEnabled;
    const b = document.getElementById('playerCrossfade');
    b.classList.toggle('active', crossfadeEnabled);
    b.setAttribute('aria-pressed', String(crossfadeEnabled));
    b.title = crossfadeEnabled ? 'Crossfade ON (3s)' : 'Crossfade (3s)';
});

function playTrack(file, useCrossfade) {
    const sw = currentFileId !== file.id; currentFileId = file.id;
    window._currentTrack = file;
    document.getElementById('playerTitle').textContent = text(file.title);
    document.getElementById('playerArtist').textContent = text(file.artist);
    playerBar.classList.remove('d-none');
    // Cover art
    const artEl = document.getElementById('playerArt');
    const artPlaceholder = document.getElementById('jukeboxArtPlaceholder');
    if (file.hasCoverArt) { artEl.src = `/api/v1/library/files/${file.id}/cover`; artEl.classList.remove('d-none'); if (artPlaceholder) artPlaceholder.style.display = 'none'; }
    else { artEl.classList.add('d-none'); artEl.removeAttribute('src'); if (artPlaceholder) artPlaceholder.style.display = ''; }
    // Init equalizer on first play
    initEqualizer();

    if (sw && useCrossfade && crossfadeEnabled) {
        // Crossfade: start new track on the inactive audio element, fade volumes
        const outgoing = activeAudio;
        const incoming = (activeAudio === audioEl) ? audioElB : audioEl;
        incoming.src = `/api/v1/library/files/${file.id}/stream`;
        incoming.volume = 0;
        incoming.load();
        incoming.play();
        activeAudio = incoming;

        // Fade out old, fade in new
        const steps = 20;
        const interval = (CROSSFADE_DURATION * 1000) / steps;
        let step = 0;
        const targetVolume = outgoing.volume;
        clearInterval(_crossfadeTimer);
        _crossfadeTimer = setInterval(() => {
            step++;
            const progress = step / steps;
            incoming.volume = Math.min(1, progress * targetVolume);
            outgoing.volume = Math.max(0, (1 - progress) * targetVolume);
            if (step >= steps) {
                clearInterval(_crossfadeTimer);
                outgoing.pause();
                outgoing.removeAttribute('src');
            }
        }, interval);
    } else if (sw) {
        // Standard play
        if (activeAudio !== audioEl) { audioElB.pause(); activeAudio = audioEl; }
        audioEl.src = `/api/v1/library/files/${file.id}/stream`; audioEl.load();
        audioEl.play();
    } else {
        activeAudio.play();
    }
}

function playNextTrack(useCrossfade) {
    // Check queue first
    if (queue.length > 0) { const next = queue.shift(); renderQueue(); const full = allFiles.find(f => f.id === next.id) || next; playTrack(full, useCrossfade); return; }
    if (!currentFileId) return;
    const tracks = getPlayableTrackList(); if (tracks.length === 0) return;
    let nf;
    if (shuffleEnabled) { const o = tracks.filter(f => f.id !== currentFileId); if (o.length === 0) return; nf = o[Math.floor(Math.random() * o.length)]; }
    else { const i = tracks.findIndex(f => f.id === currentFileId); if (i === -1 || i >= tracks.length - 1) return; nf = tracks[i + 1]; }
    const full = allFiles.find(f => f.id === nf.id) || nf;
    if (full.id) playTrack(full, useCrossfade);
}

function getPlayableTrackList() {
    const rows = document.querySelectorAll('tbody tr[data-file-id]');
    const ids = Array.from(rows).map(tr => tr.dataset.fileId);
    if (nav.view === 'playlist') return ids.map(id => ({ id }));
    return getVisibleTracks().filter(f => ids.includes(f.id));
}

function syncPlayerBtn() {
    const btn = document.getElementById('playerPlayPause');
    btn.textContent = activeAudio.paused ? '\u25B6' : '\u23F8';
    btn.setAttribute('aria-label', activeAudio.paused ? 'Play' : 'Pause');
    // Spin album art when playing
    const art = document.getElementById('playerArt');
    if (art) art.classList.toggle('spinning', !activeAudio.paused);
    document.querySelectorAll('.btn-play-row').forEach(b => { const tr = b.closest('tr'); const a = tr && tr.dataset.fileId === currentFileId; b.textContent = (a && !activeAudio.paused) ? '\u23F8' : '\u25B6'; b.classList.toggle('playing', a); });
}

document.getElementById('playerPlayPause').addEventListener('click', () => { if (activeAudio.paused) activeAudio.play(); else activeAudio.pause(); });
document.getElementById('playerShuffle').addEventListener('click', () => { shuffleEnabled = !shuffleEnabled; window._shuffleEnabled = shuffleEnabled; const b = document.getElementById('playerShuffle'); b.classList.toggle('active', shuffleEnabled); b.setAttribute('aria-pressed', String(shuffleEnabled)); });
audioEl.addEventListener('play', syncPlayerBtn);
audioEl.addEventListener('pause', syncPlayerBtn);
audioElB.addEventListener('play', syncPlayerBtn);
audioElB.addEventListener('pause', syncPlayerBtn);

let _crossfadeTriggered = false;
function handleTrackTimeUpdate(el) {
    if (!isNaN(el.duration) && el.duration > 0 && el === activeAudio) {
        document.getElementById('playerSeek').value = (el.currentTime / el.duration) * 100;
        document.getElementById('playerCurrentTime').textContent = formatTime(el.currentTime);
        // Trigger crossfade before track ends
        if (crossfadeEnabled && !_crossfadeTriggered && el.duration - el.currentTime <= CROSSFADE_DURATION) {
            _crossfadeTriggered = true;
            playNextTrack(true);
        }
    }
}
function handleTrackEnded(el) {
    syncPlayerBtn();
    if (el !== activeAudio) return; // crossfade already switched
    _crossfadeTriggered = false;
    if (!crossfadeEnabled) playNextTrack(false);
}

audioEl.addEventListener('ended', () => handleTrackEnded(audioEl));
audioElB.addEventListener('ended', () => handleTrackEnded(audioElB));
audioEl.addEventListener('timeupdate', () => handleTrackTimeUpdate(audioEl));
audioElB.addEventListener('timeupdate', () => handleTrackTimeUpdate(audioElB));
audioEl.addEventListener('loadedmetadata', () => { if (activeAudio === audioEl) document.getElementById('playerDuration').textContent = formatTime(audioEl.duration); });
audioElB.addEventListener('loadedmetadata', () => { if (activeAudio === audioElB) document.getElementById('playerDuration').textContent = formatTime(audioElB.duration); });
// Reset crossfade trigger on new track load
audioEl.addEventListener('loadstart', () => { if (activeAudio === audioEl) _crossfadeTriggered = false; });
audioElB.addEventListener('loadstart', () => { if (activeAudio === audioElB) _crossfadeTriggered = false; });
document.getElementById('playerSeek').addEventListener('input', () => { if (!isNaN(activeAudio.duration)) activeAudio.currentTime = (document.getElementById('playerSeek').value / 100) * activeAudio.duration; });
document.getElementById('playerVolume').addEventListener('input', () => { activeAudio.volume = document.getElementById('playerVolume').value; });

// ── Visual Equalizer (Web Audio API) ────────────────────
let audioCtx = null, analyser = null, eqAnimId = null, eqConnected = false;
const eqSources = new Map(); // track which audio elements have been connected

function initEqualizer() {
    if (eqConnected) return;
    try {
        audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
        analyser = audioCtx.createAnalyser();
        analyser.fftSize = 128;
        analyser.smoothingTimeConstant = 0.8;
        analyser.connect(audioCtx.destination);
        // Connect both audio elements (for crossfade support)
        [audioEl, audioElB].forEach(el => {
            if (!eqSources.has(el)) {
                const src = audioCtx.createMediaElementSource(el);
                src.connect(analyser);
                eqSources.set(el, src);
            }
        });
        eqConnected = true;
        if (!eqAnimId) drawEqualizer();
    } catch (e) { console.warn('Equalizer init failed:', e); }
}

function drawEqualizer() {
    if (!analyser || !eqCanvas) return;
    const ctx = eqCanvas.getContext('2d');
    const W = eqCanvas.width, H = eqCanvas.height;
    const bufLen = analyser.frequencyBinCount;
    const data = new Uint8Array(bufLen);

    function frame() {
        eqAnimId = requestAnimationFrame(frame);
        analyser.getByteFrequencyData(data);
        ctx.clearRect(0, 0, W, H);

        // Draw bars
        const barCount = 24;
        const barW = (W / barCount) - 2;
        const gap = 2;
        for (let i = 0; i < barCount; i++) {
            // Sample from frequency data (weighted toward lower freqs for rock/metal)
            const idx = Math.floor(Math.pow(i / barCount, 1.5) * bufLen);
            const val = data[Math.min(idx, bufLen - 1)] / 255;
            const barH = Math.max(2, val * H);
            const x = i * (barW + gap) + gap / 2;
            const y = H - barH;

            // Neon gradient: cyan at bottom, amber in middle, pink at top
            const grad = ctx.createLinearGradient(x, H, x, 0);
            grad.addColorStop(0, 'rgba(77, 232, 224, 0.9)');   // cyan
            grad.addColorStop(0.5, 'rgba(255, 179, 71, 0.9)'); // amber
            grad.addColorStop(1, 'rgba(255, 110, 180, 0.9)');  // pink
            ctx.fillStyle = grad;

            // Rounded top bars
            const radius = Math.min(barW / 2, 3);
            ctx.beginPath();
            ctx.moveTo(x, H);
            ctx.lineTo(x, y + radius);
            ctx.quadraticCurveTo(x, y, x + radius, y);
            ctx.lineTo(x + barW - radius, y);
            ctx.quadraticCurveTo(x + barW, y, x + barW, y + radius);
            ctx.lineTo(x + barW, H);
            ctx.fill();

            // Glow effect on tall bars
            if (val > 0.6) {
                ctx.shadowColor = 'rgba(255, 179, 71, 0.4)';
                ctx.shadowBlur = 6;
                ctx.fill();
                ctx.shadowBlur = 0;
            }
        }
    }
    frame();
}

// ── Keyboard shortcuts ───────────────────────────────────
document.addEventListener('keydown', e => {
    if (playerBar.classList.contains('d-none')) return;
    const t = document.activeElement.tagName; if (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT') return;
    switch (e.key) {
        case ' ': e.preventDefault(); if (activeAudio.paused) activeAudio.play(); else activeAudio.pause(); break;
        case 'ArrowRight': e.preventDefault(); if (!isNaN(activeAudio.duration)) activeAudio.currentTime = Math.min(activeAudio.duration, activeAudio.currentTime + 5); break;
        case 'ArrowLeft': e.preventDefault(); activeAudio.currentTime = Math.max(0, activeAudio.currentTime - 5); break;
        case 'ArrowUp': e.preventDefault(); activeAudio.volume = Math.min(1, activeAudio.volume + 0.05); document.getElementById('playerVolume').value = activeAudio.volume; break;
        case 'ArrowDown': e.preventDefault(); activeAudio.volume = Math.max(0, activeAudio.volume - 0.05); document.getElementById('playerVolume').value = activeAudio.volume; break;
    }
});

// ── Theme toggle ────────────────────────────────────────
(function initTheme() {
    const saved = localStorage.getItem('sg-theme');
    const btn = document.getElementById('themeToggle');
    function applyTheme(theme) {
        if (theme === 'light') {
            document.documentElement.setAttribute('data-theme', 'light');
            if (btn) btn.textContent = '\u263E'; // moon
        } else if (theme === 'dark') {
            document.documentElement.setAttribute('data-theme', 'dark');
            if (btn) btn.textContent = '\u2606'; // sun
        } else {
            document.documentElement.removeAttribute('data-theme');
            const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            if (btn) btn.textContent = prefersDark ? '\u2606' : '\u263E';
        }
    }
    applyTheme(saved);
    if (btn) btn.addEventListener('click', () => {
        const current = document.documentElement.getAttribute('data-theme');
        const next = current === 'light' ? 'dark' : 'light';
        localStorage.setItem('sg-theme', next);
        applyTheme(next);
    });
})();

// ── Init ─────────────────────────────────────────────────
loadSavedQueue();
loadLibrary();

})();
