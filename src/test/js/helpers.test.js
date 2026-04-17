/**
 * @vitest-environment jsdom
 */
import { describe, it, expect } from 'vitest';
import {
    text,
    escapeHtml,
    formatTime,
    decadeFromYear,
    crossfadeVolumes,
    getVisibleTracksPure,
    virtualScrollRange
} from '../../main/resources/static/js/helpers.js';

// ── text() ──────────────────────────────────────────────────

describe('text()', () => {
    it('returns trimmed string for non-empty input', () => {
        expect(text('Hello')).toBe('Hello');
    });

    it('trims whitespace', () => {
        expect(text('  Hello  ')).toBe('Hello');
    });

    it('returns em dash for null', () => {
        expect(text(null)).toBe('\u2014');
    });

    it('returns em dash for undefined', () => {
        expect(text(undefined)).toBe('\u2014');
    });

    it('returns em dash for empty string', () => {
        expect(text('')).toBe('\u2014');
    });

    it('returns em dash for whitespace-only string', () => {
        expect(text('   ')).toBe('\u2014');
    });
});

// ── escapeHtml() ────────────────────────────────────────────

describe('escapeHtml()', () => {
    it('escapes angle brackets', () => {
        expect(escapeHtml('<script>alert("xss")</script>')).toBe(
            '&lt;script&gt;alert("xss")&lt;/script&gt;'
        );
    });

    it('escapes ampersands', () => {
        expect(escapeHtml('A & B')).toBe('A &amp; B');
    });

    it('handles null gracefully', () => {
        expect(escapeHtml(null)).toBe('');
    });

    it('handles empty string', () => {
        expect(escapeHtml('')).toBe('');
    });

    it('returns plain text unchanged', () => {
        expect(escapeHtml('Hello World')).toBe('Hello World');
    });
});

// ── formatTime() ────────────────────────────────────────────

describe('formatTime()', () => {
    it('formats 0 seconds', () => {
        expect(formatTime(0)).toBe('0:00');
    });

    it('formats seconds under a minute', () => {
        expect(formatTime(45)).toBe('0:45');
    });

    it('formats exact minutes', () => {
        expect(formatTime(120)).toBe('2:00');
    });

    it('formats minutes and seconds', () => {
        expect(formatTime(185)).toBe('3:05');
    });

    it('pads single-digit seconds', () => {
        expect(formatTime(63)).toBe('1:03');
    });

    it('handles NaN', () => {
        expect(formatTime(NaN)).toBe('0:00');
    });

    it('handles negative values', () => {
        expect(formatTime(-10)).toBe('0:00');
    });

    it('handles large values', () => {
        expect(formatTime(3661)).toBe('61:01');
    });

    it('truncates fractional seconds', () => {
        expect(formatTime(65.7)).toBe('1:05');
    });
});

// ── decadeFromYear() ────────────────────────────────────────

describe('decadeFromYear()', () => {
    it('returns correct decade for 1987', () => {
        expect(decadeFromYear('1987')).toBe('1980s');
    });

    it('returns correct decade for 2000', () => {
        expect(decadeFromYear('2000')).toBe('2000s');
    });

    it('returns correct decade for 1973', () => {
        expect(decadeFromYear('1973')).toBe('1970s');
    });

    it('returns em dash for null', () => {
        expect(decadeFromYear(null)).toBe('\u2014');
    });

    it('returns em dash for empty string', () => {
        expect(decadeFromYear('')).toBe('\u2014');
    });

    it('returns em dash for short string', () => {
        expect(decadeFromYear('99')).toBe('\u2014');
    });

    it('returns em dash for non-numeric year', () => {
        expect(decadeFromYear('abcd')).toBe('\u2014');
    });

    it('handles year with extra text', () => {
        expect(decadeFromYear('1985-01-15')).toBe('1980s');
    });
});

// ── crossfadeVolumes() ──────────────────────────────────────

