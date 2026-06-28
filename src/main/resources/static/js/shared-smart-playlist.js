(function(exports) {
'use strict';

// Public viewer for /shared/smart-playlists/{token}. Fetches the curator's
// query/description/match-count and offers a Subscribe button that lands the
// query in the visitor's smart-playlist sidebar (if logged in) or routes
// them to the login page first.

const token = document.querySelector('meta[name="_shareToken"]')?.content;

function csrfToken()      { return document.querySelector('meta[name="_csrf"]')?.content || ''; }
function csrfHeaderName() { return document.querySelector('meta[name="_csrf_header"]')?.content || 'X-XSRF-TOKEN'; }

function showError(msg) {
    document.getElementById('sharedLoading').classList.add('d-none');
    document.getElementById('sharedContent').classList.add('d-none');
    const e = document.getElementById('sharedError');
    e.textContent = msg;
    e.classList.remove('d-none');
}

function escapeText(s) {
    const div = document.createElement('div');
    div.textContent = s == null ? '' : String(s);
    return div.innerHTML;
}

async function load() {
    if (!token) {
        showError('No share token provided.');
        return;
    }
    try {
        const r = await fetch('/api/v1/shared/smart-playlists/' + encodeURIComponent(token));
        if (r.status === 410) {
            showError('This share link has expired.');
            return;
        }
        if (r.status === 404) {
            showError('This share link is invalid or has been revoked.');
            return;
        }
        if (!r.ok) {
            showError('Could not load shared smart playlist.');
            return;
        }
        const data = await r.json();
        render(data);
    } catch (e) {
        showError('Network error.');
    }
}

function render(data) {
    document.getElementById('sharedLoading').classList.add('d-none');
    document.getElementById('sharedContent').classList.remove('d-none');

    document.getElementById('sharedName').textContent = data.name || 'Untitled';
    document.getElementById('sharedCurator').textContent = data.curatorUsername || 'unknown';
    document.getElementById('sharedCuratorEcho').textContent = data.curatorUsername || 'their';
    document.getElementById('sharedQuery').textContent = data.queryString || '';
    document.getElementById('sharedMatchCount').textContent = data.matchCount != null ? data.matchCount : 0;

    const subEl = document.getElementById('sharedSubscriberCount');
    if (subEl && data.subscriberCount != null && data.subscriberCount > 0) {
        const n = data.subscriberCount;
        subEl.textContent = `· ${n} curator${n === 1 ? '' : 's'} subscribed.`;
        subEl.classList.remove('d-none');
    }

    const desc = document.getElementById('sharedDescription');
    if (data.description && String(data.description).trim()) {
        desc.textContent = data.description;
    } else {
        desc.classList.add('d-none');
    }

    renderActions();
}

function renderActions() {
    const actions = document.getElementById('sharedActions');
    actions.innerHTML = '';

    const subscribe = document.createElement('button');
    subscribe.className = 'btn btn-primary btn-lg';
    subscribe.type = 'button';
    subscribe.textContent = 'Subscribe to this query';
    subscribe.addEventListener('click', onSubscribe);
    actions.appendChild(subscribe);
}

async function onSubscribe() {
    const btn = document.querySelector('#sharedActions button');
    btn.disabled = true;
    btn.textContent = 'Subscribing…';
    try {
        const headers = { 'Content-Type': 'application/json' };
        const csrf = csrfToken();
        if (csrf) headers[csrfHeaderName()] = csrf;
        const r = await fetch('/api/v1/smart-playlists/subscribe', {
            method: 'POST',
            headers,
            body: JSON.stringify({ shareToken: token })
        });
        if (r.status === 401 || r.status === 403) {
            // Not logged in — route to login
            window.location.href = '/login';
            return;
        }
        if (r.status === 410) {
            showError('This share link has expired.');
            return;
        }
        if (!r.ok) {
            const data = await r.json().catch(() => ({}));
            btn.disabled = false;
            btn.textContent = 'Subscribe to this query';
            showError(data.detail || data.error || 'Subscribe failed.');
            return;
        }
        // Success — bounce to the app where the new sub appears in the sidebar.
        window.location.href = '/';
    } catch (e) {
        btn.disabled = false;
        btn.textContent = 'Subscribe to this query';
        showError('Network error.');
    }
}

document.addEventListener('DOMContentLoaded', load);

// Export for both module (tests) and browser (IIFE) — mirrors helpers.js.
exports.escapeText = escapeText;
exports.csrfToken = csrfToken;
exports.csrfHeaderName = csrfHeaderName;

})(typeof module !== 'undefined' && module.exports ? module.exports : (window.SGSharedPlaylist = {}));
