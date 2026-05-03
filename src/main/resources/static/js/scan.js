(function() {
'use strict';

const SG = window.SG;

// ── Scan progress (WebSocket + SSE fallback) ─────────────
// Stream lifecycle: progress events update the status line; snapshot lets a reconnecting
// client see current counts instead of starting at 0; complete carries the final totals
// and triggers library reload. Resolves the returned promise on complete or error so the
// submit handler can unblock its button.
function connectScanProgress(statusSpan, onComplete, onError) {
    const userId = document.querySelector('meta[name="_userId"]')?.content;
    if (!userId) return { cleanup: () => {} };

    let stompClient = null;
    let eventSource = null;

    function renderProgress(p) {
        statusSpan.textContent = `\u23F3 Scanning\u2026 ${p.saved} imported, ${p.skipped} skipped, ${p.errors} error(s)`;
    }

    try {
        stompClient = new StompJs.Client({
            webSocketFactory: () => new SockJS('/ws'),
            // 5s delay between reconnect attempts — 0 disables auto-reconnect entirely
            // in StompJS. Brief WebSocket drops mid-scan should recover automatically;
            // hard STOMP errors still fall through to the SSE fallback below.
            reconnectDelay: 5000,
            onConnect: () => {
                stompClient.subscribe('/topic/scan/' + userId, (message) => {
                    try {
                        const payload = JSON.parse(message.body);
                        if (payload.type === 'progress' || payload.type === 'snapshot') {
                            renderProgress(payload.data);
                        } else if (payload.type === 'complete') {
                            onComplete && onComplete(payload.data);
                        } else if (payload.type === 'error') {
                            onError && onError(payload.data && payload.data.message);
                        }
                    } catch (_) {}
                });
            },
            onStompError: () => { connectSSEFallback(); }
        });
        stompClient.activate();
    } catch (_) {
        connectSSEFallback();
    }

    function connectSSEFallback() {
        try {
            eventSource = new EventSource('/api/v1/library/scan/progress');
            const handleProgress = (ev) => { try { renderProgress(JSON.parse(ev.data)); } catch (_) {} };
            eventSource.addEventListener('progress', handleProgress);
            eventSource.addEventListener('snapshot', handleProgress);
            eventSource.addEventListener('complete', (ev) => {
                try { onComplete && onComplete(JSON.parse(ev.data)); } catch (_) { onComplete && onComplete(null); }
                try { eventSource.close(); } catch (_) {}
            });
            eventSource.addEventListener('error', (ev) => {
                try { const d = JSON.parse(ev.data); onError && onError(d && d.message); } catch (_) {}
            });
        } catch (_) {}
    }

    return {
        cleanup: () => {
            if (stompClient) try { stompClient.deactivate(); } catch (_) {}
            if (eventSource) try { eventSource.close(); } catch (_) {}
        }
    };
}

// ── Scan form ────────────────────────────────────────────
document.getElementById('scanForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const pv = document.getElementById('path').value.trim(), sd = document.getElementById('scanStatus'), btn = document.getElementById('scanBtn');
    if (!pv) { document.getElementById('path').classList.add('is-invalid'); return; }
    document.getElementById('path').classList.remove('is-invalid');
    btn.disabled = true; btn.classList.add('btn-loading');
    const ss = document.createElement('span'); ss.className = 'status-scanning'; ss.textContent = '\u23F3 Scanning\u2026'; sd.replaceChildren(ss);

    let progress;
    const finished = new Promise((resolve) => {
        progress = connectScanProgress(ss,
            (data) => { // complete
                let m = (data && data.saved > 0) ? `\u2713 ${data.saved} imported.` : '\u2713 No new files.';
                if (data && data.skipped > 0) m += ` ${data.skipped} skipped.`;
                if (data && data.errors > 0) m += ` ${data.errors} error(s).`;
                const s = document.createElement('span'); s.className = 'status-success'; s.textContent = m;
                sd.replaceChildren(s);
                resolve({ ok: true });
            },
            (errMsg) => { // error
                const s = document.createElement('span'); s.className = 'status-error';
                s.textContent = '\u2717 ' + (errMsg || 'Scan failed');
                sd.replaceChildren(s);
                resolve({ ok: false });
            });
    });

    try {
        const r = await fetch('/api/v1/library/scan', { method: 'POST', headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ path: pv }) });
        if (r.status === 202) {
            // Wait for progress stream to deliver the terminal event
            const outcome = await finished;
            if (outcome.ok) await SG.loadLibrary();
        } else {
            const d = await r.json().catch(() => ({}));
            const s = document.createElement('span'); s.className = 'status-error'; s.textContent = '\u2717 ' + (d.error || d.detail || 'Scan failed'); sd.replaceChildren(s);
        }
    } catch (er) { const s = document.createElement('span'); s.className = 'status-error'; s.textContent = '\u2717 Network error'; sd.replaceChildren(s); }
    finally { btn.disabled = false; btn.classList.remove('btn-loading'); if (progress) progress.cleanup(); }
});

