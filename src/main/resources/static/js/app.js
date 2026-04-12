const GENRE_CLASSES = {
    CLASSIC_ROCK: 'genre-CLASSIC_ROCK',
    HARD_ROCK:    'genre-HARD_ROCK',
    HAIR_METAL:   'genre-HAIR_METAL',
    HEAVY_METAL:  'genre-HEAVY_METAL',
    THRASH_METAL: 'genre-THRASH_METAL',
    OTHER:        'genre-OTHER'
};

const GENRE_LABELS = {
    CLASSIC_ROCK: 'Classic Rock',
    HARD_ROCK:    'Hard Rock',
    HAIR_METAL:   'Hair Metal',
    HEAVY_METAL:  'Heavy Metal',
    THRASH_METAL: 'Thrash Metal',
    OTHER:        'Other'
};

let allFiles = [];
let sortState = { col: null, dir: 'asc' };

// nav: { view: 'library'|'artists'|'albums'|'tracks'|'playlist', artist, album, playlistId, playlistName }
let nav = { view: 'library', artist: null, album: null, playlistId: null, playlistName: null };

let playlists = [];
let selectedFileForPlaylist = null;
let addToPlaylistModal = null;

// ── CSRF ──────────────────────────────────────────────────

function csrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : '';
}

function csrfHeaders(extra = {}) {
    return Object.assign({ 'X-XSRF-TOKEN': csrfToken() }, extra);
}

// ── Helpers ───────────────────────────────────────────────

function text(val) {
    return (val && val.trim()) ? val.trim() : '\u2014';
}

function escapeHtml(str) {
    const d = document.createElement('div');
    d.appendChild(document.createTextNode(str || ''));
    return d.innerHTML;
}

function genreBadge(genre) {
    const cls   = GENRE_CLASSES[genre] || 'genre-OTHER';
    const label = GENRE_LABELS[genre]  || 'Other';
    const span  = document.createElement('span');
    span.className   = 'badge ' + cls;
    span.textContent = label;
    return span;
}

function decadeFromYear(year) {
    if (!year || year.length < 4) return '\u2014';
    const y = parseInt(year.substring(0, 4), 10);
    if (isNaN(y)) return '\u2014';
    return Math.floor(y / 10) * 10 + 's';
}

// ── Stats ─────────────────────────────────────────────────

function updateStats() {
    const artists = new Set(allFiles.map(f => f.artist).filter(Boolean));
    const albums  = new Set(allFiles.map(f => f.album).filter(Boolean));
    const genres  = new Set(allFiles.map(f => f.genre || 'OTHER'));

    // Count tracks that share (title + artist) with at least one other track
    const metaCount = {};
    allFiles.forEach(f => {
        if (f.title && f.artist) {
            const key = f.title.toLowerCase() + '|' + f.artist.toLowerCase();
            metaCount[key] = (metaCount[key] || 0) + 1;
        }
    });
    const dupes = Object.values(metaCount).reduce((sum, n) => sum + (n > 1 ? n : 0), 0);

    document.getElementById('statTotal').textContent   = allFiles.length;
    document.getElementById('statArtists').textContent = artists.size;
    document.getElementById('statAlbums').textContent  = albums.size;
    document.getElementById('statGenres').textContent  = genres.size;
    document.getElementById('statDupes').textContent   = dupes;

    const statsRow = document.getElementById('statsRow');
    const clearBtn = document.getElementById('clearBtn');
    if (allFiles.length > 0) {
        statsRow.classList.remove('d-none');
        clearBtn.classList.remove('d-none');
    } else {
        statsRow.classList.add('d-none');
        clearBtn.classList.add('d-none');
    }
}

// ── Navigation / Breadcrumb ───────────────────────────────

function navigate(newNav) {
    nav = newNav;
    renderBreadcrumb();
    renderCurrentView();
    renderPlaylistSidebar();
}

