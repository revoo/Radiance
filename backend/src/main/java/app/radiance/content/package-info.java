/**
 * Content is data (SPEC P7): quirk, event, arc and constraint defs, the rules files, the roster and the
 * arc blacklist, loaded from {@code content/} and validated as one library at startup (ADR 0015).
 * Defs, loading and validation are plain Java; {@code ContentConfig} only feeds them the classpath.
 */
package app.radiance.content;
