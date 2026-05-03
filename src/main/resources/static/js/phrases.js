/**
 * Phrases UI — sidebar list, edit view, save/delete.
 * Phrases are reusable @name fragments referenced from smart-playlist queries.
 * Validation lives on the server; this module just surfaces parser errors that
 * come back from POST/PUT.
 *
 * Depends on SG globals from app.js: csrfHeaders, errorMsg, showToast,
 * navigate, escapeHtml, guardClick.
 */
(function() {
'use strict';

const SG = window.SG;

let phrases = [];

// ── Data loading ────────────────────────────────────────
async function loadPhrases() {
    try {
        const r = await fetch('/api/v1/smart-playlists/phrases');
        if (!r.ok) {
            SG.showToast(await SG.errorMsg(r, 'Failed to load phrases'));
            return;
        }
        phrases = await r.json();
        renderSidebar();
    } catch (e) {
        console.error('Failed to load phrases', e);
    }
}

function renderSidebar() {
    const ul = document.getElementById('phrasesList');
    if (!ul) return;
    ul.innerHTML = '';
    if (phrases.length === 0) {
        ul.innerHTML = '<li class="playlist-empty-hint">No phrases yet</li>';
        return;
    }
    const nav = window.nav || {};
    const sorted = [...phrases].sort((a, b) =>
        (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }));
    sorted.forEach(p => {
        const li = document.createElement('li');
        const isActive = nav.view === 'phrase' && nav.phraseId === p.id;
        li.className = 'playlist-item' + (isActive ? ' active' : '');
        const tip = p.description
            ? `@${p.name}\n${p.description}`
            : `@${p.name}`;
        li.innerHTML =
            `<span class="playlist-item-name" title="${SG.escapeHtml(tip)}">@${SG.escapeHtml(p.name)}</span>` +
            `<button class="playlist-item-del" data-id="${p.id}" title="Delete phrase" ` +
            `aria-label="Delete phrase @${SG.escapeHtml(p.name)}">✕</button>`;
        li.querySelector('.playlist-item-name').addEventListener('click', () => {
            SG.navigate({ view: 'phrase', phraseId: p.id });
        });
        li.querySelector('.playlist-item-del').addEventListener('click', async function(e) {
            e.stopPropagation();
            if (!confirm(`Delete phrase @${p.name}?\nSmart playlists referencing it will fail until you redefine or remove the reference.`)) return;
            await SG.guardClick(this, () => deletePhrase(p.id));
        });
        ul.appendChild(li);
    });
}

// ── Detail view ─────────────────────────────────────────
function renderView() {
    const nav = window.nav || {};
    const nameEl = document.getElementById('phName');
    const bodyEl = document.getElementById('phBody');
    const descEl = document.getElementById('phDescription');
    const deleteBtn = document.getElementById('phDeleteBtn');
    const statusEl = document.getElementById('phStatus');
    const hintEl = document.getElementById('phHint');

    if (statusEl) { statusEl.textContent = ''; statusEl.className = 'sp-status small'; }
    if (hintEl) hintEl.textContent = '';
    setBodyInvalid(false);

    if (nav.phraseId) {
        const existing = phrases.find(p => p.id === nav.phraseId);
        if (!existing) {
            // Not loaded yet or deleted — fall back to fresh form
            nameEl.value = '';
            bodyEl.value = '';
            if (descEl) descEl.value = '';
            deleteBtn.classList.add('d-none');
            return;
        }
        nameEl.value = existing.name;
        bodyEl.value = existing.body || '';
        if (descEl) descEl.value = existing.description || '';
        deleteBtn.classList.remove('d-none');
        // Don't allow rename in v1 — keeps things simple. Renaming a phrase that's
        // referenced elsewhere would silently break those queries.
        nameEl.readOnly = true;
        if (hintEl) hintEl.textContent = 'Renaming phrases is not supported in this release — create a new phrase if you need a different name.';
    } else {
        nameEl.value = '';
        bodyEl.value = '';
        if (descEl) descEl.value = '';
        deleteBtn.classList.add('d-none');
        nameEl.readOnly = false;
        if (hintEl) hintEl.textContent = '';
    }
}

async function save() {
    const nav = window.nav || {};
    const nameEl = document.getElementById('phName');
    const bodyEl = document.getElementById('phBody');
    const descEl = document.getElementById('phDescription');
    const statusEl = document.getElementById('phStatus');

    const name = (nameEl.value || '').trim().toLowerCase();
    const body = (bodyEl.value || '').trim();
    const description = descEl ? (descEl.value || '').trim() : '';

    if (!name) {
        statusEl.textContent = 'Name is required';
        statusEl.className = 'sp-status small status-error';
        return;
    }
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(name)) {
        statusEl.textContent = 'Name must be lowercase letters/digits/-/_ and start with a letter or digit';
        statusEl.className = 'sp-status small status-error';
        return;
    }
    if (!body) {
        statusEl.textContent = 'Body is required';
        statusEl.className = 'sp-status small status-error';
        setBodyInvalid(true);
        return;
    }

    const isUpdate = !!nav.phraseId;
    const url = isUpdate
        ? `/api/v1/smart-playlists/phrases/${nav.phraseId}`
        : '/api/v1/smart-playlists/phrases';
    const method = isUpdate ? 'PUT' : 'POST';

    try {
        const r = await fetch(url, {
            method,
            headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ name, body, description: description || null })
        });
        if (!r.ok) {
            const msg = await SG.errorMsg(r, 'Save failed');
            statusEl.textContent = msg;
            statusEl.className = 'sp-status small status-error';
            // Body parse errors come back from the server — flag the textarea
            if (/sort|limit|parse|expression|empty/i.test(msg)) setBodyInvalid(true);
            return;
        }
        const saved = await r.json();
        statusEl.textContent = isUpdate ? 'Saved' : 'Created';
        statusEl.className = 'sp-status small status-success';
        await loadPhrases();
        if (!isUpdate) {
            SG.navigate({ view: 'phrase', phraseId: saved.id });
        }
    } catch (e) {
        statusEl.textContent = 'Network error';
        statusEl.className = 'sp-status small status-error';
    }
}

