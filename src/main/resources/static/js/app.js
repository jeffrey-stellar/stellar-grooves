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
const VIRTUAL_ROW_HEIGHT_DEFAULT = 42, VIRTUAL_BUFFER = 20, VIRTUAL_THRESHOLD = 250;
let virtualRowHeight = VIRTUAL_ROW_HEIGHT_DEFAULT;

/** Measure actual row height from the DOM. Call once after first render. */
function measureRowHeight() {
    const tbody = document.getElementById('musicTableBody');
    if (!tbody || !tbody.firstElementChild) return;
    const row = tbody.querySelector('tr[data-file-id]');
    if (row) {
        const h = row.getBoundingClientRect().height;
        if (h > 0) virtualRowHeight = Math.round(h);
    }
}

// ── State ────────────────────────────────────────────────
let allFiles = [], sortState = { col: null, dir: 'asc' };
let nav = { view: 'library', artist: null, album: null, playlistId: null, playlistName: null };
let playlists = [], selectedFileForPlaylist = null, addToPlaylistModal = null;
let cachedVisibleTracks = [];
let selectedIds = new Set(); // bulk selection
let queue = []; // play queue
let playlistContext = null; // { playlistId, playlistName, tracks: [{id, title, artist, ...}] } when playing from a playlist

// Shared state with player.js and other modules via SG namespace
window.SG = window.SG || {};
SG.currentFileId = null;

// Expose mutable state via proper accessors for modules (player.js, queue.js, scan.js)
Object.defineProperties(SG, {
    allFiles:        { get() { return allFiles; },   configurable: true },
    playlists:       { get() { return playlists; },  configurable: true },
    queue:           { get() { return queue; },      set(v) { queue = v; },           configurable: true },
    nav:             { get() { return nav; },        configurable: true },
    playlistContext: { get() { return playlistContext; }, configurable: true }
});
SG.setAllFiles = (v) => { allFiles = v; };
SG.setPlaylists = (v) => { playlists = v; };
SG.setQueue = (v) => { queue = v; };

// ── CSRF + Correlation ID + Helpers ─────────────────────
function csrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.getAttribute('content') : '';
}
function csrfHeaderName() {
    const meta = document.querySelector('meta[name="_csrf_header"]');
    return meta ? meta.getAttribute('content') : 'X-CSRF-TOKEN';
}
let _correlationSeq = 0;
function correlationId() {
    return (window._sgSessionId || 'ui') + '-' + (++_correlationSeq);
}
// Generate a short session ID for correlating client requests with server logs
window._sgSessionId = Math.random().toString(36).substring(2, 10);
function csrfHeaders(extra = {}) { const h = { 'X-Correlation-Id': correlationId() }; h[csrfHeaderName()] = csrfToken(); return Object.assign(h, extra); }
function text(v) { return (v && v.trim()) ? v.trim() : '\u2014'; }
function escapeHtml(s) { const d = document.createElement('div'); d.appendChild(document.createTextNode(s || '')); return d.innerHTML; }
function formatTime(s) { if (isNaN(s) || s < 0) return '0:00'; return Math.floor(s / 60) + ':' + Math.floor(s % 60).toString().padStart(2, '0'); }

// Expose shared helpers for player.js and modules
window.SG = window.SG || {};
SG.text = text;
SG.formatTime = formatTime;
SG.escapeHtml = escapeHtml;
SG.csrfHeaders = csrfHeaders;
SG.guardClick = guardClick;
SG.showToast = showToast;
SG.navigate = (n) => navigate(n);
SG.updateStats = () => updateStats();
SG.renderPlaylistSidebar = () => renderPlaylistSidebar();
function genreBadge(g) { const s = document.createElement('span'); s.className = 'badge ' + (GENRE_CLASSES[g] || 'genre-OTHER'); s.textContent = GENRE_LABELS[g] || 'Other'; return s; }
function decadeFromYear(y) { if (!y || y.length < 4) return '\u2014'; const n = parseInt(y.substring(0, 4), 10); return isNaN(n) ? '\u2014' : Math.floor(n / 10) * 10 + 's'; }
async function guardClick(btn, fn) { if (btn.disabled) return; btn.disabled = true; try { await fn(); } finally { btn.disabled = false; } }
function showToast(message, type = 'error', durationMs = 3500) {
    const container = document.getElementById('toastContainer'); if (!container) return;
    const el = document.createElement('div'); el.className = 'toast-msg toast-' + type; el.textContent = message;
    container.appendChild(el);
    // Cap visible toasts to prevent overflow
    const toasts = container.querySelectorAll('.toast-msg:not(.toast-fade-out)');
    if (toasts.length > 5) toasts[0].classList.add('toast-fade-out');
    setTimeout(() => { el.classList.add('toast-fade-out'); el.addEventListener('animationend', () => el.remove()); }, durationMs);
}
async function errorMsg(resp, fallback) {
    try { const d = await resp.json(); return d.error || d.detail || d.message || fallback; } catch (_) { return fallback; }
}
function fileById(id) { return allFiles.find(f => f.id === id); }
function fileForRow(tr) { return tr ? fileById(tr.dataset.fileId) : null; }

