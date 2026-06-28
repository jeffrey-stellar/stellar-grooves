/* ══════════════════════════════════════════════
   Stellar Grooves – Audio Player & Equalizer
   Handles playback, crossfade, Web Audio EQ,
   keyboard shortcuts, and player UI sync.
   ══════════════════════════════════════════════ */
(function() {
'use strict';

const CROSSFADE_DURATION = 3; // seconds

// ── DOM refs (set once) ─────────────────────────────────
const audioEl  = document.getElementById('audioPlayer');
const audioElB = document.getElementById('audioPlayerB');
const playerBar = document.getElementById('jukeboxPlayer');
const eqCanvas = document.getElementById('eqCanvas');

let activeAudio = audioEl;
let crossfadeEnabled = false;
let _crossfadeTimer = null;
let _crossfadeTriggered = false;

// ── Equalizer state ─────────────────────────────────────
let audioCtx = null, analyser = null, eqAnimId = null, eqConnected = false;
const eqSources = new Map();

// iOS (including iPadOS, which reports a desktop Safari UA but exposes touch)
// suspends the Web Audio AudioContext whenever the screen locks or the PWA is
// backgrounded. The visual equalizer routes the <audio> element through that
// context (createMediaElementSource), so on iOS playback goes silent on lock.
// We detect iOS and skip the equalizer there so audio plays straight to the
// system output and keeps going in the background — see initEqualizer().
const IS_IOS = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.userAgent.includes('Macintosh') && 'ontouchend' in document);

// ── Play history accumulator ────────────────────────────
// Track listened time per play. Seeks don't count — we only add time when
// currentTime advanced roughly in real time since the last tick.
const PLAY_REPORT_THRESHOLD = 0.5; // fire at 50% listened OR on `ended`
let _playFileId = null;
let _playListenedMs = 0;
let _playLastPos = 0;
let _playLastTickAt = 0;
let _playReported = false;

function _resetPlayAccumulator(fileId) {
    _playFileId = fileId || null;
    _playListenedMs = 0;
    _playLastPos = 0;
    _playLastTickAt = Date.now();
    _playReported = false;
}

function _reportPlay(completed) {
    if (!_playFileId || _playReported) return;
    _playReported = true;
    const fileId = _playFileId;
    const listenedMs = Math.max(0, Math.round(_playListenedMs));
    const headers = (window.SG && SG.csrfHeaders)
        ? SG.csrfHeaders({ 'Content-Type': 'application/json' })
        : { 'Content-Type': 'application/json' };
    fetch(`/api/v1/library/files/${fileId}/plays`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ listenedMs, completed: !!completed })
    }).then(r => {
        // Best-effort telemetry — surface server errors in console so a curator
        // debugging "why are my play counts wrong?" can see what happened.
        if (!r.ok) console.warn('Play recording rejected:', r.status, fileId);
    }).catch(err => { console.warn('Play recording network error:', err && err.message); });
}

function _accumulateListened(el) {
    const now = Date.now();
    const elapsed = now - _playLastTickAt;
    _playLastTickAt = now;
    const delta = el.currentTime - _playLastPos;
    _playLastPos = el.currentTime;
    // Only count forward motion that roughly matches wall-clock time (rejects seeks).
    // Allow a 2x slop to tolerate timeupdate jitter.
    if (delta > 0 && delta * 1000 <= elapsed * 2) {
        _playListenedMs += delta * 1000;
    }
    if (!_playReported && el.duration > 0
            && (_playListenedMs / 1000) / el.duration >= PLAY_REPORT_THRESHOLD) {
        _reportPlay(false);
    }
}

// ── Public API (consumed by app.js) ─────────────────────
window.SG = window.SG || {};

SG.getActiveAudio = function() { return activeAudio; };
SG.getPlayerBar   = function() { return playerBar; };
SG.isPlaying      = function() { return !activeAudio.paused; };