function renderBreadcrumb() {
    const ol = document.getElementById('breadcrumbList');
    ol.innerHTML = '';

    function crumb(label, clickFn) {
        const li = document.createElement('li');
        li.className = 'breadcrumb-item' + (clickFn ? '' : ' active');
        if (clickFn) {
            const a = document.createElement('a');
            a.href = '#';
            a.textContent = label;
            a.addEventListener('click', e => { e.preventDefault(); clickFn(); });
            li.appendChild(a);
        } else {
            li.textContent = label;
        }
        ol.appendChild(li);
    }

    const isHome = nav.view === 'library';
    const homeClick = isHome ? null : () => navigate({ view: 'library', artist: null, album: null });

    if (isHome) {
        crumb('My Music Library', null);
    } else if (nav.view === 'artists') {
        crumb('My Music Library', homeClick);
        crumb('Artists', null);
    } else if (nav.view === 'albums' && !nav.artist) {
        crumb('My Music Library', homeClick);
        crumb('Albums', null);
    } else if (nav.view === 'albums' && nav.artist) {
        crumb('My Music Library', homeClick);
        crumb('Artists', () => navigate({ view: 'artists', artist: null, album: null }));
        crumb(nav.artist, null);
    } else if (nav.view === 'tracks' && nav.album && nav.artist) {
        crumb('My Music Library', homeClick);
        crumb('Artists', () => navigate({ view: 'artists', artist: null, album: null }));
        crumb(nav.artist, () => navigate({ view: 'albums', artist: nav.artist, album: null }));
        crumb(nav.album, null);
    } else if (nav.view === 'tracks' && nav.album && !nav.artist) {
        crumb('My Music Library', homeClick);
        crumb('Albums', () => navigate({ view: 'albums', artist: null, album: null }));
        crumb(nav.album, null);
    } else if (nav.view === 'playlist') {
        crumb('My Music Library', homeClick);
        crumb(nav.playlistName || 'Playlist', null);
    } else {
        crumb('My Music Library', null);
    }

    // Show track filters only on top-level library/tracks view
    const showFilters = nav.view === 'library' || (nav.view === 'tracks' && !nav.album && !nav.artist);
    document.getElementById('trackFilters').classList.toggle('d-none', !showFilters);
}

// ── View rendering ────────────────────────────────────────

function renderCurrentView() {
    const views = ['viewArtists', 'viewAlbums', 'viewTracks', 'viewPlaylist', 'emptyState'];
    views.forEach(id => document.getElementById(id).classList.add('d-none'));

    if (allFiles.length === 0) {
        document.getElementById('emptyState').classList.remove('d-none');
        return;
    }

    if (nav.view === 'artists') {
        document.getElementById('viewArtists').classList.remove('d-none');
        renderArtistsView();
    } else if (nav.view === 'albums') {
        document.getElementById('viewAlbums').classList.remove('d-none');
        renderAlbumsView();
    } else if (nav.view === 'playlist') {
        document.getElementById('viewPlaylist').classList.remove('d-none');
        renderPlaylistView();
    } else {
        document.getElementById('viewTracks').classList.remove('d-none');
        renderTracksView();
    }
}

// ── Artists view ──────────────────────────────────────────

function renderArtistsView() {
    const artistMap = {};
    allFiles.forEach(f => {
        const a = f.artist || '(Unknown Artist)';
        if (!artistMap[a]) artistMap[a] = { tracks: 0, albums: new Set() };
        artistMap[a].tracks++;
        if (f.album) artistMap[a].albums.add(f.album);
    });

    const tbody = document.getElementById('artistsBody');
    tbody.innerHTML = '';
    Object.entries(artistMap)
        .sort((a, b) => a[0].localeCompare(b[0]))
        .forEach(([artist, data]) => {
            const tr = document.createElement('tr');
            tr.className = 'drill-row';
            tr.innerHTML = `
                <td><span class="drill-link">${escapeHtml(artist)}</span></td>
                <td class="text-end hide-xs">${data.albums.size}</td>
                <td class="text-end">${data.tracks}</td>
            `;
            tr.addEventListener('click', () =>
                navigate({ view: 'albums', artist, album: null })
            );
            tbody.appendChild(tr);
        });
}