// ── Clear library ────────────────────────────────────────
document.getElementById('clearBtn').addEventListener('click', async function() {
    if (!confirm('Clear your entire library? This cannot be undone.')) return;
    await SG.guardClick(this, async () => {
        const r = await fetch('/api/v1/library/files', { method: 'DELETE', headers: SG.csrfHeaders() });
        if (r.ok) { SG.setAllFiles([]); SG.setPlaylists([]); SG.setQueue([]); SG.navigate({ view: 'library' }); SG.updateStats(); SG.renderPlaylistSidebar(); SG.renderQueue(); const s = document.createElement('span'); s.className = 'status-success'; s.textContent = 'Library cleared.'; document.getElementById('scanStatus').replaceChildren(s); }
    });
});

// ── Scan Schedule ────────────────────────────────────────
SG.loadScanSchedule = async function() {
    try {
        const r = await fetch('/api/v1/library/scan/schedule');
        if (!r.ok) return;
        const d = await r.json();
        const section = document.getElementById('scanScheduleSection');
        const info = document.getElementById('scheduleInfo');
        const clearBtn = document.getElementById('clearScheduleBtn');
        section.classList.remove('d-none');
        if (d.cronExpression) {
            const lastScan = d.lastScheduledScan ? new Date(d.lastScheduledScan).toLocaleString() : 'Never';
            info.innerHTML = `<strong>Active:</strong> <code>${SG.escapeHtml(d.cronExpression)}</code><br>` +
                `<strong>Path:</strong> ${SG.escapeHtml(d.path || 'N/A')}<br>` +
                `<strong>Last run:</strong> ${lastScan}`;
            clearBtn.style.display = '';
        } else {
            info.textContent = 'No schedule configured.';
            clearBtn.style.display = 'none';
        }
    } catch (e) { console.error('Failed to load scan schedule', e); }
};

document.getElementById('schedulePreset').addEventListener('change', function() {
    const custom = document.getElementById('customCron');
    const saveBtn = document.getElementById('saveScheduleBtn');
    if (this.value === 'custom') { custom.classList.remove('d-none'); saveBtn.classList.remove('d-none'); }
    else if (this.value) { custom.classList.add('d-none'); saveBtn.classList.remove('d-none'); }
    else { custom.classList.add('d-none'); saveBtn.classList.add('d-none'); }
});

document.getElementById('saveScheduleBtn').addEventListener('click', async function() {
    const preset = document.getElementById('schedulePreset').value;
    const cron = preset === 'custom' ? document.getElementById('customCron').value.trim() : preset;
    const scanPath = document.getElementById('path').value.trim();
    if (!cron) { SG.showToast('Please select or enter a schedule.'); return; }
    if (!scanPath) { SG.showToast('Please enter a music directory path first.'); return; }
    await SG.guardClick(this, async () => {
        const r = await fetch('/api/v1/library/scan/schedule', {
            method: 'PUT', headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ cronExpression: cron, path: scanPath })
        });
        const d = await r.json();
        if (r.ok) {
            SG.showToast('Scan schedule saved.', 'info');
            document.getElementById('schedulePreset').value = '';
            document.getElementById('customCron').classList.add('d-none');
            document.getElementById('saveScheduleBtn').classList.add('d-none');
            SG.loadScanSchedule();
        } else { SG.showToast(d.detail || d.error || 'Failed to save schedule.'); }
    });
});

document.getElementById('clearScheduleBtn').addEventListener('click', async function() {
    if (!confirm('Remove the scan schedule?')) return;
    await SG.guardClick(this, async () => {
        const r = await fetch('/api/v1/library/scan/schedule', { method: 'DELETE', headers: SG.csrfHeaders() });
        if (r.ok) { SG.showToast('Scan schedule removed.', 'info'); SG.loadScanSchedule(); }
    });
});

})();
