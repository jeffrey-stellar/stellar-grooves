/**
 * Pure utility functions extracted for testability.
 * Used by app.js (via global) and by unit tests (via import).
 */
(function(exports) {
'use strict';

/**
 * Return trimmed value or em dash for blank/null.
 */
function text(v) {
    return (v && v.trim()) ? v.trim() : '\u2014';
}

/**
 * HTML-escape a string to prevent XSS.
 */
function escapeHtml(s) {
    const d = document.createElement('div');
    d.appendChild(document.createTextNode(s || ''));
    return d.innerHTML;
}

/**
 * Format seconds as m:ss.
 */
function formatTime(s) {
    if (isNaN(s) || s < 0) return '0:00';
    return Math.floor(s / 60) + ':' + Math.floor(s % 60).toString().padStart(2, '0');
}

/**
 * Extract decade string from a year (e.g. 1987 -> "1980s"). Accepts number or
 * string - backups predating the int-year change still serialize as strings.
 */
function decadeFromYear(y) {
    if (y == null || y === '') return '\u2014';
    let n;
    if (typeof y === 'number') {
        n = y;
    } else {
        const s = String(y);
        if (s.length < 4) return '\u2014';
        n = parseInt(s.substring(0, 4), 10);
    }
    // Dual guard: string years are rejected by the length check above (< 4 chars),
    // numeric years by the magnitude check here (< 1000) \u2014 so e.g. "99" and 99 are
    // both rejected, just via different branches.
    if (isNaN(n) || n < 1000) return '\u2014';
    return Math.floor(n / 10) * 10 + 's';
}

/**
 * Compute crossfade volumes for a given progress (0..1).
 * @param {number} progress - 0 = start (outgoing full), 1 = end (incoming full)
 * @param {number} targetVolume - the outgoing audio's original volume
 * @returns {{ incoming: number, outgoing: number }}
 */
function crossfadeVolumes(progress, targetVolume) {
    return {
        incoming: Math.min(1, progress * targetVolume),
        outgoing: Math.max(0, (1 - progress) * targetVolume)
    };
}

/**
 * Filter and sort tracks based on navigation state and filters.
 * Pure function — takes state as arguments instead of reading DOM.
 *
 * @param {Array} allFiles - all track objects
 * @param {Object} nav - { artist, album }
 * @param {Object} filters - { genre, artist, album, query }
 * @param {Object} sortState - { col, dir }
 * @returns {Array} filtered and sorted tracks
 */
function getVisibleTracksPure(allFiles, nav, filters, sortState) {
    let f = allFiles;
    if (nav.artist) f = f.filter(x => (x.artist || '(Unknown)') === nav.artist);
    if (nav.album)  f = f.filter(x => (x.album  || '(Unknown)') === nav.album);
    if (!nav.artist && !nav.album) {
        if (filters.genre)  f = f.filter(x => x.genre === filters.genre);
        if (filters.artist) f = f.filter(x => x.artist === filters.artist);
        if (filters.album)  f = f.filter(x => x.album === filters.album);
        if (filters.query) {
            const q = filters.query.toLowerCase();
            f = f.filter(x =>
                (x.title  || '').toLowerCase().includes(q) ||
                (x.artist || '').toLowerCase().includes(q) ||
                (x.album  || '').toLowerCase().includes(q)
            );
        }
    }
    if (sortState && sortState.col) {
        f = [...f].sort((a, b) => {
            let av, bv;
            if (sortState.col === 'rating') {
                av = a.rating || 0; bv = b.rating || 0;
                return sortState.dir === 'asc' ? av - bv : bv - av;
            }
            av = a[sortState.col] || ''; bv = b[sortState.col] || '';
            const c = av.localeCompare(bv);
            return sortState.dir === 'asc' ? c : -c;
        });
    }
    return f;
}

/**
 * Compute virtual scroll indices for a given scroll position.
 * @param {number} totalItems - total number of items
 * @param {number} scrollTop - current scroll position
 * @param {number} viewportHeight - visible area height
 * @param {number} rowHeight - height of each row
 * @param {number} buffer - number of extra rows to render above/below
 * @returns {{ startIndex: number, endIndex: number }}
 */
function virtualScrollRange(totalItems, scrollTop, viewportHeight, rowHeight, buffer) {
    const si = Math.max(0, Math.floor(scrollTop / rowHeight) - buffer);
    const ei = Math.min(totalItems, Math.ceil((scrollTop + viewportHeight) / rowHeight) + buffer);
    return { startIndex: si, endIndex: ei };
}

// Export for both module (tests) and browser (IIFE)
exports.text = text;
exports.escapeHtml = escapeHtml;
exports.formatTime = formatTime;
exports.decadeFromYear = decadeFromYear;
exports.crossfadeVolumes = crossfadeVolumes;
exports.getVisibleTracksPure = getVisibleTracksPure;
exports.virtualScrollRange = virtualScrollRange;

})(typeof module !== 'undefined' && module.exports ? module.exports : (window.SGHelpers = {}));
