/**
 * @vitest-environment jsdom
 */
import { describe, it, expect } from 'vitest';
import {
    escapeText,
    csrfToken,
    csrfHeaderName
} from '../../main/resources/static/js/shared-smart-playlist.js';

// escapeText() guards a public, unauthenticated page (the shared smart-playlist
// viewer), so its escaping behaviour is security-relevant — cover it directly.

describe('escapeText()', () => {
    it('escapes angle brackets so markup is rendered as text', () => {
        expect(escapeText('<script>alert(1)</script>'))
            .toBe('&lt;script&gt;alert(1)&lt;/script&gt;');
    });

    it('escapes ampersands', () => {
        expect(escapeText('AC/DC & Friends')).toBe('AC/DC &amp; Friends');
    });

    it('escapes a lone ampersand without double-encoding', () => {
        expect(escapeText('&')).toBe('&amp;');
    });

    it('returns empty string for null', () => {
        expect(escapeText(null)).toBe('');
    });

    it('returns empty string for undefined', () => {
        expect(escapeText(undefined)).toBe('');
    });

    it('coerces numbers to their string form', () => {
        expect(escapeText(42)).toBe('42');
        expect(escapeText(0)).toBe('0');
    });

    it('leaves plain text untouched', () => {
        expect(escapeText('Just a playlist')).toBe('Just a playlist');
    });
});

// csrfToken()/csrfHeaderName() read meta tags that are absent in this jsdom
// document, so they exercise the fallback branches.

describe('csrfToken() / csrfHeaderName() fallbacks', () => {
    it('csrfToken() returns empty string when the meta tag is missing', () => {
        expect(csrfToken()).toBe('');
    });

    it('csrfHeaderName() falls back to X-XSRF-TOKEN when the meta tag is missing', () => {
        expect(csrfHeaderName()).toBe('X-XSRF-TOKEN');
    });
});
