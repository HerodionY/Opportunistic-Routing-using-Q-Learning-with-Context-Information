# Liberi Backend — Code Audit Report

**Date:** March 5, 2026  
**Scope:** Full codebase review of `liberi-backend` — a Spring Boot 3.4.5 / Java 17 multi-module Maven project (backend-api, backend-business, backend-data)

---

## 🔴 Critical Issues

### 1. No Spring Security — All Dashboard Endpoints Are Public

There is **no `spring-boot-starter-security` dependency** anywhere in the project. Every endpoint under `/v1/dashboard/**` (queue management, employee CRUD, reservations, schedules, reports, roles, privileges) is **publicly accessible to anyone on the network** without authentication.

The JWT logic in [AuthController.java](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/controller/dashboard/AuthController.java) issues tokens, but nothing enforces their presence on subsequent requests. There is no `SecurityFilterChain`, no `OncePerRequestFilter`, no middleware verifying JWTs on protected routes.

> [!CAUTION]
> An attacker can call any dashboard API (create/delete employees, modify queues, change schedules) with zero authentication. This is the highest-priority fix.

**Recommendation:** Add `spring-boot-starter-security`, implement a `JwtAuthenticationFilter`, and define a `SecurityFilterChain` that protects `/v1/dashboard/**` and `/v1/kiosk/**` routes.

---

### 2. JWT Tokens Have No Expiration

