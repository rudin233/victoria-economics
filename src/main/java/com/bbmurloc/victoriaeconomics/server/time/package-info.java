/**
 * Persistent accepted logical server time, separate payroll cursor, and production callback return evidence.
 * No wall-clock/offline catchup or historical production callback replay. A returned legacy callback is not a receipt
 * that all Batch, Inventory or boundary operations succeeded; their existing recovery protocols remain authoritative.
 */
package com.bbmurloc.victoriaeconomics.server.time;
