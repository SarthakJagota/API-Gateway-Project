# API Gateway — Self-Service Rate-Limiting Layer for Web APIs

A lightweight, self-service API Gateway that sits between clients and an existing API to provide **rate limiting, request protection, authentication, logging, and analytics** without requiring changes to the target API.

## Website
https://api-gateway-yp6s.onrender.com/

## 🚀 Overview

Uncontrolled API traffic can lead to:

* Resource exhaustion
* Unexpected infrastructure and API costs
* Unfair resource usage between clients
* Service degradation caused by excessive requests

This project addresses these problems by providing a deployable **reverse proxy with distributed rate limiting**. Developers can register their APIs, configure request limits, and expose them through a protected gateway endpoint.

The gateway forwards allowed requests to the target API and automatically returns **HTTP 429 — Too Many Requests** when a client's request budget is exhausted.

## ✨ Features

* 🔐 **User Authentication**

  * User signup and login
  * JWT-based authentication

* 🚦 **Rate Limiting**

  * Token Bucket algorithm
  * Sliding Window algorithm
  * Configurable request limits
  * HTTP 429 responses for rejected requests
  * `Retry-After` response support

* 🌐 **Reverse Proxy**

  * Forward requests to registered target APIs
  * Relay origin responses back to clients
  * No modifications required to the target API

* 📊 **Analytics Dashboard**

  * Allowed vs. blocked requests
  * Traffic over time
  * Per-API traffic breakdown
  * Request logs with filtering and pagination

* 📝 **Request Logging**

  * Request timestamp
  * Client identity
  * Request outcome
  * Persistent storage in PostgreSQL

* ⚡ **Distributed Rate Limiting**

  * Redis-backed counters
  * Supports multiple gateway instances
  * Atomic counter operations for concurrency correctness

* 🔄 **CI/CD**

  * Automated builds and tests
  * Integration testing
  * Staging deployment pipeline

## 🏗️ Architecture

```text
                    ┌─────────────────┐
                    │     Client      │
                    └────────┬────────┘
                             │
                             ▼
              ┌──────────────────────────┐
              │       API Gateway        │
              │   Spring Cloud Gateway   │
              └────────────┬─────────────┘
                           │
                    Consume / Reject
                           │
                           ▼
              ┌──────────────────────────┐
              │     Bucket4j + Redis     │
              │   Distributed Limiter    │
              └────────────┬─────────────┘
                           │
                    Request Allowed
                           │
                           ▼
              ┌──────────────────────────┐
              │       Target API         │
              └────────────┬─────────────┘
                           │
                           ▼
                     Response
                           │
                           ▼
              ┌──────────────────────────┐
              │      Client Response     │
              └──────────────────────────┘

                     Every Request
                           │
                           ▼
                 ┌──────────────────┐
                 │    PostgreSQL    │
                 │   Request Logs   │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │ React Dashboard  │
                 │    + Recharts    │
                 └──────────────────┘
```

The gateway uses Spring Cloud Gateway for the proxy layer, Redis for distributed counter state, PostgreSQL for persistent data, and React for the analytics interface.

## 🔄 How It Works

1. A developer creates an account and logs in.
2. The developer registers a target API.
3. A gateway endpoint and API key are generated.
4. The developer configures a request limit.
5. A client sends requests through the gateway.
6. The gateway checks the client's rate-limit policy.
7. Redis/Bucket4j performs an atomic consume-or-reject operation.
8. If allowed, the request is forwarded to the target API.
9. If the limit is exceeded, the gateway returns **HTTP 429**.
10. Request outcomes are recorded in PostgreSQL.
11. The dashboard displays aggregated traffic and request statistics.

## 🛠️ Tech Stack

| Component           | Technology            |
| ------------------- | --------------------- |
| Backend             | Spring Boot           |
| API Gateway         | Spring Cloud Gateway  |
| Rate Limiting       | Bucket4j              |
| Distributed Counter | Redis                 |
| Database            | PostgreSQL            |
| ORM                 | Spring Data JPA       |
| Authentication      | Spring Security + JWT |
| Frontend            | React                 |
| Visualization       | Recharts              |
| API Testing         | Postman               |
| Unit Testing        | JUnit                 |
| Integration Testing | Testcontainers        |
| CI/CD               | GitHub Actions        |
| Backend Deployment  | Render / Railway      |
| Frontend Deployment | Vercel                |

## 🚦 Rate-Limiting Algorithms

### Token Bucket

The token bucket maintains a bucket with a fixed capacity and refill rate.

```text
Bucket Capacity = 10 requests
Refill Rate     = 5 requests/sec

Client
  │
  ├── Request → Token available → ✅ Forward
  ├── Request → Token available → ✅ Forward
  └── Request → No token         → ❌ HTTP 429
```

This allows controlled bursts while maintaining a bounded long-term request rate.

### Sliding Window