// ── Albums view ───────────────────────────────────────────

function renderAlbumsView() {
    const sourceFiles = nav.artist
        ? allFiles.filter(f => (f.artist || '(Unknown Artist)') === nav.artist)
        : allFiles;

    const albumMap = {};
    sourceFiles.forEach(f => {
        const alb = f.album  || '(Unknown Album)';
        const art = f.artist || '\u2014';
        const key = alb + '||' + art;
        if (!albumMap[key]) albumMap[key] = { album: alb, artist: art, tracks: 0 };
        albumMap[key].tracks++;
    });

    const tbody = document.getElementById('albumsBody');
    tbody.innerHTML = '';
    Object.values(albumMap)
        .sort((a, b) => a.album.localeCompare(b.album))
        .forEach(({ album, artist, tracks }) => {
            const tr = document.createElement('tr');
            tr.className = 'drill-row';
            tr.innerHTML = `
                <td><span class="drill-link">${escapeHtml(album)}</span></td>
                <td class="hide-xs">${escapeHtml(artist)}</td>
                <td class="text-end">${tracks}</td>
            `;
            tr.addEventListener('click', () =>
                navigate({ view: 'tracks', artist: nav.artist || artist, album })
            );
            tbody.appendChild(tr);
        });
}

// ── Tracks view ───────────────────────────────────────────

function getVisibleTracks() {
    let files = allFiles;

    // Drill-down filters
    if (nav.artist) files = files.filter(f => (f.artist || '(Unknown Artist)') === nav.artist);
    if (nav.album)  files = files.filter(f => (f.album  || '(Unknown Album)')  === nav.album);

    // Genre + search (only on top-level tracks)
    if (!nav.artist && !nav.album) {
        const genre = document.getElementById('genreFilter').value;
        if (genre) files = files.filter(f => f.genre === genre);

        const q = document.getElementById('searchInput').value.toLowerCase();
        if (q) files = files.filter(f =>
            (f.title  || '').toLowerCase().includes(q) ||
            (f.artist || '').toLowerCase().includes(q) ||
            (f.album  || '').toLowerCase().includes(q)
        );
    }

    // Sort
    if (sortState.col) {
        files = [...files].sort((a, b) => {
            let av = a[sortState.col] || '';
            let bv = b[sortState.col] || '';
            const cmp = av.localeCompare(bv);
            return sortState.dir === 'asc' ? cmp : -cmp;
        });
    }

    return files;
}

