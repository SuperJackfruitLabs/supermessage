/**
 * Fixtures for stories and tests. **Never imported by application code.**
 *
 * Two layers, because they serve different needs. Builders take
 * `Partial<T>` overrides and exist so a test can say only what it cares
 * about. Named scenarios are built on top and are the content of the story
 * catalogue — the states worth looking at.
 *
 * That second layer answers a question the design-language audit raised and
 * could not settle: what states does this UI have? They were uncountable,
 * which is why drift between the three platforms was invisible. A named
 * scenario per state makes the set enumerable.
 */

/**
 * Searched for in the production bundle, so "Vite tree-shakes this" stops
 * being a belief. Nothing here should ever ship; see
 * `scripts/tests/test_fixture_leak.mjs`.
 *
 * Deliberately long and unlikely: a short marker risks colliding with
 * minified output and reporting a leak that is not one.
 */
export const FIXTURE_MARKER = "__supermessage_fixture_marker_do_not_ship__";

export * from "./connection";
export * from "./live";
export * from "./rooms";
export * from "./spaces";
export * from "./timeline";
