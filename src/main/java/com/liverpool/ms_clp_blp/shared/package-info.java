/**
 * Cross-cutting concerns shared by all slices: the error hierarchy and RFC 7807 exception handler
 * ({@code errors}), session identity resolved from gateway headers ({@code session}), and web
 * platform configuration — security, MDC request context ({@code web}).
 *
 * <p>Anything needed by two or more slices lives here (contracts) or in {@code clients} (infra);
 * slices never import from each other. Must never depend on {@code landing} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.shared;