// ── Playlist context: remembers which playlist we're playing from ──
function setPlaylistContext(playlistId, playlistName, tracks) {
    playlistContext = { playlistId, playlistName, tracks };
    renderPlaylistUpNext();
}
function clearPlaylistContext() {
    if (playlistContext) { playlistContext = null; renderPlaylistUpNext(); }
}
SG.setPlaylistContext = setPlaylistContext;
SG.clearPlaylistContext = clearPlaylistContext;
function renderPlaylistUpNext() {
    const ul = document.getElementById('playlistUpNext');
    if (!ul) return;
    const label = document.getElementById('playlistUpNextLabel');
    if (!playlistContext || !SG.currentFileId) {
        ul.innerHTML = ''; if (label) label.classList.add('d-none');
        return;
    }
    const idx = playlistContext.tracks.findIndex(t => t.id === SG.currentFileId);
    const upcoming = idx === -1 ? [] : playlistContext.tracks.slice(idx + 1);
    if (upcoming.length === 0) {
        ul.innerHTML = ''; if (label) label.classList.add('d-none');
        return;
    }
    if (label) { label.classList.remove('d-none'); label.textContent = 'FROM: ' + (playlistContext.playlistName || 'Playlist'); }
    ul.innerHTML = '';
    upcoming.slice(0, 10).forEach(f => {
        const li = document.createElement('li'); li.className = 'queue-item playlist-up-next-item';
        const title = document.createElement('span'); title.className = 'queue-item-title';
        title.textContent = text(f.title) + (f.artist ? ' \u00B7 ' + f.artist : '');
        li.appendChild(title); ul.appendChild(li);
    });
    if (upcoming.length > 10) {
        const li = document.createElement('li'); li.className = 'queue-item playlist-up-next-item';
        li.textContent = '... and ' + (upcoming.length - 10) + ' more';
        ul.appendChild(li);
    }
}
SG.renderPlaylistUpNext = renderPlaylistUpNext;

// ── Star rating widget ───────────────────────────────────
function buildStarRating(fileId, currentRating) {
    const div = document.createElement('span');
    div.className = 'star-rating';
    div.setAttribute('role', 'group');
    div.setAttribute('aria-label', 'Rate this track');
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

    // Populate artist filter dropdown; album dropdown updated via updateAlbumFilter()
    const af = document.getElementById('artistFilter');
    const prevArtist = af.value;
    af.innerHTML = '<option value="">All Artists</option>';
    [...artists].sort().forEach(a => { const o = document.createElement('option'); o.value = a; o.textContent = a; af.appendChild(o); });
    af.value = prevArtist;

    updateAlbumFilter();
}

function updateAlbumFilter() {
    const selectedArtist = document.getElementById('artistFilter').value;
    const src = selectedArtist ? allFiles.filter(f => f.artist === selectedArtist) : allFiles;
    const albums = new Set(src.map(f => f.album).filter(Boolean));
    const abf = document.getElementById('albumFilter');
    const prevAlbum = abf.value;
    abf.innerHTML = '<option value="">All Albums</option>';
    [...albums].sort().forEach(a => { const o = document.createElement('option'); o.value = a; o.textContent = a; abf.appendChild(o); });
    // Keep previous selection only if it's still valid
    if (albums.has(prevAlbum)) abf.value = prevAlbum; else abf.value = '';
}

// ── Navigation ───────────────────────────────────────────
function navigate(newNav) {
    nav = newNav; window.nav = nav;
    selectedIds.clear(); updateBulkBar();
    renderBreadcrumb(); renderCurrentView();
    renderPlaylistSidebar();
    if (typeof SG.renderSmartPlaylistSidebar === 'function') SG.renderSmartPlaylistSidebar();
    if (typeof SG.renderTagsSidebar === 'function') SG.renderTagsSidebar();
}

function renderBreadcrumb() {
    const ol = document.getElementById('breadcrumbList'); ol.innerHTML = '';
    function crumb(label, fn) {
        const li = document.createElement('li'); li.className = 'breadcrumb-item' + (fn ? '' : ' active');
        if (fn) { const a = document.createElement('a'); a.href = '#'; a.textContent = label; a.addEventListener('click', e => { e.preventDefault(); fn(); }); li.appendChild(a); }
        else { li.textContent = label; li.setAttribute('aria-current', 'page'); }
        ol.appendChild(li);
    }
    const home = nav.view === 'library' && !nav.tag;
    const hc = () => navigate({ view: 'library', artist: null, album: null, tag: null });
    if (home) crumb('My Music Library', null);
    else if (nav.view === 'library' && nav.tag) { crumb('My Music Library', hc); crumb('Tag: ' + nav.tag, null); }
    else if (nav.view === 'artists') { crumb('My Music Library', hc); crumb('Artists', null); }
    else if (nav.view === 'albums' && !nav.artist) { crumb('My Music Library', hc); crumb('Albums', null); }
    else if (nav.view === 'albums' && nav.artist) { crumb('My Music Library', hc); crumb('Artists', () => navigate({ view: 'artists' })); crumb(nav.artist, null); }
    else if (nav.view === 'tracks' && nav.album && nav.artist) { crumb('My Music Library', hc); crumb('Artists', () => navigate({ view: 'artists' })); crumb(nav.artist, () => navigate({ view: 'albums', artist: nav.artist })); crumb(nav.album, null); }
    else if (nav.view === 'tracks' && nav.album) { crumb('My Music Library', hc); crumb('Albums', () => navigate({ view: 'albums' })); crumb(nav.album, null); }
    else if (nav.view === 'playlist') { crumb('My Music Library', hc); crumb(nav.playlistName || 'Playlist', null); }
    else if (nav.view === 'smartPlaylist') { crumb('My Music Library', hc); crumb('Smart Playlists', null); }
    else if (nav.view === 'duplicates') { crumb('My Music Library', hc); crumb('Duplicates', null); }
    else if (nav.view === 'history') { crumb('My Music Library', hc); crumb('Listening History', null); }
    else if (nav.view === 'rediscover') { crumb('My Music Library', hc); crumb('Rediscover', null); }
    else crumb('My Music Library', null);

    const showFilters = nav.view === 'library' || (nav.view === 'tracks' && !nav.album && !nav.artist);
    document.getElementById('trackFilters').classList.toggle('d-none', !showFilters);
}