describe('crossfadeVolumes()', () => {
    it('returns outgoing full and incoming silent at progress=0', () => {
        const { incoming, outgoing } = crossfadeVolumes(0, 1.0);
        expect(incoming).toBe(0);
        expect(outgoing).toBe(1.0);
    });

    it('returns incoming full and outgoing silent at progress=1', () => {
        const { incoming, outgoing } = crossfadeVolumes(1, 1.0);
        expect(incoming).toBe(1.0);
        expect(outgoing).toBe(0);
    });

    it('returns equal volumes at progress=0.5', () => {
        const { incoming, outgoing } = crossfadeVolumes(0.5, 1.0);
        expect(incoming).toBe(0.5);
        expect(outgoing).toBe(0.5);
    });

    it('respects target volume less than 1', () => {
        const { incoming, outgoing } = crossfadeVolumes(0.5, 0.6);
        expect(incoming).toBeCloseTo(0.3);
        expect(outgoing).toBeCloseTo(0.3);
    });

    it('clamps incoming to max 1', () => {
        const { incoming } = crossfadeVolumes(1.5, 1.0);
        expect(incoming).toBe(1.0);
    });

    it('clamps outgoing to min 0', () => {
        const { outgoing } = crossfadeVolumes(1.5, 1.0);
        expect(outgoing).toBe(0);
    });

    it('handles target volume of 0', () => {
        const { incoming, outgoing } = crossfadeVolumes(0.5, 0);
        expect(incoming).toBe(0);
        expect(outgoing).toBe(0);
    });

    it('progresses linearly over steps', () => {
        const steps = 20;
        for (let s = 0; s <= steps; s++) {
            const progress = s / steps;
            const { incoming, outgoing } = crossfadeVolumes(progress, 1.0);
            expect(incoming + outgoing).toBeCloseTo(1.0, 10);
        }
    });
});

// ── getVisibleTracksPure() ──────────────────────────────────

describe('getVisibleTracksPure()', () => {
    const tracks = [
        { id: '1', title: 'Iron Man',       artist: 'Black Sabbath', album: 'Paranoid',    genre: 'HEAVY_METAL', rating: 5 },
        { id: '2', title: 'Paranoid',       artist: 'Black Sabbath', album: 'Paranoid',    genre: 'HEAVY_METAL', rating: 4 },
        { id: '3', title: 'Detroit Rock City', artist: 'KISS',       album: 'Destroyer',   genre: 'HARD_ROCK',   rating: 3 },
        { id: '4', title: 'Panama',         artist: 'Van Halen',    album: 'MCMLXXXIV',   genre: 'HARD_ROCK',   rating: 5 },
        { id: '5', title: 'Unknown Song',   artist: null,           album: null,           genre: 'OTHER',       rating: 0 },
    ];
    const noNav = { artist: null, album: null };
    const noFilters = {};
    const noSort = {};

    it('returns all tracks with no filters', () => {
        const result = getVisibleTracksPure(tracks, noNav, noFilters, noSort);
        expect(result).toHaveLength(5);
    });

    it('filters by artist via nav', () => {
        const result = getVisibleTracksPure(tracks, { artist: 'Black Sabbath', album: null }, noFilters, noSort);
        expect(result).toHaveLength(2);
        expect(result.every(t => t.artist === 'Black Sabbath')).toBe(true);
    });

    it('filters by album via nav', () => {
        const result = getVisibleTracksPure(tracks, { artist: null, album: 'Paranoid' }, noFilters, noSort);
        expect(result).toHaveLength(2);
    });

    it('filters by artist + album via nav', () => {
        const result = getVisibleTracksPure(tracks, { artist: 'Black Sabbath', album: 'Paranoid' }, noFilters, noSort);
        expect(result).toHaveLength(2);
    });

    it('filters by genre', () => {
        const result = getVisibleTracksPure(tracks, noNav, { genre: 'HARD_ROCK' }, noSort);
        expect(result).toHaveLength(2);
    });

    it('filters by text query (title)', () => {
        const result = getVisibleTracksPure(tracks, noNav, { query: 'iron' }, noSort);
        expect(result).toHaveLength(1);
        expect(result[0].title).toBe('Iron Man');
    });

    it('filters by text query (artist)', () => {
        const result = getVisibleTracksPure(tracks, noNav, { query: 'kiss' }, noSort);
        expect(result).toHaveLength(1);
    });

    it('filters by text query (album)', () => {
        const result = getVisibleTracksPure(tracks, noNav, { query: 'destroyer' }, noSort);
        expect(result).toHaveLength(1);
    });

    it('query is case-insensitive', () => {
        const result = getVisibleTracksPure(tracks, noNav, { query: 'PARANOID' }, noSort);
        expect(result).toHaveLength(2);
    });

    it('combines genre and query filters', () => {
        const result = getVisibleTracksPure(tracks, noNav, { genre: 'HEAVY_METAL', query: 'iron' }, noSort);
        expect(result).toHaveLength(1);
    });

    it('sorts by title ascending', () => {
        const result = getVisibleTracksPure(tracks, noNav, noFilters, { col: 'title', dir: 'asc' });
        expect(result[0].title).toBe('Detroit Rock City');
        expect(result[result.length - 1].title).toBe('Unknown Song');
    });

    it('sorts by title descending', () => {
        const result = getVisibleTracksPure(tracks, noNav, noFilters, { col: 'title', dir: 'desc' });
        expect(result[0].title).toBe('Unknown Song');
    });

    it('sorts by rating ascending', () => {
        const result = getVisibleTracksPure(tracks, noNav, noFilters, { col: 'rating', dir: 'asc' });
        expect(result[0].rating).toBe(0);
        expect(result[result.length - 1].rating).toBe(5);
    });

    it('sorts by rating descending', () => {
        const result = getVisibleTracksPure(tracks, noNav, noFilters, { col: 'rating', dir: 'desc' });
        expect(result[0].rating).toBe(5);
    });

    it('ignores filters when nav.artist is set', () => {
        const result = getVisibleTracksPure(tracks, { artist: 'KISS', album: null }, { genre: 'HEAVY_METAL' }, noSort);
        expect(result).toHaveLength(1);
        expect(result[0].artist).toBe('KISS');
    });

    it('handles null artist as (Unknown)', () => {
        const result = getVisibleTracksPure(tracks, { artist: '(Unknown)', album: null }, noFilters, noSort);
        expect(result).toHaveLength(1);
        expect(result[0].id).toBe('5');
    });

    it('returns empty for no matches', () => {
        const result = getVisibleTracksPure(tracks, noNav, { query: 'nonexistent' }, noSort);
        expect(result).toHaveLength(0);
    });

    it('does not mutate original array when sorting', () => {
        const original = [...tracks];
        getVisibleTracksPure(tracks, noNav, noFilters, { col: 'title', dir: 'desc' });
        expect(tracks).toEqual(original);
    });
});