function renderTracksView() {
    const files = getVisibleTracks();
    const tbody = document.getElementById('musicTableBody');
    tbody.innerHTML = '';

    files.forEach(file => {
        const tr = document.createElement('tr');
        tr.dataset.fileId = file.id;
        if (currentFileId === file.id) tr.classList.add('table-active');

        const tdPlay = document.createElement('td');
        tdPlay.className = 'col-play';
        const btnPlay = document.createElement('button');
        btnPlay.className = 'btn-play-row' + (currentFileId === file.id ? ' playing' : '');
        btnPlay.title = 'Play';
        btnPlay.textContent = (currentFileId === file.id && !audioEl.paused) ? '\u23F8' : '\u25B6';
        btnPlay.addEventListener('click', () => playTrack(file));
        tdPlay.appendChild(btnPlay);
        tr.appendChild(tdPlay);

        const tdTitle = document.createElement('td');
        tdTitle.textContent = text(file.title);
        tr.appendChild(tdTitle);

        const tdArtist = document.createElement('td');
        tdArtist.textContent = text(file.artist);
        tr.appendChild(tdArtist);

        const tdAlbum = document.createElement('td');
        tdAlbum.className = 'hide-xs';
        tdAlbum.textContent = text(file.album);
        tr.appendChild(tdAlbum);

        const tdGenre = document.createElement('td');
        if (file.genre === 'OTHER') {
            const select = document.createElement('select');
            select.className = 'form-select form-select-sm genre-select';
            Object.keys(GENRE_LABELS).forEach(key => {
                const opt = document.createElement('option');
                opt.value = key;
                opt.textContent = GENRE_LABELS[key];
                if (key === file.genre) opt.selected = true;
                select.appendChild(opt);
            });
            select.addEventListener('change', async () => {
                const newGenre = select.value;
                try {
                    const resp = await fetch(`/api/library/files/${file.id}/genre`, {
                        method: 'PATCH',
                        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                        body: JSON.stringify({ genre: newGenre })
                    });
                    if (resp.ok) {
                        const idx = allFiles.findIndex(f => f.id === file.id);
                        if (idx !== -1) allFiles[idx].genre = newGenre;
                        renderTracksView();
                    } else {
                        select.value = file.genre;
                    }
                } catch (err) {
                    select.value = file.genre;
                }
            });
            tdGenre.appendChild(select);
        } else {
            tdGenre.appendChild(genreBadge(file.genre));
        }
        tr.appendChild(tdGenre);

        const tdDecade = document.createElement('td');
        tdDecade.className = 'hide-xs';
        tdDecade.textContent = decadeFromYear(file.year);
        tr.appendChild(tdDecade);

        const tdAct = document.createElement('td');
        tdAct.className = 'hide-xs col-actions';

        const btnAdd = document.createElement('button');
        btnAdd.className = 'btn-action-sm';
        btnAdd.title = 'Add to playlist';
        btnAdd.textContent = '+';
        btnAdd.addEventListener('click', () => openAddToPlaylistModal(file));
        tdAct.appendChild(btnAdd);

        const btnDel = document.createElement('button');
        btnDel.className = 'btn-action-sm btn-action-remove';
        btnDel.title = 'Delete from library';
        btnDel.textContent = '\u2715';
        btnDel.addEventListener('click', async () => {
            if (!confirm(`Delete "${file.title || file.fileName}" from your library?`)) return;
            const resp = await fetch(`/api/library/files/${file.id}`, { method: 'DELETE', headers: csrfHeaders() });
            if (resp.ok) {
                allFiles = allFiles.filter(f => f.id !== file.id);
                updateStats();
                renderCurrentView();
                await loadPlaylists();
            }
        });
        tdAct.appendChild(btnDel);

        tr.appendChild(tdAct);

        tbody.appendChild(tr);
    });
}

// ── Sort headers ──────────────────────────────────────────

document.querySelectorAll('th.col-sortable').forEach(th => {
    th.addEventListener('click', () => {
        const col = th.dataset.col;
        if (sortState.col === col) {
            sortState.dir = sortState.dir === 'asc' ? 'desc' : 'asc';
        } else {
            sortState.col = col;
            sortState.dir = 'asc';
        }
        document.querySelectorAll('th.col-sortable').forEach(h => {
            h.querySelector('.sort-icon').textContent =
                h.dataset.col === sortState.col
                    ? (sortState.dir === 'asc' ? ' \u25B2' : ' \u25BC')
                    : '';
        });
        renderTracksView();
    });
});

// ── Stat card clicks ──────────────────────────────────────

document.getElementById('statCardTotal').addEventListener('click', () =>
    navigate({ view: 'library', artist: null, album: null })
);
document.getElementById('statCardArtists').addEventListener('click', () =>
    navigate({ view: 'artists', artist: null, album: null })
);
document.getElementById('statCardAlbums').addEventListener('click', () =>
    navigate({ view: 'albums', artist: null, album: null })
);
document.getElementById('statCardGenres').addEventListener('click', () =>
    navigate({ view: 'library', artist: null, album: null })
);