// ── View rendering ───────────────────────────────────────
function renderCurrentView() {
    ['viewArtists','viewAlbums','viewTracks','viewPlaylist','viewSmartPlaylist','viewDuplicates','viewHistory','viewRediscover','emptyState'].forEach(id => { const el = document.getElementById(id); if (el) el.classList.add('d-none'); });
    document.getElementById('bulkBar').classList.add('d-none');
    if (nav.view === 'smartPlaylist') {
        document.getElementById('viewSmartPlaylist').classList.remove('d-none');
        if (typeof SG.renderSmartPlaylistView === 'function') SG.renderSmartPlaylistView();
        return;
    }
    if (nav.view === 'history') {
        document.getElementById('viewHistory').classList.remove('d-none');
        if (typeof SG.renderHistoryView === 'function') SG.renderHistoryView();
        return;
    }
    if (nav.view === 'rediscover') {
        const el = document.getElementById('viewRediscover');
        if (el) el.classList.remove('d-none');
        if (typeof SG.renderRediscoverView === 'function') SG.renderRediscoverView();
        return;
    }
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
        tr.setAttribute('role', 'button'); tr.setAttribute('tabindex', '0');
        tr.innerHTML = `<td><span class="drill-link">${escapeHtml(artist)}</span></td><td class="text-end hide-xs">${d.al.size}</td><td class="text-end">${d.t}</td>`;
        const go = () => navigate({ view: 'albums', artist, album: null });
        tr.addEventListener('click', go);
        tr.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
        tbody.appendChild(tr);
    });
    renderJukeboxPanels();
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

    // Grid / List / Art gallery views
    const grid = document.getElementById('albumsGrid');
    const tableWrap = document.getElementById('albumsTableWrap');
    const artGallery = document.getElementById('albumsArtGallery');
    grid.classList.add('d-none'); tableWrap.classList.add('d-none'); artGallery.classList.add('d-none');
    if (albumViewMode === 'grid') {
        grid.classList.remove('d-none');
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
    } else if (albumViewMode === 'art') {
        artGallery.classList.remove('d-none');
        artGallery.innerHTML = '';
        const withArt = sorted.filter(a => a.coverFileId);
        if (withArt.length === 0) {
            artGallery.innerHTML = '<p style="color:var(--text-muted);text-align:center;padding:2rem;">No cover art found. Scan a library with embedded artwork to populate this gallery.</p>';
        } else {
            withArt.forEach(({ album, artist, coverFileId }) => {
                const card = document.createElement('div'); card.className = 'art-gallery-card';
                const img = document.createElement('img');
                img.src = `/api/v1/library/files/${coverFileId}/cover`; img.alt = escapeHtml(album);
                img.loading = 'lazy';
                card.appendChild(img);
                const label = document.createElement('div'); label.className = 'art-gallery-label';
                label.innerHTML = `<strong>${escapeHtml(album)}</strong><span>${escapeHtml(artist)}</span>`;
                card.appendChild(label);
                card.addEventListener('click', () => openCoverArtLightbox(
                    `/api/v1/library/files/${coverFileId}/cover`, album, artist));
                artGallery.appendChild(card);
            });
        }
    } else {
        tableWrap.classList.remove('d-none');
        const tbody = document.getElementById('albumsBody'); tbody.innerHTML = '';
        sorted.forEach(({ album, artist, tracks }) => {
            const tr = document.createElement('tr'); tr.className = 'drill-row';
            tr.setAttribute('role', 'button'); tr.setAttribute('tabindex', '0');
            tr.innerHTML = `<td><span class="drill-link">${escapeHtml(album)}</span></td><td class="hide-xs">${escapeHtml(artist)}</td><td class="text-end">${tracks}</td>`;
            const go = () => navigate({ view: 'tracks', artist: nav.artist || artist, album });
            tr.addEventListener('click', go);
            tr.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
            tbody.appendChild(tr);
        });
    }
    renderJukeboxPanels();
}

