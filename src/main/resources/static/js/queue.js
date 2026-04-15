(function() {
'use strict';

const SG = window.SG;

let _queueSaveTimer = null;

function saveQueue() {
    try {
        const minimal = SG.queue.map(f => ({ id: f.id, title: f.title, artist: f.artist, hasCoverArt: f.hasCoverArt }));
        localStorage.setItem('sg-queue', JSON.stringify(minimal));
    } catch (e) {}
    clearTimeout(_queueSaveTimer);
    _queueSaveTimer = setTimeout(syncQueueToServer, 2000);
}

async function syncQueueToServer() {
    try {
        const currentTrack = window._currentTrack || null;
        await fetch('/api/v1/library/queue', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', ...SG.csrfHeaders() },
            body: JSON.stringify({
                trackIds: SG.queue.map(f => f.id),
                currentTrackId: currentTrack ? currentTrack.id : null,
                shuffle: !!window._shuffleEnabled
            })
        });
    } catch (e) {}
}

SG.addToQueue = function(file) {
    SG.queue.push(file);
    SG.renderQueue();
};

SG.renderQueue = function() {
    const ul = document.getElementById('queueList'); ul.innerHTML = '';
    const clearBtn = document.getElementById('clearQueueBtn');
    if (SG.queue.length === 0) {
        ul.innerHTML = '<li class="playlist-empty-hint">Queue is empty</li>';
        clearBtn.style.display = 'none';
        saveQueue();
        return;
    }
    clearBtn.style.display = '';
    SG.queue.forEach((f, i) => {
        const li = document.createElement('li'); li.className = 'queue-item';
        const title = document.createElement('span'); title.className = 'queue-item-title'; title.textContent = SG.text(f.title) + (f.artist ? ' \u00B7 ' + f.artist : '');
        const rm = document.createElement('button'); rm.className = 'queue-item-remove'; rm.textContent = '\u2715'; rm.setAttribute('aria-label', 'Remove from queue');
        rm.addEventListener('click', () => { SG.queue.splice(i, 1); SG.renderQueue(); });
        li.appendChild(title); li.appendChild(rm); ul.appendChild(li);
    });
    saveQueue();
};

SG.loadSavedQueue = async function() {
    try {
        const r = await fetch('/api/v1/library/queue');
        if (r.ok) {
            const data = await r.json();
            if (data.trackIds && data.trackIds.length > 0) {
                SG.queue = data.trackIds.map(id => SG.allFiles.find(f => f.id === id)).filter(Boolean);
                if (SG.queue.length > 0) { SG.renderQueue(); return; }
            }
        }
    } catch (e) {}
    try {
        const saved = localStorage.getItem('sg-queue');
        if (saved) { const parsed = JSON.parse(saved); if (Array.isArray(parsed) && parsed.length > 0) { SG.queue = parsed; SG.renderQueue(); } }
    } catch (e) {}
};

document.getElementById('clearQueueBtn').addEventListener('click', async () => {
    SG.queue = []; SG.renderQueue();
    try { await fetch('/api/v1/library/queue', { method: 'DELETE', headers: SG.csrfHeaders() }); } catch (e) {}
});

})();
