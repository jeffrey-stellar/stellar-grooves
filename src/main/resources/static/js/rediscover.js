/**
 * Rediscovery dashboard — three curator surfaces built on existing metadata:
 *   • Forgotten           — tracks played before but not in the last N days
 *   • Neglected favorites — rated ≥ N but never/rarely played recently
 *   • One-hit wonders     — artists with one played track and more in the library
 *
 * Each section has a "Play all" action that sets SG playlistContext so
 * next/shuffle keep playing within the surface.
 */
(function() {
'use strict';

const SG = window.SG;
const PREVIEW_SIZE = 12;
const PLAY_MAX = 100;

async function renderRediscoverView() {
    const host = document.getElementById('rediscoverContent');
    if (!host) return;
    host.innerHTML = '';

    host.appendChild(buildSection({
        id: 'rediscoverForgotten',
        title: "Haven't heard in a while",
        subtitle: "Tracks you've played at least once, but not lately — oldest first.",
        empty: 'Nothing forgotten yet. Keep listening and we\u2019ll surface the ones that drift away.',
        load: () => loadTracks('/api/v1/library/rediscovery/forgotten', PREVIEW_SIZE),
        playAll: () => playAllFromEndpoint('/api/v1/library/rediscovery/forgotten', 'Forgotten')
    }));

    host.appendChild(buildSection({
        id: 'rediscoverNeglected',
        title: 'Neglected favorites',
        subtitle: 'Rated 4+ but you haven\u2019t played them recently.',
        empty: 'No neglected favorites — the 4-star shelf is well-tended.',
        load: () => loadTracks('/api/v1/library/rediscovery/neglected-favorites', PREVIEW_SIZE),
        playAll: () => playAllFromEndpoint('/api/v1/library/rediscovery/neglected-favorites', 'Neglected Favorites')
    }));

    host.appendChild(buildOneHitSection());
}

// ── Track-list sections (forgotten, neglected) ───────────

function buildSection({ id, title, subtitle, empty, load, playAll }) {
    const card = document.createElement('div');
    card.className = 'card mb-3';
    card.id = id;
    card.innerHTML =
        `<div class="card-header d-flex justify-content-between align-items-center gap-2 flex-wrap">
            <div>
                <h6 class="mb-0">${SG.escapeHtml(title)}</h6>
                <div class="small text-secondary-themed">${SG.escapeHtml(subtitle)}</div>
            </div>
            <button class="btn btn-sm btn-success" data-action="play-all" disabled>&#9654; Play</button>
         </div>
         <div class="card-body p-0" data-body><div class="small px-3 py-3 text-secondary-themed">Loading\u2026</div></div>`;

    const body = card.querySelector('[data-body]');
    const playBtn = card.querySelector('[data-action="play-all"]');
    playBtn.addEventListener('click', () => SG.guardClick(playBtn, playAll));

    load().then(tracks => {
        body.innerHTML = '';
        if (tracks.length === 0) {
            body.innerHTML = `<div class="small px-3 py-3 text-secondary-themed">${SG.escapeHtml(empty)}</div>`;
            return;
        }
        playBtn.disabled = false;
        body.appendChild(buildTrackTable(tracks));
    }).catch(() => {
        body.innerHTML = '<div class="small px-3 py-3 text-secondary-themed">Failed to load.</div>';
    });

    return card;
}

async function loadTracks(url, size) {
    const r = await fetch(url + '?size=' + size, { headers: SG.csrfHeaders() });
    if (!r.ok) throw new Error('load failed');
    const d = await r.json();
    return d.items || [];
}

function buildTrackTable(tracks) {
    const wrap = document.createElement('div');
    wrap.className = 'table-responsive';
    const table = document.createElement('table');
    table.className = 'table table-sm mb-0';
    table.innerHTML =
        `<thead><tr>
            <th scope="col" class="col-play"><span class="visually-hidden">Play</span></th>
            <th scope="col">Title</th>
            <th scope="col">Artist</th>
            <th scope="col" class="hide-xs">Album</th>
            <th scope="col" class="hide-xs">Rating</th>
            <th scope="col" class="hide-xs">Last played</th>
         </tr></thead>`;
    const tbody = document.createElement('tbody');
    tracks.forEach(t => tbody.appendChild(buildTrackRow(t, tracks)));
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
}

function buildTrackRow(track, sectionTracks) {
    const tr = document.createElement('tr');
    tr.dataset.fileId = track.id;
    const tdPlay = document.createElement('td');
    tdPlay.className = 'col-play';
    const btn = document.createElement('button');
    btn.className = 'btn-play-row';
    btn.setAttribute('aria-label', 'Play ' + (track.title || ''));
    btn.textContent = '\u25B6';
    btn.addEventListener('click', () => {
        // Scope the queue to the surface the user clicked in.
        SG.setPlaylistContext('rediscover:section', 'Rediscover', sectionTracks);
        SG.playTrack(track);
    });
    tdPlay.appendChild(btn);
    tr.appendChild(tdPlay);

    tr.appendChild(cell(track.title || '\u2014'));
    tr.appendChild(cell(track.artist || '\u2014'));
    tr.appendChild(cell(track.album || '\u2014', 'hide-xs'));
    tr.appendChild(cell(track.rating ? '\u2605'.repeat(track.rating) : '', 'hide-xs'));
    tr.appendChild(cell(formatRelative(track.lastPlayedAt), 'hide-xs small text-secondary-themed'));
    return tr;
}

function cell(text, className) {
    const td = document.createElement('td');
    if (className) td.className = className;
    td.textContent = text;
    return td;
}

async function playAllFromEndpoint(url, label) {
    try {
        const r = await fetch(url + '?size=' + PLAY_MAX, { headers: SG.csrfHeaders() });
        if (!r.ok) { SG.showToast('Failed to load tracks'); return; }
        const d = await r.json();
        const tracks = d.items || [];
        if (tracks.length === 0) { SG.showToast('Nothing to play'); return; }
        SG.setPlaylistContext('rediscover:' + label, label, tracks);
        SG.playTrack(tracks[0]);
    } catch (e) {
        SG.showToast('Network error');
    }
}

// ── One-hit wonders section (grouped by artist) ──────────

function buildOneHitSection() {
    const card = document.createElement('div');
    card.className = 'card mb-3';
    card.id = 'rediscoverOneHit';
    card.innerHTML =
        `<div class="card-header">
            <h6 class="mb-0">One-hit wonders</h6>
            <div class="small text-secondary-themed">Artists with a single played track — the rest of their catalog is sitting unheard.</div>
         </div>
         <div class="card-body p-0" data-body><div class="small px-3 py-3 text-secondary-themed">Loading\u2026</div></div>`;

    const body = card.querySelector('[data-body]');
    fetch('/api/v1/library/rediscovery/one-hit-wonders?limit=20', { headers: SG.csrfHeaders() })
        .then(r => r.ok ? r.json() : Promise.reject(r))
        .then(d => {
            body.innerHTML = '';
            const items = d.items || [];
            if (items.length === 0) {
                body.innerHTML = '<div class="small px-3 py-3 text-secondary-themed">No one-hit-wonder artists yet. Either you\u2019ve explored deeply or you have few multi-track artists.</div>';
                return;
            }
            items.forEach(item => body.appendChild(buildOneHitArtist(item)));
        })
        .catch(() => {
            body.innerHTML = '<div class="small px-3 py-3 text-secondary-themed">Failed to load.</div>';
        });
    return card;
}

function buildOneHitArtist(item) {
    const wrap = document.createElement('div');
    wrap.className = 'p-3 border-top';
    const header = document.createElement('div');
    header.className = 'd-flex justify-content-between align-items-center mb-2 gap-2 flex-wrap';
    header.innerHTML =
        `<div>
            <strong>${SG.escapeHtml(item.artist || '\u2014')}</strong>
            <span class="small text-secondary-themed"> \u2014 ${item.playedTracks} of ${item.totalTracks} played</span>
         </div>`;
    const playBtn = document.createElement('button');
    playBtn.className = 'btn btn-sm btn-success';
    playBtn.textContent = '\u25B6 Play unplayed';
    playBtn.disabled = !item.unplayed || item.unplayed.length === 0;
    playBtn.addEventListener('click', () => {
        const tracks = item.unplayed || [];
        if (tracks.length === 0) return;
        SG.setPlaylistContext('rediscover:artist:' + item.artist, item.artist, tracks);
        SG.playTrack(tracks[0]);
    });
    header.appendChild(playBtn);
    wrap.appendChild(header);

    if (item.unplayed && item.unplayed.length > 0) {
        const ul = document.createElement('ul');
        ul.className = 'list-unstyled mb-0';
        item.unplayed.forEach(t => {
            const li = document.createElement('li');
            li.className = 'd-flex align-items-center gap-2 py-1';
            const b = document.createElement('button');
            b.className = 'btn-play-row';
            b.textContent = '\u25B6';
            b.setAttribute('aria-label', 'Play ' + (t.title || ''));
            b.addEventListener('click', () => {
                SG.setPlaylistContext('rediscover:artist:' + item.artist, item.artist, item.unplayed);
                SG.playTrack(t);
            });
            const label = document.createElement('span');
            label.textContent = (t.title || '\u2014') + (t.album ? '  \u2014  ' + t.album : '');
            li.appendChild(b);
            li.appendChild(label);
            ul.appendChild(li);
        });
        wrap.appendChild(ul);
    }
    return wrap;
}

// ── Helpers ──────────────────────────────────────────────

function formatRelative(iso) {
    if (!iso) return 'never';
    const then = new Date(iso).getTime();
    if (isNaN(then)) return '\u2014';
    const days = Math.floor((Date.now() - then) / 86400000);
    if (days < 1) return 'today';
    if (days < 30) return days + 'd ago';
    const months = Math.floor(days / 30);
    if (months < 12) return months + 'mo ago';
    return Math.floor(months / 12) + 'y ago';
}

// ── Wire DOM ─────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('showRediscoverBtn');
    if (btn) btn.addEventListener('click', (e) => {
        e.preventDefault();
        SG.navigate({ view: 'rediscover' });
    });
});

SG.renderRediscoverView = renderRediscoverView;

})();