SG.playTrack = function(file, useCrossfade) {
    const sw = SG.currentFileId !== file.id;
    if (sw) {
        // If switching tracks, finalize the prior play first
        _reportPlay(false);
        _resetPlayAccumulator(file.id);
    }
    SG.currentFileId = file.id;
    window._currentTrack = file;
    document.getElementById('playerTitle').textContent = SG.text(file.title);
    document.getElementById('playerArtist').textContent = SG.text(file.artist);
    playerBar.classList.remove('d-none');

    // Cover art
    const artEl = document.getElementById('playerArt');
    const artPlaceholder = document.getElementById('jukeboxArtPlaceholder');
    if (file.hasCoverArt) {
        artEl.src = `/api/v1/library/files/${file.id}/cover`;
        artEl.classList.remove('d-none');
        if (artPlaceholder) artPlaceholder.style.display = 'none';
    } else {
        artEl.classList.add('d-none'); artEl.removeAttribute('src');
        if (artPlaceholder) artPlaceholder.style.display = '';
        artPlaceholder.classList.remove('spinning');
    }

    initEqualizer();

    if (sw && useCrossfade && crossfadeEnabled) {
        const outgoing = activeAudio;
        const incoming = (activeAudio === audioEl) ? audioElB : audioEl;
        incoming.src = `/api/v1/library/files/${file.id}/stream`;
        incoming.volume = 0;
        incoming.load();
        incoming.play();
        activeAudio = incoming;

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
        if (activeAudio !== audioEl) { audioElB.pause(); activeAudio = audioEl; }
        audioEl.src = `/api/v1/library/files/${file.id}/stream`;
        audioEl.load();
        audioEl.play();
    } else {
        activeAudio.play();
    }

    // Media Session API — lock screen / notification metadata
    if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({
            title: file.title || 'Unknown Track',
            artist: file.artist || 'Unknown Artist',
            album: file.album || '',
            artwork: file.hasCoverArt
                ? [{ src: `/api/v1/library/files/${file.id}/cover`, sizes: '512x512', type: 'image/jpeg' }]
                : [
                    { src: '/images/icon-192.png', sizes: '192x192', type: 'image/png' },
                    { src: '/images/icon-512.png', sizes: '512x512', type: 'image/png' }
                  ]
        });
    }
};

SG.syncPlayerBtn = function() {
    const btn = document.getElementById('playerPlayPause');
    btn.textContent = activeAudio.paused ? '\u25B6' : '\u23F8';
    btn.setAttribute('aria-label', activeAudio.paused ? 'Play' : 'Pause');

    // Sync Media Session playback state
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = activeAudio.paused ? 'paused' : 'playing';
    }

    const art = document.getElementById('playerArt');
    const vinyl = document.getElementById('jukeboxArtPlaceholder');
    const tonearm = document.getElementById('tonearm');
    const isPlaying = !activeAudio.paused;
    if (art) art.classList.toggle('spinning', isPlaying);
    if (vinyl) vinyl.classList.toggle('spinning', isPlaying);
    if (tonearm) tonearm.classList.toggle('playing', isPlaying);

    document.querySelectorAll('.btn-play-row').forEach(b => {
        const tr = b.closest('tr');
        const a = tr && tr.dataset.fileId === SG.currentFileId;
        b.textContent = (a && !activeAudio.paused) ? '\u23F8' : '\u25B6';
        b.classList.toggle('playing', a);
    });

    // Update jukebox side panel active states
    document.querySelectorAll('.wurl-side-item').forEach(li => {
        li.classList.toggle('wurl-side-active', li.dataset.fileId === SG.currentFileId);
    });
};

// ── Crossfade toggle ────────────────────────────────────
document.getElementById('playerCrossfade').addEventListener('click', () => {
    crossfadeEnabled = !crossfadeEnabled;
    const b = document.getElementById('playerCrossfade');
    b.classList.toggle('active', crossfadeEnabled);
    b.setAttribute('aria-pressed', String(crossfadeEnabled));
    b.title = crossfadeEnabled ? 'Crossfade ON (3s)' : 'Crossfade (3s)';
});

// ── Play / pause events ─────────────────────────────────
document.getElementById('playerPlayPause').addEventListener('click', () => {
    if (activeAudio.paused) activeAudio.play(); else activeAudio.pause();
});

audioEl.addEventListener('play',  SG.syncPlayerBtn);
audioEl.addEventListener('pause', SG.syncPlayerBtn);
audioElB.addEventListener('play',  SG.syncPlayerBtn);
audioElB.addEventListener('pause', SG.syncPlayerBtn);