// ── virtualScrollRange() ────────────────────────────────────

describe('virtualScrollRange()', () => {
    const rowHeight = 42;
    const buffer = 20;

    it('returns correct range at top of scroll', () => {
        const { startIndex, endIndex } = virtualScrollRange(1000, 0, 500, rowHeight, buffer);
        expect(startIndex).toBe(0);
        expect(endIndex).toBe(Math.min(1000, Math.ceil(500 / rowHeight) + buffer));
    });

    it('startIndex is always >= 0', () => {
        const { startIndex } = virtualScrollRange(1000, 0, 500, rowHeight, buffer);
        expect(startIndex).toBeGreaterThanOrEqual(0);
    });

    it('endIndex never exceeds totalItems', () => {
        const { endIndex } = virtualScrollRange(50, 10000, 500, rowHeight, buffer);
        expect(endIndex).toBeLessThanOrEqual(50);
    });

    it('returns correct range when scrolled to middle', () => {
        const scrollTop = 5000;
        const viewportHeight = 500;
        const { startIndex, endIndex } = virtualScrollRange(1000, scrollTop, viewportHeight, rowHeight, buffer);
        const expectedStart = Math.max(0, Math.floor(scrollTop / rowHeight) - buffer);
        const expectedEnd = Math.min(1000, Math.ceil((scrollTop + viewportHeight) / rowHeight) + buffer);
        expect(startIndex).toBe(expectedStart);
        expect(endIndex).toBe(expectedEnd);
    });

    it('handles zero total items', () => {
        const { startIndex, endIndex } = virtualScrollRange(0, 0, 500, rowHeight, buffer);
        expect(startIndex).toBe(0);
        expect(endIndex).toBe(0);
    });

    it('renders buffer rows above and below viewport', () => {
        const scrollTop = 2100; // row 50
        const viewportHeight = 420; // ~10 rows
        const { startIndex, endIndex } = virtualScrollRange(1000, scrollTop, viewportHeight, rowHeight, buffer);
        // Without buffer: rows 50-60. With buffer of 20: rows 30-80
        expect(startIndex).toBe(30);
        expect(endIndex).toBe(80);
    });
});