// ── Filter listeners (top-level tracks) ──────────────────

document.getElementById('searchInput').addEventListener('input', () => {
    if (nav.view === 'library' || (nav.view === 'tracks' && !nav.artist && !nav.album)) {
        renderTracksView();
    }
});

document.getElementById('genreFilter').addEventListener('change', () => {
    if (nav.view === 'library' || (nav.view === 'tracks' && !nav.artist && !nav.album)) {
        renderTracksView();
    }
});

// ── Scan ──────────────────────────────────────────────────

document.getElementById('scanForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const pathVal  = document.getElementById('path').value.trim();
    const statusDiv = document.getElementById('scanStatus');
    const btn      = document.getElementById('scanBtn');
    const spinner  = document.getElementById('scanSpinner');

    if (!pathVal) {
        document.getElementById('path').classList.add('is-invalid');
        return;
    }
    document.getElementById('path').classList.remove('is-invalid');

    btn.disabled = true;
    spinner.classList.remove('d-none');
    const scanningSpan = document.createElement('span');
    scanningSpan.className = 'status-scanning';
    scanningSpan.textContent = '\u23F3 Scanning\u2026';
    statusDiv.replaceChildren(scanningSpan);

    try {
        const response = await fetch('/api/library/scan', {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ path: pathVal })
        });
        const data = await response.json();
        if (response.ok) {
            const msg = data.filesFound > 0
                ? `\u2713 Scan complete \u2014 ${data.filesFound} new file(s) imported.`
                : `\u2713 Scan complete \u2014 no new files found.`;
            const successSpan = document.createElement('span');
            successSpan.className = 'status-success';
            successSpan.textContent = msg;
            statusDiv.replaceChildren(successSpan);
            await loadLibrary();
        } else {
            const span = document.createElement('span');
            span.className = 'status-error';
            span.textContent = '\u2717 ' + (data.error || 'Scan failed');
            statusDiv.replaceChildren(span);
        }
    } catch (err) {
        const span = document.createElement('span');
        span.className = 'status-error';
        span.textContent = '\u2717 Network error: ' + err.message;
        statusDiv.replaceChildren(span);
    } finally {
        btn.disabled = false;
        spinner.classList.add('d-none');
    }
});

// ── Clear library ─────────────────────────────────────────

document.getElementById('clearBtn').addEventListener('click', async () => {
    if (!confirm('Clear your entire library? This cannot be undone.')) return;
    try {
        const resp = await fetch('/api/library/files', { method: 'DELETE', headers: csrfHeaders() });
        if (resp.ok) {
            allFiles = [];
            playlists = [];
            navigate({ view: 'library', artist: null, album: null, playlistId: null, playlistName: null });
            updateStats();
            renderPlaylistSidebar();
            const clearSpan = document.createElement('span');
            clearSpan.className = 'status-success';
            clearSpan.textContent = 'Library cleared.';
            document.getElementById('scanStatus').replaceChildren(clearSpan);
        }
    } catch (err) {
        console.error('Clear failed', err);
    }
});

// ── Playlists ─────────────────────────────────────────────

async function loadPlaylists() {
    try {
        const resp = await fetch('/api/playlists');
        playlists = await resp.json();
        renderPlaylistSidebar();
    } catch (err) {
        console.error('Failed to load playlists', err);
    }
}

