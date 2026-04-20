(function() {
'use strict';

/**
 * Tag management: modal editor (single-track + bulk), sidebar list, and filter.
 * Depends on SG namespace from app.js (csrfHeaders, showToast, guardClick, navigate).
 */

const MAX_TAGS_PER_TRACK = 20;
const MAX_TAG_LENGTH = 50;

let allTags = [];              // [{ tag, count }]
let tagModal = null;
let editorState = null;        // { mode: 'single'|'bulk', fileIds, current: Set, add: Set, remove: Set }

// ── Public API ───────────────────────────────────────────

async function loadTags() {
    try {
        const r = await fetch('/api/v1/library/tags', { headers: SG.csrfHeaders() });
        if (!r.ok) return;
        const data = await r.json();
        allTags = Array.isArray(data.tags) ? data.tags : [];
        renderSidebar();
    } catch (_) { /* silent */ }
}

function openSingleTrackEditor(file) {
    editorState = {
        mode: 'single',
        fileIds: [file.id],
        current: new Set(file.customTags || []),
        add: new Set(),    // unused in single mode
        remove: new Set()  // unused in single mode
    };
    document.getElementById('tagEditorTitle').textContent = 'Tags';
    document.getElementById('tagEditorSubtitle').textContent =
        (file.title || '\u2014') + (file.artist ? ' \u00B7 ' + file.artist : '');
    document.getElementById('tagEditorBulkHelp').classList.add('d-none');
    document.getElementById('tagEditorAddLabel').textContent = 'Tags';
    document.getElementById('tagEditorRemoveSection').classList.add('d-none');
    renderChips();
    openModal();
}

function openBulkEditor(fileIds) {
    if (!fileIds || fileIds.length === 0) return;
    editorState = {
        mode: 'bulk',
        fileIds: Array.from(fileIds),
        current: new Set(),
        add: new Set(),
        remove: new Set()
    };
    document.getElementById('tagEditorTitle').textContent = 'Tag ' + fileIds.length + ' tracks';
    document.getElementById('tagEditorSubtitle').textContent = '';
    document.getElementById('tagEditorBulkHelp').classList.remove('d-none');
    document.getElementById('tagEditorAddLabel').textContent = 'Tags to add';
    document.getElementById('tagEditorRemoveSection').classList.remove('d-none');
    renderChips();
    renderRemoveChips();
    openModal();
}

function getAllTags() { return allTags; }

// ── Modal rendering ──────────────────────────────────────

function openModal() {
    if (!tagModal) {
        tagModal = new bootstrap.Modal(document.getElementById('tagEditorModal'));
        document.getElementById('tagEditorModal').addEventListener('shown.bs.modal', () => {
            document.getElementById('tagEditorInput').focus();
        });
    }
    document.getElementById('tagEditorStatus').textContent = '';
    document.getElementById('tagEditorStatus').className = 'mt-2 small';
    document.getElementById('tagEditorInput').value = '';
    hideSuggestions();
    tagModal.show();
}

function renderChips() {
    const host = document.getElementById('tagEditorChips');
    host.innerHTML = '';
    const values = editorState.mode === 'single'
        ? [...editorState.current].sort()
        : [...editorState.add].sort();
    if (values.length === 0) {
        const empty = document.createElement('span');
        empty.className = 'tag-chip-empty small';
        empty.textContent = editorState.mode === 'single' ? 'No tags yet' : 'No tags to add';
        host.appendChild(empty);
        return;
    }
    values.forEach(t => host.appendChild(buildChip(t, () => {
        if (editorState.mode === 'single') editorState.current.delete(t);
        else editorState.add.delete(t);
        renderChips();
    })));
}

function renderRemoveChips() {
    const host = document.getElementById('tagEditorRemoveChips');
    host.innerHTML = '';
    const values = [...editorState.remove].sort();
    if (values.length === 0) {
        const empty = document.createElement('span');
        empty.className = 'tag-chip-empty small';
        empty.textContent = 'No tags to remove';
        host.appendChild(empty);
        return;
    }
    values.forEach(t => host.appendChild(buildChip(t, () => {
        editorState.remove.delete(t);
        renderRemoveChips();
    }, 'remove')));
}

function buildChip(tag, onRemove, variant) {
    const chip = document.createElement('span');
    chip.className = 'tag-chip' + (variant === 'remove' ? ' tag-chip--remove' : '');
    const label = document.createElement('span');
    label.className = 'tag-chip-label';
    label.textContent = tag;
    chip.appendChild(label);
    const x = document.createElement('button');
    x.type = 'button';
    x.className = 'tag-chip-x';
    x.setAttribute('aria-label', 'Remove ' + tag);
    x.textContent = '\u2715';
    x.addEventListener('click', onRemove);
    chip.appendChild(x);
    return chip;
}

// ── Input + autocomplete ─────────────────────────────────

function normalizeInput(raw) {
    return (raw || '').trim().replace(/\s+/g, ' ').toLowerCase();
}

function addTagFromInput(targetSet) {
    const input = document.getElementById('tagEditorInput');
    const value = normalizeInput(input.value);
    if (!value) return;
    if (value.length > MAX_TAG_LENGTH) {
        setStatus('Tag exceeds ' + MAX_TAG_LENGTH + ' characters', 'error');
        return;
    }
    if (editorState.mode === 'single' && editorState.current.size >= MAX_TAGS_PER_TRACK) {
        setStatus('A track can have at most ' + MAX_TAGS_PER_TRACK + ' tags', 'error');
        return;
    }
    targetSet.add(value);
    input.value = '';
    hideSuggestions();
    if (targetSet === editorState.remove) renderRemoveChips();
    else renderChips();
}

function currentTargetSet() {
    // Which chip collection the input feeds into.
    // Single mode: current tags. Bulk mode: add (remove uses its own input button).
    return editorState.mode === 'single' ? editorState.current : editorState.add;
}

function renderSuggestions(query) {
    const host = document.getElementById('tagEditorSuggestions');
    host.innerHTML = '';
    const q = normalizeInput(query);
    if (!q) { host.classList.add('d-none'); return; }
    const target = currentTargetSet();
    const matches = allTags
        .map(t => t.tag)
        .filter(t => t.startsWith(q) && !target.has(t))
        .slice(0, 8);
    if (matches.length === 0) { host.classList.add('d-none'); return; }
    matches.forEach(t => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'tag-suggestion';
        item.textContent = t;
        item.addEventListener('mousedown', (e) => {
            e.preventDefault();
            target.add(t);
            document.getElementById('tagEditorInput').value = '';
            hideSuggestions();
            if (target === editorState.remove) renderRemoveChips();
            else renderChips();
        });
        host.appendChild(item);
    });
    host.classList.remove('d-none');
}

function hideSuggestions() {
    document.getElementById('tagEditorSuggestions').classList.add('d-none');
}

function setStatus(msg, level) {
    const el = document.getElementById('tagEditorStatus');
    el.textContent = msg;
    el.className = 'mt-2 small status-' + (level === 'error' ? 'error' : 'success');
}

// ── Save ─────────────────────────────────────────────────

async function save() {
    const btn = document.getElementById('tagEditorSaveBtn');
    await SG.guardClick(btn, async () => {
        try {
            if (editorState.mode === 'single') {
                const fileId = editorState.fileIds[0];
                const tags = [...editorState.current];
                const r = await fetch(`/api/v1/library/files/${fileId}/tags`, {
                    method: 'PUT',
                    headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify({ tags })
                });
                if (!r.ok) { setStatus(await errorMsg(r, 'Failed to save tags'), 'error'); return; }
                const f = SG.allFiles.find(x => x.id === fileId);
                if (f) f.customTags = tags.length ? tags : null;
                setStatus('\u2713 Saved', 'success');
            } else {
                if (editorState.add.size === 0 && editorState.remove.size === 0) {
                    setStatus('Add or remove at least one tag', 'error');
                    return;
                }
                const body = {
                    fileIds: editorState.fileIds,
                    add: [...editorState.add],
                    remove: [...editorState.remove]
                };
                const r = await fetch('/api/v1/library/files/tags/bulk', {
                    method: 'POST',
                    headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify(body)
                });
                if (!r.ok) { setStatus(await errorMsg(r, 'Failed to apply tags'), 'error'); return; }
                const data = await r.json();
                // Optimistically update local state
                const targets = new Set(editorState.fileIds);
                SG.allFiles.forEach(f => {
                    if (!targets.has(f.id)) return;
                    const set = new Set(f.customTags || []);
                    editorState.remove.forEach(t => set.delete(t));
                    editorState.add.forEach(t => set.add(t));
                    f.customTags = set.size ? [...set] : null;
                });
                setStatus('\u2713 ' + data.modified + ' modified'
                    + (data.notFound ? ', ' + data.notFound + ' not found' : ''), 'success');
            }
            await loadTags(); // refresh counts
            setTimeout(() => tagModal && tagModal.hide(), 700);
        } catch (e) {
            setStatus('Network error', 'error');
        }
    });
}

async function errorMsg(resp, fallback) {
    try { const d = await resp.json(); return d.error || d.detail || d.message || fallback; }
    catch (_) { return fallback; }
}

// ── Sidebar list ─────────────────────────────────────────

function renderSidebar() {
    const ul = document.getElementById('tagList');
    if (!ul) return;
    ul.innerHTML = '';
    if (allTags.length === 0) {
        const li = document.createElement('li');
        li.className = 'playlist-list-empty small text-secondary-themed';
        li.textContent = 'No tags yet';
        ul.appendChild(li);
        return;
    }
    const currentTag = (SG.nav && SG.nav.tag) || null;
    allTags.forEach(t => {
        const li = document.createElement('li');
        li.className = 'playlist-list-item' + (t.tag === currentTag ? ' active' : '');
        const a = document.createElement('a');
        a.href = '#';
        a.className = 'playlist-link';
        a.addEventListener('click', (e) => {
            e.preventDefault();
            SG.navigate({ view: 'library', tag: t.tag });
        });
        const name = document.createElement('span');
        name.className = 'playlist-name';
        name.textContent = t.tag;
        const count = document.createElement('span');
        count.className = 'playlist-count';
        count.textContent = t.count;
        a.appendChild(name);
        a.appendChild(count);
        li.appendChild(a);
        ul.appendChild(li);
    });
}

// ── Wire DOM once ────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('tagEditorInput');
    if (!input) return;

    input.addEventListener('input', () => renderSuggestions(input.value));
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            addTagFromInput(currentTargetSet());
        } else if (e.key === 'Escape') {
            hideSuggestions();
        }
    });
    input.addEventListener('blur', () => setTimeout(hideSuggestions, 150));

    const addBtn = document.getElementById('tagEditorAddBtn');
    if (addBtn) addBtn.addEventListener('click', () => addTagFromInput(currentTargetSet()));

    const removeInput = document.getElementById('tagEditorRemoveInput');
    if (removeInput) {
        removeInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                const v = normalizeInput(removeInput.value);
                if (!v) return;
                editorState.remove.add(v);
                removeInput.value = '';
                renderRemoveChips();
            }
        });
    }
    const removeBtn = document.getElementById('tagEditorRemoveAddBtn');
    if (removeBtn) removeBtn.addEventListener('click', () => {
        const v = normalizeInput(removeInput.value);
        if (!v) return;
        editorState.remove.add(v);
        removeInput.value = '';
        renderRemoveChips();
    });

    const saveBtn = document.getElementById('tagEditorSaveBtn');
    if (saveBtn) saveBtn.addEventListener('click', save);
});

// Expose for app.js
window.SG = window.SG || {};
SG.loadTags = loadTags;
SG.openSingleTrackEditor = openSingleTrackEditor;
SG.openBulkTagEditor = openBulkEditor;
SG.renderTagsSidebar = renderSidebar;
SG.getAllTags = getAllTags;

})();