async function deletePhrase(id) {
    const nav = window.nav || {};
    try {
        const r = await fetch(`/api/v1/smart-playlists/phrases/${id}`, {
            method: 'DELETE', headers: SG.csrfHeaders()
        });
        if (!r.ok) {
            SG.showToast(await SG.errorMsg(r, 'Failed to delete phrase'));
            return;
        }
        if (nav.view === 'phrase' && nav.phraseId === id) {
            SG.navigate({ view: 'library' });
        }
        await loadPhrases();
    } catch (e) {
        SG.showToast('Failed to delete phrase');
    }
}

function setBodyInvalid(invalid) {
    const bodyEl = document.getElementById('phBody');
    if (!bodyEl) return;
    if (invalid) {
        bodyEl.classList.add('is-invalid');
        bodyEl.setAttribute('aria-invalid', 'true');
    } else {
        bodyEl.classList.remove('is-invalid');
        bodyEl.removeAttribute('aria-invalid');
    }
}

// ── Wire events once DOM is ready ───────────────────────
function init() {
    const newBtn = document.getElementById('showNewPhraseBtn');
    if (newBtn) newBtn.addEventListener('click', () => SG.navigate({ view: 'phrase', phraseId: null }));

    const saveBtn = document.getElementById('phSaveBtn');
    if (saveBtn) saveBtn.addEventListener('click', () => SG.guardClick(saveBtn, save));

    const delBtn = document.getElementById('phDeleteBtn');
    if (delBtn) delBtn.addEventListener('click', async () => {
        const nav = window.nav || {};
        if (!nav.phraseId) return;
        const p = phrases.find(x => x.id === nav.phraseId);
        if (!p) return;
        if (!confirm(`Delete phrase @${p.name}?\nSmart playlists referencing it will fail until you redefine or remove the reference.`)) return;
        await SG.guardClick(delBtn, () => deletePhrase(p.id));
    });

    const body = document.getElementById('phBody');
    if (body) {
        body.addEventListener('keydown', e => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                save();
            }
        });
        body.addEventListener('input', () => setBodyInvalid(false));
    }
    const name = document.getElementById('phName');
    if (name) {
        name.addEventListener('input', () => {
            // Lowercase in real-time so users see what will be saved
            const start = name.selectionStart;
            const lower = name.value.toLowerCase();
            if (lower !== name.value) {
                name.value = lower;
                if (start != null) name.setSelectionRange(start, start);
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
SG.loadPhrases = loadPhrases;
SG.renderPhrasesSidebar = renderSidebar;
SG.renderPhraseView = renderView;

})();
