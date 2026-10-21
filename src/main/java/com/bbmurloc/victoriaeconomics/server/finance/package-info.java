/**
 * One authoritative cash balance per explicit owner/currency account. Reservations derive available cash;
 * ledger and complete transfer command receipts share the physical transaction. Payroll reuses the explicit session;
 * household allowances also constrain debits and cash locks. No issuance or currency conversion API.
 */
package com.bbmurloc.victoriaeconomics.server.finance;