In [AuthController.java:161-165](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/controller/dashboard/AuthController.java#L161-L165), the JWT is built **without `.setExpiration()`**. Once issued, a token is valid forever.

```java
String accessToken = Jwts.builder()
    .setHeaderParam("typ", "JWT")
    .claim("id_pegawai", employee.getEmployeeIdentityNumber())
    .signWith(key)
    .compact(); // ← no .setExpiration()
```

**Recommendation:** Add `.setExpiration(Date.from(Instant.now().plus(Duration.ofHours(8))))` (or similar TTL) and implement refresh tokens.

---

### 3. Wide-Open CORS Policy

In [WebConfig.java:61-63](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/config/WebConfig.java#L61-L63):

```java
registry.addMapping("/**")
    .allowedOrigins("*")
    .allowedMethods("*");
```

The commented-out code shows a proper per-path CORS config existed before and was replaced with a wildcard. This allows any website on the internet to make authenticated requests to the API cross-origin.

**Recommendation:** Restore the per-path CORS policy. At minimum, restrict `allowedOrigins` to the actual frontend domain(s) and specific HTTP methods.

---

### 4. Actuator Endpoints Publicly Exposed

In [application.properties:12](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/resources/application.properties#L12):

```properties
management.endpoints.web.exposure.include=prometheus,health,info,metrics
```

Without Spring Security, these endpoints (including full application metrics) are accessible to anyone.

**Recommendation:** Once Spring Security is added, restrict actuator endpoints via a `SecurityFilterChain` that requires admin roles, or expose them on a separate non-public port.

---

## 🟠 High-Severity Issues

### 5. In-Memory Token Store (Not Scalable)

[TokenService.java](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/service/callback/TokenService.java) stores callback tokens in a `ConcurrentHashMap` in memory. If the application runs multiple instances (horizontal scaling), tokens created on one node are invalid on another. A restart also wipes all active tokens.

**Recommendation:** Use a shared store (Redis, database) for callback tokens, or switch to self-contained JWTs for the callback API.

---

### 6. Inconsistent HTTP Status Codes for Errors

In [GlobalExceptionHandler.java](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/handler/GlobalExceptionHandler.java), all exceptions including [LiberiException](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/exception/LiberiException.java#3-8) and [MobileJKNException](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/handler/GlobalExceptionHandler.java#18-26) return `HttpStatus.CREATED` (201). HTTP 201 means "resource successfully created" — this is deeply misleading for error responses.

| Exception | HTTP Status | Should Be |
|---|---|---|
| [MobileJKNException](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/handler/GlobalExceptionHandler.java#18-26) | 201 CREATED | 400 or 401 |
| [CarolusException](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/handler/GlobalExceptionHandler.java#35-42) | 201 CREATED | 400 or 401 |
| [LiberiException](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/exception/LiberiException.java#3-8) | 201 CREATED | 400 or 422 |
| [WhatsAppException](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/handler/GlobalExceptionHandler.java#51-58) | 201 CREATED | 400 or 502 |
| `NewPatientException` | 202 ACCEPTED | Needs review |

There is also **no generic [Exception](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/exception/LiberiException.java#3-8) handler** — unhandled exceptions will return Spring Boot's default 500 error with a stack trace, potentially leaking internal details.

**Recommendation:** Use proper HTTP status codes. Add a fallback `@ExceptionHandler(Exception.class)` that returns 500 with a generic message.

---

### 7. Error Logging Commented Out

Every handler in [GlobalExceptionHandler](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/handler/GlobalExceptionHandler.java#14-59) has _commented-out_ logging:

```java
// log.error("Runtime exception occurred: {}", ex.getMessage(), ex);
```

Errors are silently swallowed. In production, you will have no visibility into failures.

**Recommendation:** Uncomment the logging or add structured logging with correlation IDs.

---

### 8. Production & Staging Profiles Are Incomplete

| Profile | Lines | Config Present |
|---|---|---|
| [application-dev.properties](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/resources/application-dev.properties) | 85 | DB, BPJS, JWT, WhatsApp, Pharmacy, etc. |
| [application-stg.properties](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/resources/application-stg.properties) | 7 | Only DB connection |
| [application-prd.properties](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/resources/application-prd.properties) | 9 | Only DB connection |

Staging and production profiles are missing all third-party service configs, JWT secret, WhatsApp config, etc. This means they either fail to start or fall back to `dev` defaults — a serious deployment risk.

**Recommendation:** Ensure all required properties are defined in each profile (they can reference env vars).

---

### 9. Property File Typo — `&{...}` Instead of `${...}`

In [application-dev.properties:73](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/resources/application-dev.properties#L73):

```properties
3dolphin.whatsapp.web=&{WHATSAPP_PAYLOAD_TO_WEB}
```

The `&` should be `$`. Spring won't resolve this — the property value will be the literal string `&{WHATSAPP_PAYLOAD_TO_WEB}`.

---

## 🟡 Moderate Issues

### 10. God-Class Services

Several service classes are excessively large:

| File | Lines |
|---|---|
| [TemporaryScheduleService.java](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/service/dashboard/TemporaryScheduleService.java) | **5,300+** |
| [QueueService.java](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/service/dashboard/QueueService.java) | **2,000+** |
| [KioskService.java](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/service/kiosk/KioskService.java) | **1,200+** |
| [CarolusCallbackService.java](file:///d:/liberiBackend/liberi-backend/backend-business/src/main/java/com/liberi/business/service/callback/CarolusCallbackService.java) | **1,137** |

These classes violate single-responsibility, are hard to test, and are error-prone. `TemporaryScheduleService` alone has transactional methods spanning thousands of lines.

**Recommendation:** Extract logic into smaller, focused services (e.g., `BPJSCheckInService`, `MedinfrasRegistrationService`, `QueuePositionService`).

---

### 11. Business Logic in Controller (AuthController)

[AuthController.java](file:///d:/liberiBackend/liberi-backend/backend-api/src/main/java/com/liberi/api/controller/dashboard/AuthController.java) directly accesses repositories (`EmployeeRepository`, `EmployeeAuthRepository`), constructs JPA `Specification` objects, and runs business logic for login and token validation. This bypasses the service layer.

**Recommendation:** Extract login/token logic into an `AuthService` in `backend-business`.

---

### 12. Duplicate [.env](file:///d:/liberiBackend/liberi-backend/backend-api/.env) Lines

In [.env](file:///d:/liberiBackend/liberi-backend/backend-api/.env), these lines are duplicated:
- Lines 53-54 and 56-57: `WHATSAPP_URL` and `WHATSAPP_AUTHORIZATION`
- Lines 68 and 70: `WHATSAPP_CHANNEL`

---

### 13. `javax.servlet-api` Dependency in Spring Boot 3

In the parent [pom.xml:96-100](file:///d:/liberiBackend/liberi-backend/pom.xml#L96-L100):

```xml
<dependency>
    <groupId>javax.servlet</groupId>
    <artifactId>javax.servlet-api</artifactId>
    <version>4.0.1</version>
</dependency>
```

Spring Boot 3 uses **Jakarta EE** (`jakarta.servlet`). This `javax.servlet` dependency is either dead code or could cause classloader conflicts.

**Recommendation:** Remove this dependency. The codebase already correctly uses `jakarta.servlet` imports.

---

### 14. Inconsistent Spring Framework Version Overrides

The parent POM overrides individual Spring Framework modules with **different patch versions**:

```xml
<spring-context> 6.2.7 </spring-context>
<spring-web>     6.2.8 </spring-web>
<spring-webmvc>  6.2.10 </spring-webmvc>
```

Mixing framework module versions can cause subtle incompatibilities. Spring Boot's BOM already aligns all modules.

**Recommendation:** Remove these overrides and let Spring Boot's BOM manage framework versions. If you need a security fix, upgrade `spring.boot.version` itself.

---

### 15. `hs_err_pid*.log` and `replay_pid*.log` Files in Repository

Multiple JVM crash logs are present in the project directories. These should be gitignored and cleaned up.

---

### 16. No Unit or Integration Tests

There are `spring-boot-starter-test` dependencies in all modules but **zero test source files** were found. The `src/test/` directories exist but contain no tests. This means there is zero automated verification of any business logic.

---

### 17. Random PID for Logging Instead of MDC/Correlation ID

Every controller method generates a random `long pid = (long) (Math.random() * Long.MAX_VALUE)` for log tracing. This is not a proper tracing mechanism — it's not available to services/repositories called downstream, and it won't appear in logs from those layers.

**Recommendation:** Use SLF4J's MDC with a proper correlation ID set via a servlet filter.

---

### 18. `Hibernate.flushMode=MANUAL` Globally

```properties
spring.jpa.properties.org.hibernate.flushMode=MANUAL
```

This means Hibernate will **never automatically flush** changes before queries. If any code path forgets to manually flush, data will silently not persist. This is risky for a queue management system where data consistency is critical.

---

## 📋 Summary — Priority Matrix

| Priority | Issue | Risk |
|---|---|---|
| 🔴 P0 | No Spring Security → endpoints public | Data breach / unauthorized access |
| 🔴 P0 | JWT tokens never expire | Token theft → permanent access |
| 🔴 P0 | Wide-open CORS | Cross-site attacks |
| 🔴 P1 | Actuator publicly exposed | Info disclosure |
| 🟠 P1 | Error logging disabled | Zero production visibility |
| 🟠 P1 | Incomplete prod/stg profiles | Deployment failures |
| 🟠 P2 | In-memory token store | Scaling limitation |
| 🟠 P2 | Wrong HTTP status on errors | Client confusion, debugging difficulty |
| 🟡 P2 | God-class services (5K+ LOC) | Maintainability / bug risk |
| 🟡 P3 | No tests | Regression risk |
| 🟡 P3 | Auth logic in controller | Architecture violation |
| 🟡 P3 | Manual Hibernate flush mode | Silent data loss risk |
