# Ruffle

Ruffle is a static analysis and contract-governance platform designed to detect cross-service API breaking changes across internal microservices at pull-request time.

## Overview

In microservice architectures, modifying or deprecating an API response field often introduces breaking changes downstream. Traditional consumer-driven contract testing frameworks frequently demand substantial developer overhead and runtime test maintenance.

Ruffle addresses this problem by combining centralized OpenAPI contract tracking with source-code static analysis (AST inspection). Instead of guessing downstream dependencies or relying solely on manual communication, Ruffle provides concrete, file-and-line evidence of exactly which consumer services access a modified or deleted field.

## Architecture

Ruffle is composed of modular subsystems:

1. **Contract Registry (`contract-registry`)**  
   A Spring Boot service backed by PostgreSQL that ingests, version-controls, and normalizes OpenAPI 3.x specifications into relational endpoints and flattened dot-notation schema fields.

2. **Consumer Analyzer (`consumer-analyzer`)**  
   A static code analysis engine powered by JavaParser and JavaSymbolSolver. It analyzes consumer source repositories to discover HTTP client invocations (`WebClient`, `RestTemplate`, Feign clients) and maps downstream field-level references to specific source files and line numbers. Findings are categorized into explicit evidence tiers:
   - Confirmed: Direct typed method invocations or getter calls.
   - Likely: Dynamic map, JSON node, or generic payload access.
   - Unknown: Unresolvable or dynamic runtime types requiring manual inspection.

3. **Schema Diff Engine**  
   A rule-based comparator that detects field deletions, type alterations, and constraint mutations across specification versions to classify changes by severity (`BREAKING`, `WARNING`, `INFO`).

4. **Blast Radius Service & CLI Tooling (`blast-cli`)**  
   Computes relational joins between detected schema changes and indexed consumer field usages, producing partitioned impact reports and formatted GitHub PR status comments.

5. **Visualization Dashboard (`dashboard`)**  
   A Vite and React interface rendering dependency topology graphs and interactive blast-radius inspection views.

## Technology Stack

- Language: Java 21
- Framework: Spring Boot 3.3.x
- Persistence: PostgreSQL, Spring Data JPA, Flyway
- Parsing & Static Analysis: io.swagger.parser.v3, JavaParser 3.26+
- Frontend: Vite, React 18, React Flow
- Infrastructure: Docker Compose, GitHub Actions
