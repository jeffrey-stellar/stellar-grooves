(function() {
'use strict';

const SG = window.SG;

// ── Theme toggle ────────────────────────────────────────
(function initTheme() {
    const saved = localStorage.getItem('sg-theme');
    const btn = document.getElementById('themeToggle');
    function applyTheme(theme) {
        if (theme === 'light') {
            document.documentElement.setAttribute('data-theme', 'light');
            if (btn) btn.textContent = '\u263E';
        } else if (theme === 'dark') {
            document.documentElement.setAttribute('data-theme', 'dark');
            if (btn) btn.textContent = '\u2606';
        } else {
            document.documentElement.removeAttribute('data-theme');
            const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            if (btn) btn.textContent = prefersDark ? '\u2606' : '\u263E';
        }
    }
    applyTheme(saved);
    if (btn) btn.addEventListener('click', () => {
        const current = document.documentElement.getAttribute('data-theme');
        const next = current === 'light' ? 'dark' : 'light';
        localStorage.setItem('sg-theme', next);
        applyTheme(next);
    });
})();

// ── Keyboard shortcut help ──────────────────────────────
document.getElementById('shortcutHelp').addEventListener('click', () => {
    SG.showToast('Space: Play/Pause | \u2190\u2192: Seek 5s | \u2191\u2193: Volume', 'info', 5000);
});

// ── Pause decorative animations when tab is hidden ──────
document.addEventListener('visibilitychange', () => {
    const paused = document.hidden;
    document.querySelectorAll('.wurl-bubbles, .wurl-starburst').forEach(el => {
        el.style.animationPlayState = paused ? 'paused' : '';
    });
});

// ── Cover Art Lightbox ──────────────────────────────────
SG.openCoverArtLightbox = function(src, album, artist) {
    const lb = document.getElementById('coverArtLightbox');
    document.getElementById('lightboxImage').src = src;
    document.getElementById('lightboxCaption').innerHTML =
        `<strong>${SG.escapeHtml(album)}</strong> &mdash; ${SG.escapeHtml(artist)}`;
    lb.classList.remove('d-none');
    document.body.style.overflow = 'hidden';
};

function closeCoverArtLightbox() {
    const lb = document.getElementById('coverArtLightbox');
    lb.classList.add('d-none');
    document.getElementById('lightboxImage').src = '';
    document.body.style.overflow = '';
}

document.querySelector('.cover-art-lightbox-backdrop')?.addEventListener('click', closeCoverArtLightbox);
document.querySelector('.cover-art-lightbox-close')?.addEventListener('click', closeCoverArtLightbox);
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !document.getElementById('coverArtLightbox').classList.contains('d-none')) {
        closeCoverArtLightbox();
    }
});

})();