function renderPlaylistSidebar() {
    const ul = document.getElementById('playlistList');
    ul.innerHTML = '';
    if (playlists.length === 0) {
        const li = document.createElement('li');
        li.className = 'playlist-empty-hint';
        li.textContent = 'No playlists yet';
        ul.appendChild(li);
        return;
    }
    playlists.forEach(pl => {
        const li = document.createElement('li');
        li.className = 'playlist-item' + (nav.playlistId === pl.id ? ' active' : '');
        li.innerHTML = `
            <span class="playlist-item-name" title="${escapeHtml(pl.name)}">${escapeHtml(pl.name)}</span>
            <span class="playlist-item-count">${pl.trackCount}</span>
            <button class="playlist-item-del" data-id="${pl.id}" title="Delete playlist">\u2715</button>
        `;
        li.querySelector('.playlist-item-name').addEventListener('click', () => {
            navigate({ view: 'playlist', playlistId: pl.id, playlistName: pl.name, artist: null, album: null });
        });
        li.querySelector('.playlist-item-del').addEventListener('click', async (e) => {
            e.stopPropagation();
            if (!confirm(`Delete playlist "${pl.name}"?`)) return;
            await deletePlaylist(pl.id);
        });
        ul.appendChild(li);
    });
}

async function createPlaylist(name) {
    try {
        const resp = await fetch('/api/playlists', {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ name })
        });
        if (resp.ok) {
            document.getElementById('newPlaylistName').value = '';
            document.getElementById('newPlaylistForm').classList.add('d-none');
            await loadPlaylists();
        }
    } catch (err) {
        console.error('Failed to create playlist', err);
    }
}

async function deletePlaylist(id) {
    try {
        const resp = await fetch(`/api/playlists/${id}`, { method: 'DELETE', headers: csrfHeaders() });
        if (resp.ok) {
            if (nav.playlistId === id) {
                navigate({ view: 'library', artist: null, album: null, playlistId: null, playlistName: null });
            }
            await loadPlaylists();
        }
    } catch (err) {
        console.error('Failed to delete playlist', err);
    }
}

function openAddToPlaylistModal(file) {
    selectedFileForPlaylist = file;
    document.getElementById('addToPlaylistTrackName').textContent =
        (file.title || '\u2014') + (file.artist ? ' \u00B7 ' + file.artist : '');
    const sel = document.getElementById('addToPlaylistSelect');
    sel.innerHTML = '';
    if (playlists.length === 0) {
        const opt = document.createElement('option');
        opt.textContent = 'No playlists \u2014 create one first';
        opt.disabled = true;
        sel.appendChild(opt);
    } else {
        playlists.forEach(pl => {
            const opt = document.createElement('option');
            opt.value = pl.id;
            opt.textContent = pl.name + ' (' + pl.trackCount + ')';
            sel.appendChild(opt);
        });
    }
    document.getElementById('addToPlaylistStatus').textContent = '';
    if (!addToPlaylistModal) {
        addToPlaylistModal = new bootstrap.Modal(document.getElementById('addToPlaylistModal'));
    }
    addToPlaylistModal.show();
}

async function addToPlaylist(playlistId, file) {
    const statusEl = document.getElementById('addToPlaylistStatus');
    try {
        const resp = await fetch(`/api/playlists/${playlistId}/tracks`, {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ fileId: file.id })
        });
        const data = await resp.json();
        if (resp.ok) {
            statusEl.className = 'mt-2 small status-success';
            statusEl.textContent = '\u2713 Added!';
            await loadPlaylists();
            setTimeout(() => addToPlaylistModal && addToPlaylistModal.hide(), 800);
        } else {
            statusEl.className = 'mt-2 small status-error';
            statusEl.textContent = data.error || 'Failed to add track';
        }
    } catch (err) {
        statusEl.className = 'mt-2 small status-error';
        statusEl.textContent = 'Network error';
    }
}