// ── Time update & track end ─────────────────────────────
function handleTrackTimeUpdate(el) {
    if (!isNaN(el.duration) && el.duration > 0 && el === activeAudio) {
        _accumulateListened(el);
        document.getElementById('playerSeek').value = (el.currentTime / el.duration) * 100;
        document.getElementById('playerCurrentTime').textContent = SG.formatTime(el.currentTime);
        if (crossfadeEnabled && !_crossfadeTriggered && el.duration - el.currentTime <= CROSSFADE_DURATION) {
            _crossfadeTriggered = true;
            SG.playNextTrack(true);
        }
        // Report position to Media Session
        if ('mediaSession' in navigator && navigator.mediaSession.setPositionState) {
            try {
                navigator.mediaSession.setPositionState({
                    duration: el.duration,
                    playbackRate: el.playbackRate,
                    position: el.currentTime
                });
            } catch (_) { /* ignore invalid state errors */ }
        }
    }
}
function handleTrackEnded(el) {
    SG.syncPlayerBtn();
    if (el !== activeAudio) return;
    _reportPlay(true);
    _crossfadeTriggered = false;
    if (!crossfadeEnabled) SG.playNextTrack(false);
}

audioEl.addEventListener('ended', () => handleTrackEnded(audioEl));
audioElB.addEventListener('ended', () => handleTrackEnded(audioElB));
audioEl.addEventListener('timeupdate', () => handleTrackTimeUpdate(audioEl));
audioElB.addEventListener('timeupdate', () => handleTrackTimeUpdate(audioElB));
audioEl.addEventListener('loadedmetadata', () => { if (activeAudio === audioEl) document.getElementById('playerDuration').textContent = SG.formatTime(audioEl.duration); });
audioElB.addEventListener('loadedmetadata', () => { if (activeAudio === audioElB) document.getElementById('playerDuration').textContent = SG.formatTime(audioElB.duration); });
audioEl.addEventListener('loadstart', () => { if (activeAudio === audioEl) _crossfadeTriggered = false; });
audioElB.addEventListener('loadstart', () => { if (activeAudio === audioElB) _crossfadeTriggered = false; });

// Seek events reset the listened-time baseline so scrubs don't count as listening.
function _onSeeked(el) {
    if (el !== activeAudio) return;
    _playLastPos = el.currentTime;
    _playLastTickAt = Date.now();
}
audioEl.addEventListener('seeked', () => _onSeeked(audioEl));
audioElB.addEventListener('seeked', () => _onSeeked(audioElB));

document.getElementById('playerSeek').addEventListener('input', () => {
    if (!isNaN(activeAudio.duration)) activeAudio.currentTime = (document.getElementById('playerSeek').value / 100) * activeAudio.duration;
});
document.getElementById('playerVolume').addEventListener('input', () => {
    activeAudio.volume = document.getElementById('playerVolume').value;
});

// ── Shuffle toggle ──────────────────────────────────────
SG.shuffleEnabled = false;
document.getElementById('playerShuffle').addEventListener('click', () => {
    SG.shuffleEnabled = !SG.shuffleEnabled;
    window._shuffleEnabled = SG.shuffleEnabled;
    const b = document.getElementById('playerShuffle');
    b.classList.toggle('active', SG.shuffleEnabled);
    b.setAttribute('aria-pressed', String(SG.shuffleEnabled));
});

// ── Skip to next track ──────────────────────────────────
document.getElementById('playerNext').addEventListener('click', () => {
    SG.playNextTrack(false);
});

// ── Previous track ──────────────────────────────────────
// >3s in (or shuffle on) → restart current; otherwise walk context backwards.
function goPrevious() {
    if (activeAudio.currentTime > 3 || SG.shuffleEnabled) {
        activeAudio.currentTime = 0;
    } else {
        SG.playPreviousTrack(false);
    }
}
document.getElementById('playerPrev').addEventListener('click', goPrevious);

