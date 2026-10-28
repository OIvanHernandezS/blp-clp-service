---
name: enterprise-error-handling-logging
description: >
  Enforce a production-ready error handling and logging strategy focused on
  descriptive custom exceptions, maintainability, observability, and cloud cost
  optimization. Avoid unnecessary logging while providing actionable errors for
  frontend consumers.

globs:
  - "**/*.go"
  - "**/*.java"
  - "**/*.kt"
  - "**/*.ts"

alwaysApply: true
---

# Enterprise Error Handling & Logging

## Objective

Implement a lightweight, enterprise-grade error handling strategy that produces descriptive, standardized errors and an efficient logging strategy optimized for cloud environments.

Do not introduce middleware, filters, interceptors, or unnecessary infrastructure solely for error handling.

---

# Error Handling

## Custom Exceptions

Always use domain-specific custom exceptions.

Never throw generic exceptions for business logic.

Each exception must expose:

- errorCode
- category
- status
- message
- detail
- retryable
- cause (internal only)

## Message Rules

Every exception must provide two levels of information.

### message

A concise explanation intended for API consumers.

Explains:

- what failed

Example:

> Product could not be retrieved.

### detail

Technical explanation intended for developers.

Explain:

- why it failed
- probable root cause
- where it failed
- recommended action when applicable

Example:

> ProductService failed because productId=12345 does not exist in the catalog.

Never generate generic messages such as:

- Internal Server Error
- Unexpected Error
- Unknown Error
- Something went wrong

Errors must always be actionable.

---

# Exception Categories

Prefer a centralized hierarchy including:

- Validation
- Business
- ResourceNotFound
- ExternalService
- Timeout
- Configuration
- Database
- Infrastructure
- Unexpected

Each category should define default metadata to reduce duplicated code.

---

# Error Responses

Expose standardized responses.

Never expose:

- stack traces
- implementation details
- framework exceptions

Responses should remain stable and frontend-friendly.

---

# Logging Strategy

Optimize logging for production cloud environments.

The goal is to maximize observability while minimizing ingestion, storage, and compute costs.

## Runtime Configuration

Logging levels must be configurable without restarting or redeploying the application.

Supported levels:

- OFF
- ERROR
- WARN
- INFO
- DEBUG
- TRACE

DEBUG and TRACE should only be enabled during production incidents.

---

## Structured Logging

Always use structured, parameterized logging.

Never concatenate log messages.

Never serialize entire objects unless debugging requires it.

Never log:

- passwords
- tokens
- credentials
- personal information
- large payloads

---

## Log Only Valuable Events

Recommended:

- application startup
- application shutdown
- configuration failures
- business failures
- external service failures
- database failures
- unexpected exceptions
- important state transitions

Avoid logging:

- every method invocation
- getters/setters
- loops
- high-frequency executions
- duplicate failures

A failure should be logged exactly once by the highest responsible layer.

---

## Log Context

Include whenever available:

- timestamp
- service
- operation
- class
- method
- level
- elapsedTime
- errorCode

Logs must clearly explain what happened without requiring source code inspection.

---

# Performance

The implementation must:

- minimize allocations
- avoid reflection when possible
- avoid unnecessary object creation
- avoid expensive string formatting
- skip log evaluation when disabled
- keep runtime overhead minimal

Logging must never become a bottleneck.

---

# Documentation

Maintain `/docs/error-handling.md`.

Document:

- exception hierarchy
- error model
- logging strategy
- runtime log configuration
- operational recommendations
- cloud logging cost optimization

Documentation should remain concise and updated with architectural changes.

---

# Code Quality

Generated code must be:

- production-ready
- reusable
- maintainable
- testable
- framework-native
- SOLID-compliant
- easy to extend
- free of duplicated logic

Prioritize simplicity over unnecessary abstractions while preserving enterprise-level maintainability.