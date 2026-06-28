/**
 * @vitest-environment jsdom
 */
import { describe, it, expect } from 'vitest';
import { normalizeInput } from '../../main/resources/static/js/tags.js';

// normalizeInput(raw): trim, collapse internal whitespace to single spaces, lower-case.

describe('normalizeInput()', () => {
    it('returns empty string for null', () => {
        expect(normalizeInput(null)).toBe('');
    });

    it('returns empty string for undefined', () => {
        expect(normalizeInput(undefined)).toBe('');
    });

    it('returns empty string for empty input', () => {
        expect(normalizeInput('')).toBe('');
    });

    it('trims leading and trailing whitespace', () => {
        expect(normalizeInput('  hello  ')).toBe('hello');
    });

    it('collapses multiple internal spaces into one', () => {
        expect(normalizeInput('hard    rock')).toBe('hard rock');
    });

    it('collapses mixed internal whitespace (tabs/newlines) into one space', () => {
        expect(normalizeInput('hard\t\nrock')).toBe('hard rock');
    });

    it('lower-cases the input', () => {
        expect(normalizeInput('Classic ROCK')).toBe('classic rock');
    });

    it('applies trim, collapse, and case-folding together', () => {
        expect(normalizeInput('  Heavy   METAL  ')).toBe('heavy metal');
    });

    it('returns empty string for whitespace-only input', () => {
        expect(normalizeInput('   ')).toBe('');
    });
});