// Album view toggle
function setAlbumViewMode(mode) {
    albumViewMode = mode;
    ['albumViewGrid', 'albumViewList', 'albumViewArt'].forEach(id => {
        const btn = document.getElementById(id);
        const isActive = (id === 'albumViewGrid' && mode === 'grid')
            || (id === 'albumViewList' && mode === 'list')
            || (id === 'albumViewArt' && mode === 'art');
        btn.classList.toggle('active', isActive);
        btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });
    renderAlbumsView();
}
document.getElementById('albumViewGrid').addEventListener('click', () => setAlbumViewMode('grid'));
document.getElementById('albumViewList').addEventListener('click', () => setAlbumViewMode('list'));
document.getElementById('albumViewArt').addEventListener('click', () => setAlbumViewMode('art'));

// ── Tracks view ──────────────────────────────────────────
function getVisibleTracks() {
    let f = allFiles;
    if (nav.artist) f = f.filter(x => (x.artist || '(Unknown)') === nav.artist);
    if (nav.album) f = f.filter(x => (x.album || '(Unknown)') === nav.album);
    if (nav.tag) f = f.filter(x => Array.isArray(x.customTags) && x.customTags.includes(nav.tag));
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

function highlightText(str, query) {
    if (!query) return document.createTextNode(text(str));
    const val = text(str);
    const lower = val.toLowerCase(), qi = lower.indexOf(query.toLowerCase());
    if (qi === -1) return document.createTextNode(val);
    const frag = document.createDocumentFragment();
    frag.appendChild(document.createTextNode(val.substring(0, qi)));
    const mark = document.createElement('mark');
    mark.className = 'search-highlight';
    mark.textContent = val.substring(qi, qi + query.length);
    frag.appendChild(mark);
    frag.appendChild(document.createTextNode(val.substring(qi + query.length)));
    return frag;
}

function buildTrackRow(file) {
    const tr = document.createElement('tr'); tr.dataset.fileId = file.id;
    if (SG.currentFileId === file.id) tr.classList.add('table-active');

    // Checkbox
    const tdChk = document.createElement('td'); tdChk.className = 'col-check';
    const chk = document.createElement('input'); chk.type = 'checkbox'; chk.checked = selectedIds.has(file.id);
    chk.dataset.action = 'select'; chk.setAttribute('aria-label', 'Select ' + text(file.title));
    tdChk.appendChild(chk); tr.appendChild(tdChk);

    // Play
    const tdPlay = document.createElement('td'); tdPlay.className = 'col-play';
    const bp = document.createElement('button'); bp.className = 'btn-play-row' + (SG.currentFileId === file.id ? ' playing' : '');
    bp.setAttribute('aria-label', 'Play ' + text(file.title)); bp.textContent = (SG.currentFileId === file.id && SG.isPlaying()) ? '\u23F8' : '\u25B6';
    bp.dataset.action = 'play'; tdPlay.appendChild(bp); tr.appendChild(tdPlay);

    // Title, Artist, Album (with search highlighting)
    const searchQ = document.getElementById('searchInput').value.trim();
    const tdT = document.createElement('td'); tdT.appendChild(highlightText(file.title, searchQ)); tr.appendChild(tdT);
    const tdAr = document.createElement('td'); tdAr.appendChild(highlightText(file.artist, searchQ)); tr.appendChild(tdAr);
    const tdAl = document.createElement('td'); tdAl.className = 'hide-xs'; tdAl.appendChild(highlightText(file.album, searchQ)); tr.appendChild(tdAl);

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
    const tagCount = Array.isArray(file.customTags) ? file.customTags.length : 0;
    const bt = document.createElement('button');
    bt.className = 'btn-action-sm' + (tagCount > 0 ? ' btn-action-tagged' : '');
    bt.setAttribute('aria-label', 'Edit tags' + (tagCount ? ' (' + tagCount + ')' : ''));
    bt.title = tagCount > 0 ? 'Tags: ' + file.customTags.join(', ') : 'Edit tags';
    bt.textContent = tagCount > 0 ? '\u2691' + tagCount : '\u2691';
    bt.dataset.action = 'edit-tags';
    tdA.appendChild(bt);
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
    if (allFiles.length > 0 && cachedVisibleTracks.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td'); td.colSpan = 9; td.className = 'empty-state';
        td.innerHTML = '<div class="empty-state-icon">\uD83D\uDD0D</div><p>No tracks match your filters</p>';
        tr.appendChild(td); tbody.appendChild(tr); return;
    }
    if (cachedVisibleTracks.length <= VIRTUAL_THRESHOLD) { cachedVisibleTracks.forEach(f => tbody.appendChild(buildTrackRow(f))); measureRowHeight(); renderJukeboxPanels(); return; }
    renderVirtualSlice();
    measureRowHeight();
    renderJukeboxPanels();
}

function renderVirtualSlice() {
    const c = document.getElementById('tracksScrollContainer'), tbody = document.getElementById('musicTableBody');
    const total = cachedVisibleTracks.length, st = c.scrollTop, vh = c.clientHeight;
    const si = Math.max(0, Math.floor(st / virtualRowHeight) - VIRTUAL_BUFFER);
    const ei = Math.min(total, Math.ceil((st + vh) / virtualRowHeight) + VIRTUAL_BUFFER);
    tbody.innerHTML = '';
    if (si > 0) { const sp = document.createElement('tr'); const td = document.createElement('td'); td.colSpan = 9; td.style.cssText = `height:${si * virtualRowHeight}px;padding:0;border:none;`; sp.appendChild(td); tbody.appendChild(sp); }
    for (let i = si; i < ei; i++) tbody.appendChild(buildTrackRow(cachedVisibleTracks[i]));
    const bh = (total - ei) * virtualRowHeight;
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
        else showToast(await errorMsg(resp, 'Failed to delete selected tracks'));
    });
    this.classList.remove('btn-loading');
});

