(function() {
'use strict';

const SG = window.SG;

// ── Scan progress (WebSocket + SSE fallback) ─────────────
function connectScanProgress(statusSpan) {
    const userId = document.querySelector('meta[name="_userId"]')?.content;
    if (!userId) return { cleanup: () => {} };

    let stompClient = null;
    let eventSource = null;

    try {
        stompClient = new StompJs.Client({
            webSocketFactory: () => new SockJS('/ws'),
            reconnectDelay: 0,
            onConnect: () => {
                stompClient.subscribe('/topic/scan/' + userId, (message) => {
                    try {
                        const payload = JSON.parse(message.body);
                        if (payload.type === 'progress') {
                            const p = payload.data;
                            statusSpan.textContent = `\u23F3 Scanning\u2026 ${p.saved} imported, ${p.skipped} skipped, ${p.errors} error(s)`;
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
            eventSource.addEventListener('progress', (ev) => {
                try {
                    const p = JSON.parse(ev.data);
                    statusSpan.textContent = `\u23F3 Scanning\u2026 ${p.saved} imported, ${p.skipped} skipped, ${p.errors} error(s)`;
                } catch (_) {}
            });
            eventSource.addEventListener('complete', () => { try { eventSource.close(); } catch (_) {} });
            eventSource.addEventListener('error', () => { try { eventSource.close(); } catch (_) {} });
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

    const progress = connectScanProgress(ss);

    try {
        const r = await fetch('/api/v1/library/scan', { method: 'POST', headers: SG.csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ path: pv }) });
        const d = await r.json();
        if (r.ok) { let m = d.filesFound > 0 ? `\u2713 ${d.filesFound} imported.` : '\u2713 No new files.'; if (d.skipped > 0) m += ` ${d.skipped} skipped.`; if (d.errors > 0) m += ` ${d.errors} error(s).`; const s = document.createElement('span'); s.className = 'status-success'; s.textContent = m; sd.replaceChildren(s); await SG.loadLibrary(); }
        else { const s = document.createElement('span'); s.className = 'status-error'; s.textContent = '\u2717 ' + (d.error || d.detail || 'Scan failed'); sd.replaceChildren(s); }
    } catch (er) { const s = document.createElement('span'); s.className = 'status-error'; s.textContent = '\u2717 Network error'; sd.replaceChildren(s); }
    finally { btn.disabled = false; btn.classList.remove('btn-loading'); progress.cleanup(); }
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
