/**
 * Persistent accepted logical server time, separate payroll cursor, and production callback return evidence.
 * No wall-clock/offline catchup. Per-building advance and finish checkpoints govern recovery of accepted work;
 * callback return alone is not a completion receipt. New Tick allocation requires the prior Payroll/production facts.
 */
package com.bbmurloc.victoriaeconomics.server.time;