document.getElementById('bulkPlaylistBtn').addEventListener('click', () => {
    if (selectedIds.size === 0) return;
    // Reuse the add-to-playlist modal for bulk
    selectedFileForPlaylist = { id: '__bulk__', title: `${selectedIds.size} tracks`, artist: '' };
    openAddToPlaylistModal(selectedFileForPlaylist);
});

const bulkTagBtn = document.getElementById('bulkTagBtn');
if (bulkTagBtn) bulkTagBtn.addEventListener('click', () => {
    if (selectedIds.size === 0) return;
    if (typeof SG.openBulkTagEditor === 'function') SG.openBulkTagEditor([...selectedIds]);
});

// ── Event delegation: tracks tbody ───────────────────────
document.getElementById('musicTableBody').addEventListener('click', function(e) {
    const el = e.target.closest('[data-action]'); if (!el) return;
    const tr = el.closest('tr'), file = fileForRow(tr); if (!file && el.dataset.action !== 'select') return;
    switch (el.dataset.action) {
        case 'play': clearPlaylistContext(); playTrack(file); break;
        case 'queue': addToQueue(file); break;
        case 'add-to-playlist': openAddToPlaylistModal(file); break;
        case 'edit-tags': if (typeof SG.openSingleTrackEditor === 'function') SG.openSingleTrackEditor(file); break;
        case 'delete': guardClick(el, async () => {
            if (!confirm(`Delete "${file.title || file.fileName}"?`)) return;
            const r = await fetch(`/api/v1/library/files/${file.id}`, { method: 'DELETE', headers: csrfHeaders() });
            if (r.ok) { allFiles = allFiles.filter(f => f.id !== file.id); selectedIds.delete(file.id); updateStats(); updateBulkBar(); renderCurrentView(); await loadPlaylists(); }
            else showToast(await errorMsg(r, 'Failed to delete track'));
        }); break;
        case 'rate': {
            const rating = parseInt(el.dataset.rating);
            const starWrap = el.closest('.star-rating'); if (starWrap) starWrap.classList.add('loading');
            fetch(`/api/v1/library/files/${file.id}/rating`, {
                method: 'PATCH', headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ rating })
            }).then(async r => { if (r.ok) { const i = allFiles.findIndex(f => f.id === file.id); if (i !== -1) allFiles[i].rating = rating; } else showToast(await errorMsg(r, 'Failed to update rating')); })
              .catch(e => { console.error('Rating update failed:', e); showToast('Failed to update rating — check your connection'); })
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
        .then(async r => { if (r.ok) { const i = allFiles.findIndex(f => f.id === file.id); if (i !== -1) allFiles[i].genre = ng; renderTracksView(); } else { sel.value = file.genre; showToast(await errorMsg(r, 'Failed to update genre')); } })
        .catch(e => { console.error('Genre update failed:', e); sel.value = file.genre; showToast('Failed to update genre — check your connection'); })
        .finally(() => sel.classList.remove('loading'));
});

// ── Event delegation: playlist tbody (with drag-drop reorder) ──
const plBody = document.getElementById('playlistTracksBody');
plBody.addEventListener('click', function(e) {
    const el = e.target.closest('[data-action]'); if (!el) return;
    const tr = el.closest('tr'), fid = tr ? tr.dataset.fileId : null; if (!fid) return;
    if (el.dataset.action === 'play') {
        // Build playlist context from visible rows
        const rows = plBody.querySelectorAll('tr[data-file-id]');
        const tracks = Array.from(rows).map(r => {
            const id = r.dataset.fileId;
            return fileById(id) || { id, title: r.querySelector('td:nth-child(2)')?.textContent, artist: r.querySelector('td:nth-child(3)')?.textContent };
        });
        setPlaylistContext(nav.playlistId, nav.playlistName, tracks);
        playTrack(fileById(fid) || { id: fid });
    }
    else if (el.dataset.action === 'remove') guardClick(el, async () => {
        const r = await fetch(`/api/v1/playlists/${nav.playlistId}/tracks/${fid}`, { method: 'DELETE', headers: csrfHeaders() });
        if (r.ok) { await loadPlaylists(); await renderPlaylistView(); }
        else showToast(await errorMsg(r, 'Failed to remove track'));
    });
});