async function renderPlaylistView() {
    const tbody = document.getElementById('playlistTracksBody');
    const emptyEl = document.getElementById('playlistEmptyState');
    tbody.innerHTML = '';
    emptyEl.classList.add('d-none');
    try {
        const resp = await fetch(`/api/playlists/${nav.playlistId}/tracks`);
        if (!resp.ok) return;
        const tracks = await resp.json();
        if (tracks.length === 0) {
            emptyEl.classList.remove('d-none');
            return;
        }
        tracks.forEach(file => {
            const tr = document.createElement('tr');
            tr.dataset.fileId = file.id;
            if (currentFileId === file.id) tr.classList.add('table-active');

            const tdPlay = document.createElement('td');
            tdPlay.className = 'col-play';
            const btnPlay = document.createElement('button');
            btnPlay.className = 'btn-play-row' + (currentFileId === file.id ? ' playing' : '');
            btnPlay.textContent = (currentFileId === file.id && !audioEl.paused) ? '\u23F8' : '\u25B6';
            btnPlay.addEventListener('click', () => playTrack(file));
            tdPlay.appendChild(btnPlay);
            tr.appendChild(tdPlay);

            const tdTitle = document.createElement('td'); tdTitle.textContent = text(file.title); tr.appendChild(tdTitle);
            const tdArtist = document.createElement('td'); tdArtist.textContent = text(file.artist); tr.appendChild(tdArtist);
            const tdAlbum = document.createElement('td'); tdAlbum.className = 'hide-xs'; tdAlbum.textContent = text(file.album); tr.appendChild(tdAlbum);

            const tdGenre = document.createElement('td');
            tdGenre.appendChild(genreBadge(file.genre));
            tr.appendChild(tdGenre);

            const tdDecade = document.createElement('td'); tdDecade.className = 'hide-xs'; tdDecade.textContent = decadeFromYear(file.year); tr.appendChild(tdDecade);

            const tdAct = document.createElement('td');
            tdAct.className = 'hide-xs col-actions';
            const btnRem = document.createElement('button');
            btnRem.className = 'btn-action-sm btn-action-remove';
            btnRem.title = 'Remove from playlist';
            btnRem.textContent = '\u2715';
            btnRem.addEventListener('click', async () => {
                await fetch(`/api/playlists/${nav.playlistId}/tracks/${file.id}`, { method: 'DELETE', headers: csrfHeaders() });
                await loadPlaylists();
                await renderPlaylistView();
            });
            tdAct.appendChild(btnRem);
            tr.appendChild(tdAct);

            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error('Failed to load playlist tracks', err);
    }
}

// ── Playlist event listeners ──────────────────────────────

document.getElementById('showNewPlaylistBtn').addEventListener('click', () => {
    const form = document.getElementById('newPlaylistForm');
    form.classList.toggle('d-none');
    if (!form.classList.contains('d-none')) {
        document.getElementById('newPlaylistName').focus();
    }
});

document.getElementById('createPlaylistBtn').addEventListener('click', () => {
    const name = document.getElementById('newPlaylistName').value.trim();
    if (name) createPlaylist(name);
});

document.getElementById('newPlaylistName').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        const name = e.target.value.trim();
        if (name) createPlaylist(name);
    }
});

document.getElementById('addToPlaylistConfirm').addEventListener('click', () => {
    const sel = document.getElementById('addToPlaylistSelect');
    if (sel.value && selectedFileForPlaylist) {
        addToPlaylist(sel.value, selectedFileForPlaylist);
    }
});

// ── Load library ──────────────────────────────────────────

async function loadLibrary() {
    try {
        allFiles = [];
        let page = 0;
        let totalPages = 1;
        while (page < totalPages) {
            const response = await fetch(`/api/library/files?page=${page}&size=200`);
            const data = await response.json();
            allFiles = allFiles.concat(data.content);
            totalPages = data.totalPages;
            page++;
        }
        updateStats();
        renderCurrentView();
        await loadPlaylists();
    } catch (err) {
        console.error('Failed to load library', err);
    }
}

// ── Audio Player ──────────────────────────────────────────

const audioEl  = document.getElementById('audioPlayer');
const playerBar = document.getElementById('playerBar');
let currentFileId = null;
let shuffleEnabled = false;