// ── Media Session action handlers (lock screen controls) ──
if ('mediaSession' in navigator) {
    navigator.mediaSession.setActionHandler('play', () => activeAudio.play());
    navigator.mediaSession.setActionHandler('pause', () => activeAudio.pause());
    navigator.mediaSession.setActionHandler('previoustrack', goPrevious);
    navigator.mediaSession.setActionHandler('nexttrack', () => {
        SG.playNextTrack(false);
    });
    navigator.mediaSession.setActionHandler('seekto', (details) => {
        if (details.seekTime != null && !isNaN(activeAudio.duration)) {
            activeAudio.currentTime = details.seekTime;
        }
    });
    navigator.mediaSession.setActionHandler('seekbackward', (details) => {
        activeAudio.currentTime = Math.max(0, activeAudio.currentTime - (details.seekOffset || 10));
    });
    navigator.mediaSession.setActionHandler('seekforward', (details) => {
        activeAudio.currentTime = Math.min(activeAudio.duration || 0, activeAudio.currentTime + (details.seekOffset || 10));
    });
}

// ── Visual Equalizer (Web Audio API) ────────────────────
function initEqualizer() {
    if (eqConnected) return;
    // On iOS, routing audio through the AudioContext breaks lock-screen/background
    // playback (the context is suspended on lock). Skip the visualizer entirely so
    // the <audio> element keeps playing in the background; hide the empty canvas.
    if (IS_IOS) {
        if (eqCanvas) eqCanvas.style.display = 'none';
        return;
    }
    try {
        audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
        analyser = audioCtx.createAnalyser();
        analyser.fftSize = 128;
        analyser.smoothingTimeConstant = 0.8;
        analyser.connect(audioCtx.destination);
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

        const barCount = 24;
        const barW = (W / barCount) - 2;
        const gap = 2;
        for (let i = 0; i < barCount; i++) {
            const idx = Math.floor(Math.pow(i / barCount, 1.5) * bufLen);
            const val = data[Math.min(idx, bufLen - 1)] / 255;
            const barH = Math.max(2, val * H);
            const x = i * (barW + gap) + gap / 2;
            const y = H - barH;

            const grad = ctx.createLinearGradient(x, H, x, 0);
            grad.addColorStop(0, 'rgba(255, 209, 102, 0.9)');
            grad.addColorStop(0.5, 'rgba(255, 122, 26, 0.9)');
            grad.addColorStop(1, 'rgba(255, 59, 31, 0.9)');
            ctx.fillStyle = grad;

            const radius = Math.min(barW / 2, 3);
            ctx.beginPath();
            ctx.moveTo(x, H);
            ctx.lineTo(x, y + radius);
            ctx.quadraticCurveTo(x, y, x + radius, y);
            ctx.lineTo(x + barW - radius, y);
            ctx.quadraticCurveTo(x + barW, y, x + barW, y + radius);
            ctx.lineTo(x + barW, H);
            ctx.fill();

            if (val > 0.6) {
                ctx.shadowColor = 'rgba(255, 59, 31, 0.4)';
                ctx.shadowBlur = 6;
                ctx.fill();
                ctx.shadowBlur = 0;
            }
        }
    }
    frame();
}

// ── Keyboard shortcuts ──────────────────────────────────
document.addEventListener('keydown', e => {
    if (playerBar.classList.contains('d-none')) return;
    const t = document.activeElement.tagName;
    if (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT') return;
    switch (e.key) {
        case ' ': e.preventDefault(); if (activeAudio.paused) activeAudio.play(); else activeAudio.pause(); break;
        case 'ArrowRight': e.preventDefault(); if (!isNaN(activeAudio.duration)) activeAudio.currentTime = Math.min(activeAudio.duration, activeAudio.currentTime + 5); break;
        case 'ArrowLeft': e.preventDefault(); activeAudio.currentTime = Math.max(0, activeAudio.currentTime - 5); break;
        case 'ArrowUp': e.preventDefault(); activeAudio.volume = Math.min(1, activeAudio.volume + 0.05); document.getElementById('playerVolume').value = activeAudio.volume; break;
        case 'ArrowDown': e.preventDefault(); activeAudio.volume = Math.max(0, activeAudio.volume - 0.05); document.getElementById('playerVolume').value = activeAudio.volume; break;
    }
});

})();