// Drag-drop reorder for playlist
let dragRow = null;
const dragAnnounce = document.getElementById('dragAnnounce');
function announceDrag(msg) { if (dragAnnounce) dragAnnounce.textContent = msg; }
plBody.addEventListener('dragstart', e => {
    dragRow = e.target.closest('tr'); if (!dragRow) return;
    dragRow.classList.add('dragging');
    const title = dragRow.querySelector('td:nth-child(3)')?.textContent || 'Track';
    announceDrag('Grabbed ' + title + '. Use arrow keys or drop to reorder.');
});
plBody.addEventListener('dragend', () => {
    if (dragRow) dragRow.classList.remove('dragging');
    document.querySelectorAll('.drag-over').forEach(r => r.classList.remove('drag-over'));
    dragRow = null;
    announceDrag('');
});
plBody.addEventListener('dragover', e => { e.preventDefault(); const tr = e.target.closest('tr'); if (tr && tr !== dragRow) { document.querySelectorAll('.drag-over').forEach(r => r.classList.remove('drag-over')); tr.classList.add('drag-over'); } });
plBody.addEventListener('drop', async e => {
    e.preventDefault();
    const target = e.target.closest('tr');
    if (!target || !dragRow || target === dragRow) return;
    // Reorder in DOM
    const rows = [...plBody.querySelectorAll('tr[data-file-id]')];
    const fromIdx = rows.indexOf(dragRow), toIdx = rows.indexOf(target);
    if (fromIdx < toIdx) target.after(dragRow); else target.before(dragRow);
    const title = dragRow.querySelector('td:nth-child(3)')?.textContent || 'Track';
    announceDrag(title + ' moved to position ' + ([...plBody.querySelectorAll('tr[data-file-id]')].indexOf(dragRow) + 1));
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
function hasActiveFilters() {
    return document.getElementById('genreFilter').value
        || document.getElementById('artistFilter').value
        || document.getElementById('albumFilter').value
        || document.getElementById('searchInput').value.trim().length >= 2;
}
['searchInput', 'genreFilter', 'artistFilter', 'albumFilter'].forEach(id => {
    document.getElementById(id).addEventListener(id === 'searchInput' ? 'input' : 'change', () => {
        if (id === 'artistFilter') updateAlbumFilter();
        if (nav.view === 'library' || (nav.view === 'tracks' && !nav.artist && !nav.album)) {
            clearTimeout(searchDebounce);
            if (hasActiveFilters()) {
                const q = document.getElementById('searchInput').value.trim();
                searchDebounce = setTimeout(() => serverSearch(q.length >= 2 ? q : null), 450);
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
        const params = new URLSearchParams();
        if (query) params.set('q', query);
        const genre = document.getElementById('genreFilter').value;
        const artist = document.getElementById('artistFilter').value;
        const album = document.getElementById('albumFilter').value;
        if (genre) params.set('genre', genre);
        if (artist) params.set('artist', artist);
        // Album filter uses artist param on backend (searches album field via query text)
        if (album && !query) params.set('q', album);
        params.set('size', '200');
        const r = await fetch(`/api/v1/library/search?${params.toString()}`);
        if (!r.ok) return;
        const d = await r.json();
        cachedVisibleTracks = d.content;
        const tbody = document.getElementById('musicTableBody'); tbody.innerHTML = '';
        updateTrackCount();
        if (cachedVisibleTracks.length <= VIRTUAL_THRESHOLD) { cachedVisibleTracks.forEach(f => tbody.appendChild(buildTrackRow(f))); }
        else { renderVirtualSlice(); }
        renderJukeboxPanels();
        const parts = [];
        if (query) parts.push(`"${query}"`);
        if (genre) parts.push(genre);
        if (artist) parts.push(artist);
        const label = parts.length > 0 ? ` for ${parts.join(', ')}` : '';
        document.getElementById('trackCount').textContent = `${d.totalElements} result${d.totalElements !== 1 ? 's' : ''}${label}`;
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

// ── Queue management (moved to queue.js) ────────────────
// Queue functions are accessed via SG.renderQueue, SG.addToQueue, SG.loadSavedQueue
function addToQueue(file) { SG.addToQueue(file); }
function renderQueue() { SG.renderQueue(); }

// ── Scan (moved to scan.js) ──────────────────────────────
function loadScanSchedule() { SG.loadScanSchedule(); }

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

async function createPlaylist(name) { try { const r = await fetch('/api/v1/playlists', { method: 'POST', headers: csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ name }) }); if (r.ok) { document.getElementById('newPlaylistName').value = ''; document.getElementById('newPlaylistForm').classList.add('d-none'); await loadPlaylists(); } else showToast(await errorMsg(r, 'Failed to create playlist')); } catch (e) { showToast('Failed to create playlist'); } }
async function deletePlaylist(id) { try { const r = await fetch(`/api/v1/playlists/${id}`, { method: 'DELETE', headers: csrfHeaders() }); if (r.ok) { if (nav.playlistId === id) navigate({ view: 'library' }); await loadPlaylists(); } else showToast(await errorMsg(r, 'Failed to delete playlist')); } catch (e) { showToast('Failed to delete playlist'); } }

function openAddToPlaylistModal(file) {
    selectedFileForPlaylist = file;
    document.getElementById('addToPlaylistTrackName').textContent = (file.title || '\u2014') + (file.artist ? ' \u00B7 ' + file.artist : '');
    const sel = document.getElementById('addToPlaylistSelect'); sel.innerHTML = '';
    if (playlists.length === 0) { const o = document.createElement('option'); o.textContent = 'No playlists'; o.disabled = true; sel.appendChild(o); }
    else playlists.forEach(pl => { const o = document.createElement('option'); o.value = pl.id; o.textContent = pl.name + ' (' + pl.trackCount + ')'; sel.appendChild(o); });
    document.getElementById('addToPlaylistStatus').textContent = '';
    if (!addToPlaylistModal) {
        addToPlaylistModal = new bootstrap.Modal(document.getElementById('addToPlaylistModal'));
        document.getElementById('addToPlaylistModal').addEventListener('shown.bs.modal', () => {
            const sel = document.getElementById('addToPlaylistSelect');
            if (sel) sel.focus();
        });
    }
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
        let { tracks } = await r.json();
        if (tracks.length === 0) { empty.classList.remove('d-none'); renderJukeboxPanels([]); return; }

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
        renderJukeboxPanels(tracks);

        const isDraggable = sortBy === 'custom';
        tracks.forEach(file => {
            const tr = document.createElement('tr'); tr.dataset.fileId = file.id; tr.draggable = isDraggable;
            if (SG.currentFileId === file.id) tr.classList.add('table-active');

            // Drag handle + play
            const tdP = document.createElement('td'); tdP.className = 'col-play';
            if (isDraggable) {
                const dh = document.createElement('span'); dh.className = 'drag-handle'; dh.textContent = '\u2630'; dh.setAttribute('aria-label', 'Drag to reorder');
                tdP.appendChild(dh);
            }
            const bp = document.createElement('button'); bp.className = 'btn-play-row' + (SG.currentFileId === file.id ? ' playing' : '');
            bp.setAttribute('aria-label', 'Play ' + text(file.title)); bp.textContent = (SG.currentFileId === file.id && SG.isPlaying()) ? '\u23F8' : '\u25B6'; bp.dataset.action = 'play';
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
SG.loadLibrary = loadLibrary;
async function loadLibrary() {
    try {
        allFiles = []; let p = 0, tp = 1;
        while (p < tp) { const r = await fetch(`/api/v1/library/files?page=${p}&size=200`); const d = await r.json(); allFiles = allFiles.concat(d.content); tp = d.totalPages; p++; }
        updateStats(); renderCurrentView(); await loadPlaylists();
        if (typeof SG.loadSmartPlaylists === 'function') await SG.loadSmartPlaylists();
        if (typeof SG.loadTags === 'function') await SG.loadTags();
    } catch (e) { console.error(e); }
}
SG.loadPlaylists = loadPlaylists;

// ── Player bridge (audio player logic now in player.js) ──
function playTrack(file, useCrossfade) { SG.playTrack(file, useCrossfade); }

function playNextTrack(useCrossfade) {
    if (queue.length > 0) { const next = queue.shift(); renderQueue(); const full = allFiles.find(f => f.id === next.id) || next; playTrack(full, useCrossfade); renderPlaylistUpNext(); return; }
    if (!SG.currentFileId) return;
    const tracks = getPlayableTrackList(); if (tracks.length === 0) return;
    let nf;
    if (SG.shuffleEnabled) { const o = tracks.filter(f => f.id !== SG.currentFileId); if (o.length === 0) return; nf = o[Math.floor(Math.random() * o.length)]; }
    else { const i = tracks.findIndex(f => f.id === SG.currentFileId); if (i === -1 || i >= tracks.length - 1) return; nf = tracks[i + 1]; }
    const full = allFiles.find(f => f.id === nf.id) || nf;
    if (full.id) { playTrack(full, useCrossfade); renderPlaylistUpNext(); }
}
SG.playNextTrack = playNextTrack;

function getPlayableTrackList() {
    // If we have an active playlist context, use it regardless of current view
    if (playlistContext && playlistContext.tracks.length > 0) return playlistContext.tracks;
    const rows = document.querySelectorAll('tbody tr[data-file-id]');
    const ids = Array.from(rows).map(tr => tr.dataset.fileId);
    if (nav.view === 'playlist') return ids.map(id => ({ id }));
    return getVisibleTracks().filter(f => ids.includes(f.id));
}

// ── Theme, keyboard shortcuts, lightbox (moved to theme.js) ──
function openCoverArtLightbox(src, album, artist) { SG.openCoverArtLightbox(src, album, artist); }

// ── Jukebox side panels (2700-style selection lists) ─────
function renderJukeboxPanels(tracksOverride) {
    const listL = document.getElementById('jukeboxListL');
    const listR = document.getElementById('jukeboxListR');
    if (!listL || !listR) return;
    listL.innerHTML = ''; listR.innerHTML = '';
    let tracks;
    if (tracksOverride !== undefined) {
        tracks = tracksOverride;
    } else if (nav.view === 'albums' && nav.artist) {
        tracks = allFiles.filter(f => (f.artist || '(Unknown)') === nav.artist);
    } else if (nav.view === 'artists' || nav.view === 'albums') {
        tracks = allFiles;
    } else {
        tracks = cachedVisibleTracks.length > 0 ? cachedVisibleTracks : allFiles;
    }
    if (tracks.length === 0) {
        listL.innerHTML = '<li class="wurl-side-empty">No songs loaded</li>';
        listR.innerHTML = '<li class="wurl-side-empty">No songs loaded</li>';
        return;
    }
    const buildItem = (file, idx, localIdx) => {
        const li = document.createElement('li');
        li.className = 'wurl-side-item' + (SG.currentFileId === file.id ? ' wurl-side-active' : '');
        li.dataset.fileId = file.id;
        li.setAttribute('role', 'button'); li.setAttribute('tabindex', '0');
        li.style.setProperty('--sg-i', Math.min(localIdx, 40));
        const num = document.createElement('span'); num.className = 'wurl-side-num';
        num.textContent = String(idx + 1).padStart(2, '0');
        const info = document.createElement('div');
        info.style.cssText = 'flex:1;min-width:0;';
        const title = document.createElement('div'); title.className = 'wurl-side-title';
        title.textContent = file.title || file.fileName || '\u2014';
        const artist = document.createElement('div'); artist.className = 'wurl-side-artist';
        artist.textContent = file.artist || '';
        info.appendChild(title); info.appendChild(artist);
        li.appendChild(num); li.appendChild(info);
        return li;
    };
    // On mobile, collapse into a single unified list in the left panel
    // (the right panel is hidden via CSS at <= 991.98px).
    const isMobile = window.matchMedia('(max-width: 991.98px)').matches;
    if (isMobile) {
        for (let i = 0; i < tracks.length; i++) listL.appendChild(buildItem(tracks[i], i, i));
    } else {
        const mid = Math.ceil(tracks.length / 2);
        for (let i = 0; i < mid; i++) listL.appendChild(buildItem(tracks[i], i, i));
        for (let i = mid; i < tracks.length; i++) listR.appendChild(buildItem(tracks[i], i, i - mid));
    }
}

// Re-render jukebox panels on viewport crossing the mobile breakpoint
(function setupJukeboxResponsiveRerender() {
    let wasMobile = window.matchMedia('(max-width: 991.98px)').matches;
    let rafId = null;
    window.addEventListener('resize', () => {
        if (rafId) return;
        rafId = requestAnimationFrame(() => {
            rafId = null;
            const isMobile = window.matchMedia('(max-width: 991.98px)').matches;
            if (isMobile !== wasMobile) {
                wasMobile = isMobile;
                renderJukeboxPanels();
            }
        });
    });
})();

// Click + keyboard handler for side panels
function handleSideItemActivate(e) {
    if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
    if (e.type === 'keydown') e.preventDefault();
    const item = e.target.closest('.wurl-side-item');
    if (!item) return;
    const fid = item.dataset.fileId;
    const file = allFiles.find(f => f.id === fid);
    if (!file) return;
    if (nav.view === 'playlist' && nav.playlistId) {
        // Stay in the playlist playback context so next-track follows the playlist
        const rows = document.querySelectorAll('#playlistTracksBody tr[data-file-id]');
        const tracks = Array.from(rows).map(r => fileById(r.dataset.fileId) || { id: r.dataset.fileId });
        setPlaylistContext(nav.playlistId, nav.playlistName, tracks);
    } else {
        clearPlaylistContext();
    }
    playTrack(file);
}
document.addEventListener('click', handleSideItemActivate);
document.addEventListener('keydown', handleSideItemActivate);

// Expose for player.js to call on track change
SG.renderJukeboxPanels = renderJukeboxPanels;

// ── Web Vitals ──────────────────────────────────────────
function reportWebVitals() {
    if (typeof PerformanceObserver === 'undefined') return;
    const report = (name, value) => console.debug('[WebVital]', name, Math.round(value) + 'ms');
    try {
        // Largest Contentful Paint
        new PerformanceObserver(list => {
            const entries = list.getEntries();
            if (entries.length > 0) report('LCP', entries[entries.length - 1].startTime);
        }).observe({ type: 'largest-contentful-paint', buffered: true });
        // First Input Delay
        new PerformanceObserver(list => {
            list.getEntries().forEach(e => report('FID', e.processingStart - e.startTime));
        }).observe({ type: 'first-input', buffered: true });
        // Cumulative Layout Shift
        let clsValue = 0;
        new PerformanceObserver(list => {
            list.getEntries().forEach(e => { if (!e.hadRecentInput) clsValue += e.value; });
            console.debug('[WebVital] CLS', clsValue.toFixed(4));
        }).observe({ type: 'layout-shift', buffered: true });
    } catch (e) { /* PerformanceObserver type not supported in this browser */ }
}

// ── Init ─────────────────────────────────────────────────
loadLibrary();
// loadSavedQueue and loadScanSchedule are defined in queue.js and scan.js,
// which load after app.js — defer until all scripts are ready.
window.addEventListener('load', () => {
    if (SG.loadSavedQueue) SG.loadSavedQueue();
    if (SG.loadScanSchedule) SG.loadScanSchedule();

    // Web Vitals — report core metrics for performance monitoring
    reportWebVitals();

    // PWA: Service Worker registration
    if ('serviceWorker' in navigator) {
        const v = document.querySelector('meta[name="_appVersion"]');
        const swUrl = '/sw.js' + (v && v.content ? '?v=' + encodeURIComponent(v.content) : '');
        navigator.serviceWorker.register(swUrl)
            .then(reg => console.log('SW registered, scope:', reg.scope))
            .catch(err => console.warn('SW registration failed:', err));
    }
});

})();