function playTrack(file) {
    const switching = currentFileId !== file.id;
    currentFileId = file.id;

    document.getElementById('playerTitle').textContent  = text(file.title);
    document.getElementById('playerArtist').textContent = text(file.artist);
    playerBar.classList.remove('d-none');
    document.body.classList.add('player-open');

    if (switching) {
        audioEl.src = `/api/library/files/${file.id}/stream`;
        audioEl.load();
    }
    audioEl.play();
}

function getPlayableTrackList() {
    // Get the ordered list of files currently visible on screen
    const rows = document.querySelectorAll('tbody tr[data-file-id]');
    const ids = Array.from(rows).map(tr => tr.dataset.fileId);
    // For the tracks view, use getVisibleTracks(); for playlists, use the DOM order
    if (nav.view === 'playlist') {
        return ids.map(id => ({ id })); // minimal objects — playTrack just needs the id
    }
    return getVisibleTracks().filter(f => ids.includes(f.id));
}

function playNextTrack() {
    if (!currentFileId) return;
    const tracks = getPlayableTrackList();
    if (tracks.length === 0) return;

    let nextFile;
    if (shuffleEnabled) {
        // Pick a random track that isn't the current one
        const others = tracks.filter(f => f.id !== currentFileId);
        if (others.length === 0) return;
        nextFile = others[Math.floor(Math.random() * others.length)];
    } else {
        const idx = tracks.findIndex(f => f.id === currentFileId);
        if (idx === -1 || idx >= tracks.length - 1) return;
        nextFile = tracks[idx + 1];
    }

    const fullFile = allFiles.find(f => f.id === nextFile.id) || nextFile;
    if (fullFile.id) {
        playTrack(fullFile);
    }
}

function syncPlayerBtn() {
    const btn = document.getElementById('playerPlayPause');
    btn.textContent = audioEl.paused ? '\u25B6' : '\u23F8';
    // sync row buttons too
    document.querySelectorAll('.btn-play-row').forEach(b => {
        const tr = b.closest('tr');
        const isActive = tr && tr.dataset.fileId === currentFileId;
        b.textContent = (isActive && !audioEl.paused) ? '\u23F8' : '\u25B6';
        b.classList.toggle('playing', isActive);
    });
}

document.getElementById('playerPlayPause').addEventListener('click', () => {
    if (audioEl.paused) audioEl.play(); else audioEl.pause();
});

document.getElementById('playerShuffle').addEventListener('click', () => {
    shuffleEnabled = !shuffleEnabled;
    const btn = document.getElementById('playerShuffle');
    btn.classList.toggle('active', shuffleEnabled);
    btn.title = shuffleEnabled ? 'Shuffle: On' : 'Shuffle: Off';
});

audioEl.addEventListener('play',  syncPlayerBtn);
audioEl.addEventListener('pause', syncPlayerBtn);
audioEl.addEventListener('ended', () => {
    syncPlayerBtn();
    playNextTrack();
});

audioEl.addEventListener('timeupdate', () => {
    if (!isNaN(audioEl.duration) && audioEl.duration > 0) {
        document.getElementById('playerSeek').value =
            (audioEl.currentTime / audioEl.duration) * 100;
        document.getElementById('playerCurrentTime').textContent =
            formatTime(audioEl.currentTime);
    }
});

audioEl.addEventListener('loadedmetadata', () => {
    document.getElementById('playerDuration').textContent = formatTime(audioEl.duration);
});

document.getElementById('playerSeek').addEventListener('input', () => {
    if (!isNaN(audioEl.duration)) {
        audioEl.currentTime =
            (document.getElementById('playerSeek').value / 100) * audioEl.duration;
    }
});

document.getElementById('playerVolume').addEventListener('input', () => {
    audioEl.volume = document.getElementById('playerVolume').value;
});

function formatTime(secs) {
    if (isNaN(secs) || secs < 0) return '0:00';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60).toString().padStart(2, '0');
    return `${m}:${s}`;
}

loadLibrary();