The sliding-window algorithm counts requests within a moving time interval and rejects requests once the configured threshold is reached.

The project supports both approaches so their behavior can be evaluated under identical workloads.

## 📁 Project Structure

A recommended structure for the implementation is:

```text
api-gateway/
│
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   └── java/
│   │   └── test/
│   ├── pom.xml
│   └── Dockerfile
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── services/
│   │   └── charts/
│   ├── package.json
│   └── Dockerfile
│
├── docker-compose.yml
├── README.md
└── .gitignore
```

> The exact folder structure may change as implementation progresses.

## ⚙️ Configuration

The application requires configuration for:

```env
# Database
DATABASE_URL=
DATABASE_USERNAME=
DATABASE_PASSWORD=

# Redis
REDIS_HOST=
REDIS_PORT=
REDIS_PASSWORD=

# JWT
JWT_SECRET=

# Gateway
GATEWAY_BASE_URL=
```

**Never commit secrets or credentials to the repository.**

Use environment variables or platform-managed secret configuration instead.

## 🧪 Testing

The project emphasizes correctness under concurrent load.

Testing includes:

* Unit tests
* Integration tests
* Redis-backed rate-limit tests
* Concurrent request tests
* Multi-instance gateway tests
* API testing using Postman
* Automated CI testing

The primary evaluation metric is **Enforcement Accuracy under Concurrent Load**.

### Target

For a configured limit of `N` requests:

```text
Expected admitted requests ≈ N ± 2%
```

The test is performed with multiple gateway instances and significantly more concurrent requests than the configured limit.

## 📈 Performance Targets

| Metric                          |  Target |
| ------------------------------- | ------: |
| Rate-limit enforcement accuracy |     ±2% |
| p95 added latency               | ≤ 50 ms |
| Request log completeness        | ≥ 99.9% |
| Staging availability            |   ≥ 99% |

## 🔒 Security Considerations

The gateway is designed to prevent misuse of the proxy:

* JWT authentication for users
* Registered APIs only
* Target URL validation
* Credentials stored securely
* Secrets excluded from source control
* No arbitrary proxying to unregistered hosts
* Explicit Redis failure policy

These measures reduce the risk of the gateway being abused as an open proxy.

## 📦 Deployment

The proposed deployment architecture uses managed infrastructure:

```text
                    GitHub
                      │
                      ▼
               GitHub Actions
                      │
              ┌───────┴───────┐
              ▼               ▼
          Backend          Frontend
        Render/Railway       Vercel
              │
       ┌──────┴──────┐
       ▼             ▼
     Redis       PostgreSQL
```

The backend can be containerized and horizontally replicated, while Redis stores shared rate-limiting state and PostgreSQL stores persistent application data.

## 🗺️ Roadmap

### Phase 1 — Core Gateway

* [x] Project architecture
* [ ] Reverse proxy
* [ ] Token Bucket rate limiting
* [ ] Redis integration
* [ ] HTTP 429 handling
* [ ] JWT authentication
* [ ] API registration
* [ ] PostgreSQL request logging

### Phase 2 — Analytics

* [ ] React dashboard
* [ ] Allowed vs blocked requests
* [ ] Traffic graphs
* [ ] Per-API statistics
* [ ] Request log filtering
* [ ] Pagination

### Phase 3 — Distributed Validation

* [ ] Multiple gateway instances
* [ ] Concurrent load testing
* [ ] Enforcement accuracy measurement
* [ ] Performance benchmarking

### Phase 4 — Advanced Features

* [ ] Sliding Window algorithm
* [ ] Algorithm comparison
* [ ] Request replay
* [ ] Advanced analytics

The proposal identifies request replay and live algorithm comparison as stretch features, to be implemented only after the core enforcement metrics are achieved.

## 📊 Evaluation

The project will evaluate:

1. **Rate-limit enforcement accuracy**
2. **Added latency**
3. **Sustained throughput**
4. **Request-log completeness**
5. **Dashboard freshness**
6. **Gateway reliability**
7. **Developer usability**

A pilot will use representative target APIs with different latency profiles and controlled concurrent workloads across one and two gateway instances.

## 👥 Team

**UCS503P Project**

* **Sarthak Jagota** — 1024030753
* **Palak Mahajan** — 1024030755
* **Aayush Kumar Singh** — 1024030758

**Thapar Institute of Engineering and Technology**

## 📄 Project Proposal

This implementation is based on the project proposal **“API Gateway: A Self-Service Rate-Limiting Layer for Web APIs”**, submitted for UCS503P on August 8, 2026.

## 🎯 Project Goal

> Build a lightweight, deployable API protection layer that allows developers to enforce fair request budgets in front of an existing API without modifying the API itself.

The primary engineering focus is **correct distributed rate limiting, measurable performance, reliability, and scalability**, rather than developing a new rate-limiting algorithm.
