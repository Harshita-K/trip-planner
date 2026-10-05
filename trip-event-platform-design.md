# Wanderly — Trip Planning & Event Booking Platform
### Complete Architecture & Implementation Design Document

> A resume-grade, Java-backed, event-driven platform for trip planning and event booking, with an ML-powered recommendation and itinerary engine, built on Kafka and AWS.

*(“Wanderly” is a placeholder name — rename freely.)*

---

## Table of Contents

1. [Project Vision](#1-project-vision)
2. [Core Features](#2-core-features)
3. [System Architecture (High-Level)](#3-system-architecture-high-level)
4. [Technology Stack](#4-technology-stack)
5. [Microservices Breakdown](#5-microservices-breakdown)
6. [Kafka — Event-Driven Design](#6-kafka--event-driven-design)
7. [AWS Services Mapping](#7-aws-services-mapping)
8. [External Data Sources (Free vs. Mocked)](#8-external-data-sources-free-vs-mocked)
9. [Data Model / Database Schema](#9-data-model--database-schema)
10. [Feature Implementation Details](#10-feature-implementation-details)
11. [The Itinerary Generation Algorithm](#11-the-itinerary-generation-algorithm)
12. [The ML Recommendation Component](#12-the-ml-recommendation-component)
13. [Caching Strategy](#13-caching-strategy)
14. [Security](#14-security)
15. [Testing Strategy](#15-testing-strategy)
16. [CI/CD & Infrastructure as Code](#16-cicd--infrastructure-as-code)
17. [Suggested Repository Structure](#17-suggested-repository-structure)
18. [Phased Roadmap](#18-phased-roadmap)
19. [Prerequisites — What to Learn Before Starting](#19-prerequisites--what-to-learn-before-starting)
20. [Cost Considerations](#20-cost-considerations)
21. [Risks & Scope Warnings](#21-risks--scope-warnings)
22. [Resume Bullets & Interview Talking Points](#22-resume-bullets--interview-talking-points)

---

## 1. Project Vision

Wanderly is a platform that helps users **book events and plan trips intelligently**. Rather than being a simple CRUD booking app, it acts as a **smart aggregator + planning engine**:

- When a user plans a trip, it suggests nearby attractions and generates a **day-by-day itinerary** that respects opening hours and travel time.
- When a user looks at a specific location, it suggests **more tourist spots nearby**, personalized to their taste.
- When a user books an event, it recommends **nearby food and attractions**.
- When a user searches travel, it aggregates **all commute modes with prices, timings, nearby spots, and hotel suggestions**.

**The engineering story it tells:** distributed systems, event-driven architecture (Kafka), microservices, external-API integration with caching, a real algorithmic problem (itinerary optimization), a small ML recommendation system, and cloud-native deployment on AWS. This breadth — anchored in one coherent product — is what makes it a strong portfolio and interview project.

### Design Philosophy (important)
- **Synchronous where the user waits** (search, view, book, pay) → plain REST + relational DB.
- **Asynchronous where they don’t** (notifications, analytics, recommendation updates, reminders) → Kafka.
- **Kafka is used only where it earns its place** — deliberately, not everywhere. Knowing *when not* to use it is a maturity signal.
- **ML is a focused personalization/ranking layer**, not the whole system.
- **Finish a working spine before adding breadth.** Three services that genuinely work over Kafka beat ten half-built ones.

---

## 2. Core Features

| # | Feature | Type | Powered By |
|---|---------|------|------------|
| F1 | User signup / login / profile & preferences | Sync CRUD | Cognito + RDS |
| F2 | Search & browse events and destinations | Sync CRUD | RDS + Redis cache |
| F3 | Book an event / trip | Sync + async fan-out | RDS + Kafka |
| F4 | Payment (sandbox) | Sync + async | Stripe test mode + Kafka |
| F5 | Suggest nearby tourist spots for a location | Aggregation + ML re-rank | Places API + Recommender |
| F6 | Generate a planned day-by-day itinerary | **Own algorithm** | Places + Maps API + optimizer |
| F7 | Suggest food/attractions near a booked event | Aggregation + ML re-rank | Places API + Recommender |
| F8 | Travel: all commute modes + prices + timings | Aggregation | Flight/train/bus APIs (or mock) |
| F9 | Hotel suggestions | Aggregation | Amadeus Hotel API (or mock) |
| F10 | Personalized recommendations on home/feed | ML (batch + online) | Activity data → recommender |
| F11 | Notifications & trip reminders | Async | Kafka → notification consumer |
| F12 | Analytics dashboard (trending, funnels) | Async | Kafka → S3 → Athena/QuickSight |

---

## 3. System Architecture (High-Level)

```
                              ┌─────────────────────────┐
                              │   Client (React / Web)  │
                              └────────────┬────────────┘
                                           │ HTTPS
                              ┌────────────▼────────────┐
                              │  API Gateway + ALB      │
                              │  (auth via Cognito)     │
                              └────────────┬────────────┘
                                           │
        ┌──────────────┬───────────────────┼───────────────────┬───────────────┐
        ▼              ▼                    ▼                   ▼               ▼
 ┌────────────┐ ┌────────────┐     ┌───────────────┐   ┌──────────────┐ ┌─────────────┐
 │  user-svc  │ │ booking-svc│     │  places-svc   │   │itinerary-svc │ │  recommend  │
 │ (Spring)   │ │ (Spring)   │     │ (Spring)      │   │ (Spring)     │ │  -svc       │
 └─────┬──────┘ └─────┬──────┘     └──────┬────────┘   └──────┬───────┘ └──────┬──────┘
       │              │                   │                   │                │
       ▼              ▼                   ▼                   │                ▼
   ┌───────┐     ┌───────┐        ┌──────────────┐            │          ┌───────────┐
   │  RDS  │     │  RDS  │        │  Redis cache │◄───────────┘          │  Redis    │
   └───────┘     └───┬───┘        │ + External   │                       │(precomputed│
                     │            │  APIs        │                       │  recs)     │
                     │            └──────────────┘                       └───────────┘
                     │ publish events
                     ▼
        ┌──────────────────────────────────────────┐
        │        Apache Kafka  (Amazon MSK)         │
        │  topics: user-activity, bookings,         │
        │          payments, notifications          │
        └───┬───────────────┬──────────────┬────────┘
            │               │              │
            ▼               ▼              ▼
   ┌────────────────┐ ┌────────────┐ ┌──────────────┐
   │ notification-  │ │  S3 sink   │ │ recommend-svc│
   │  consumer      │ │  consumer  │ │  consumer    │
   │ (email/SMS)    │ │            │ │              │
   └────────────────┘ └─────┬──────┘ └──────────────┘
                            ▼
                     ┌─────────────┐        ┌──────────────────┐
                     │  S3 Data    │───────▶│  Python ML job    │
                     │  Lake (raw/ │        │ (batch retrain)   │
                     │  curated)   │        │ writes recs→Redis │
                     └──────┬──────┘        └──────────────────┘
                            ▼
                     ┌─────────────┐
                     │ Athena /    │
                     │ QuickSight  │
                     └─────────────┘
```

**Mental model in one sentence:** User acts → synchronous services serve them from RDS/Redis → every action publishes to Kafka → consumers handle notifications, analytics, and ML updates asynchronously → activity lands in S3 → a batch ML job refreshes recommendations into Redis for the next visit.

---

## 4. Technology Stack

| Layer | Technology |
|-------|-----------|
| Backend | **Java 17+, Spring Boot 3.x** (Spring Web, Spring Data JPA, Spring Kafka, Spring Security) |
| Event streaming | **Apache Kafka** (local Docker for dev) → **Amazon MSK** (prod) |
| Relational DB | **PostgreSQL** on **Amazon RDS** |
| Cache | **Redis** on **Amazon ElastiCache** |
| Data lake | **Amazon S3** (raw + curated zones, Parquet) |
| Query/analytics | **Amazon Athena** + **AWS Glue Data Catalog**, dashboards in **QuickSight** or React |
| ML | **Python** (scikit-learn / `implicit` / `surprise`), optionally **SageMaker** for hosting/training |
| Auth | **Amazon Cognito** |
| Compute | **Docker** → **ECS Fargate** (simpler) or **EKS** (Kubernetes on resume) |
| Edge | **API Gateway** + **Application Load Balancer** |
| Secrets | **AWS Secrets Manager** |
| Observability | **Amazon CloudWatch** (logs, metrics, alarms) |
| IaC | **Terraform** (or AWS CDK) |
| CI/CD | **GitHub Actions** (or AWS CodePipeline) |
| Local AWS testing | **LocalStack** |
| Frontend (optional) | **React** (Vite) |
| External APIs | Google Places / OpenTripMap, Google Maps / OpenRouteService, Amadeus (flights+hotels) |

---

## 5. Microservices Breakdown

Start as a **modular monolith** if microservices feel heavy, then split. Target services:

### `user-service`
Signup, login (delegates to Cognito), profile, and **user preferences** (interests, budget level, travel pace). Preferences feed personalization. Publishes `user.registered`, `user.preferences.updated`.

### `booking-service`
Search/list events & trips, create bookings, manage booking status. On confirmed booking, **publishes a `bookings` event** that fans out. Owns the `bookings` and `events`/`trips` tables. This is the synchronous core.

### `places-service`
Wraps external Places/Maps APIs. Given a location + category, returns nearby attractions/restaurants/hotels with ratings, timings, coordinates. **Heavily cached in Redis** (external APIs are slow, rate-limited, and cost money per call). Exposes clean internal endpoints so other services don’t each call Google directly.

### `itinerary-service`
The crown jewel. Takes a destination, date range, chosen/eligible spots (from `places-service`), and user preferences, and produces a **day-by-day ordered plan** respecting opening hours and travel time. Contains the optimization algorithm (Section 11). Can persist saved itineraries to RDS.

### `recommendation-service`
Serves personalized recommendations (fast Redis lookups of precomputed results) and re-ranks candidate lists from `places-service` for the current user. Also runs a Kafka consumer that updates lightweight “recently viewed / session” signals.

### `notification-service` (Kafka consumer)
Consumes `bookings`, `payments`, and scheduled reminder triggers; sends email/SMS/push (use Amazon SES/SNS or a mock in dev).

### `analytics/s3-sink` (Kafka consumer)
Consumes `user-activity` and writes to the S3 data lake (raw), then a job curates into Parquet.

> **Polyglot note:** the ML training job is **Python**, separate from the Java services. It reads from S3 and writes recommendations to Redis/DynamoDB that the Java `recommendation-service` reads. Call out this Java + Python boundary — it’s realistic.

---

## 6. Kafka — Event-Driven Design

### Why Kafka here (the justification to give)
A confirmed booking must trigger **many** independent actions: send confirmation, update availability, process receipt, update the recommender, write analytics. Doing that synchronously would make the booking endpoint slow and fragile (fails if any downstream is down). With Kafka, the booking endpoint **publishes one event and returns instantly**; consumers react on their own time, scale independently, and new consumers can be added without touching the producer.

### Topics

| Topic | Key (partition by) | Producers | Consumers | Notes |
|-------|--------------------|-----------|-----------|-------|
| `user-activity` | `userId` | all services | s3-sink, recommend-svc | High volume; keeps a user’s events ordered for session reconstruction |
| `bookings` | `bookingId` | booking-svc | notification, inventory, analytics, recommend | The main fan-out |
| `payments` | `bookingId` | payment flow | booking-svc, notification | Async status updates + edge cases |
| `notifications` | `userId` | multiple | notification-svc | Turns events into messages |
| `dead-letter` | — | consumers | monitoring | Events that fail processing |

### Concepts you get to demonstrate
- **Partitioning** (order guarantees per key, avoiding hot partitions).
- **Consumer groups** (independent scaling of consumers).
- **Decoupling** (add a consumer without changing producers).
- **Idempotency** (a consumer must handle the same event twice without double-effect — e.g. dedupe by `eventId`).
- **Dead-letter topic** for poison messages.
- **Consumer lag** monitoring (a key operational metric).

### Example event payload (`bookings`)
```json
{
  "eventId": "uuid",
  "eventType": "booking.confirmed",
  "occurredAt": "2026-09-27T10:15:30Z",
  "bookingId": "bk_123",
  "userId": "user_789",
  "itemType": "event | trip",
  "itemId": "evt_456",
  "amount": 1499.00,
  "currency": "INR",
  "location": { "lat": 12.9716, "lng": 77.5946, "city": "Bengaluru" }
}
```

---

## 7. AWS Services Mapping

| AWS Service | Role in this project |
|-------------|----------------------|
| **Amazon MSK** | Managed Kafka cluster (prod). Dev uses Kafka in Docker to save cost. |
| **Amazon RDS (PostgreSQL)** | Source of truth: users, events, trips, bookings, payments, saved itineraries. |
| **Amazon ElastiCache (Redis)** | Cache external-API responses; store precomputed recommendations for fast serving. |
| **Amazon S3** | Data lake: raw activity events + curated Parquet; cached raw API responses; static assets. |
| **AWS Glue + Athena** | Catalog + serverless SQL over S3 for analytics. |
| **Amazon QuickSight** | Optional BI dashboards over Athena. |
| **Amazon Cognito** | User authentication & token issuance. |
| **Amazon ECS Fargate / EKS** | Run containerized Spring Boot services. |
| **API Gateway + ALB** | Routing, TLS, throttling. |
| **AWS Lambda** | Glue tasks: scheduled trip reminders, nightly ML retrain trigger. |
| **Amazon SES / SNS** | Email / SMS / push for notifications. |
| **AWS Secrets Manager** | External API keys, DB creds. |
| **Amazon SageMaker** | (Optional) train/host the recommendation model. |
| **Amazon CloudWatch** | Logs, metrics, alarms (e.g. Kafka consumer lag, error rates). |
| **AWS IAM** | Least-privilege roles per service. |

---

## 8. External Data Sources (Free vs. Mocked)

> **Key truth:** you do NOT integrate with a real store’s private data. You aggregate from public APIs and **mock what’s gated**. Say so honestly — it shows you understand the real data landscape.

| Data need | Recommended API | Cost reality | Fallback |
|-----------|-----------------|--------------|----------|
| Attractions, restaurants, ratings | **Google Places API** / **OpenTripMap** / Foursquare / OSM Overpass | Google has free credit; OpenTripMap/OSM are free | Curated seed dataset |
| Maps, distance, travel time | **Google Maps Distance Matrix/Directions** / **OpenRouteService** / OSRM | ORS/OSRM free | Haversine distance approximation |
| Flights + hotels + prices | **Amadeus Self-Service API** | Free self-service tier | **Simulate prices** from a formula |
| Trains / buses / transit timings | GTFS feeds / transit APIs | Varies by region (India train data is limited) | Mock schedules |

**Practical stance:** integrate Google Places (or OpenTripMap) + OpenRouteService + Amadeus free tier for real data; **simulate** flight/train/hotel pricing where real feeds are gated. In README + interview: *“Integrated Amadeus and Google Places; simulated pricing where production would use paid partner feeds.”*

---

## 9. Data Model / Database Schema

Core tables (PostgreSQL). Simplified — expand as needed.

```sql
users (
  id UUID PK, cognito_sub TEXT, email TEXT, name TEXT,
  created_at TIMESTAMP
)

user_preferences (
  user_id UUID FK, interests TEXT[],          -- e.g. {museums, nightlife, nature}
  budget_level TEXT,                            -- low | mid | high
  travel_pace TEXT                              -- relaxed | balanced | packed
)

events (
  id UUID PK, title TEXT, category TEXT, venue TEXT,
  lat DOUBLE, lng DOUBLE, city TEXT,
  start_time TIMESTAMP, price NUMERIC, capacity INT, available INT
)

trips (                                          -- destinations/packages
  id UUID PK, destination TEXT, lat DOUBLE, lng DOUBLE,
  base_price NUMERIC, description TEXT
)

bookings (
  id UUID PK, user_id UUID FK, item_type TEXT,   -- event | trip
  item_id UUID, status TEXT,                      -- pending|confirmed|cancelled
  amount NUMERIC, created_at TIMESTAMP
)

payments (
  id UUID PK, booking_id UUID FK, status TEXT,
  provider_ref TEXT, amount NUMERIC, created_at TIMESTAMP
)

itineraries (
  id UUID PK, user_id UUID FK, destination TEXT,
  start_date DATE, end_date DATE, plan JSONB,     -- day-by-day plan
  created_at TIMESTAMP
)

places_cache (                                    -- optional; Redis is primary cache
  cache_key TEXT PK, payload JSONB, expires_at TIMESTAMP
)
```

`recommendations` are stored in **Redis** as `recs:{userId}` → ranked list (not in RDS), refreshed by the ML job.

---

## 10. Feature Implementation Details

**F5 — Suggest nearby tourist spots for a location.**
`places-service` calls Places API “nearby search” by category near the location’s coordinates → returns candidates with rating, coords, opening hours → `recommendation-service` **re-ranks** by user preferences → return top N. Cache the raw Places result in Redis (key = `places:{lat},{lng}:{category}`, TTL hours).

**F6 — Planned itinerary.** See Section 11 (own algorithm).

**F7 — Nearby food/attractions after booking an event.** On `booking.confirmed`, take the venue coordinates → `places-service` nearby restaurants + attractions → personalize → surface in the confirmation screen and/or a follow-up notification.

**F8 — Travel: all commute modes + prices + timings.** `places-service` (or a dedicated `travel-service`) fans out **in parallel** to flight/train/bus providers (or mocks) for origin→destination, plus attractions with timings. Because it hits several slow external APIs, use **async parallel calls** (`CompletableFuture`) and aggressive caching. Present modes side-by-side with price, duration, and departure options.

**F9 — Hotels.** Amadeus Hotel API (or mock) near the destination, filtered by budget level, cached.

**F10 — Personalized feed.** Home page reads `recs:{userId}` from Redis (precomputed by the batch ML job) → fast lookup, no model inference on the request path.

**F11 — Notifications & reminders.** `notification-service` consumes events for immediate messages; a scheduled **Lambda** (or Spring `@Scheduled`) emits reminder events (“your trip is tomorrow — here’s your itinerary”).

**F12 — Analytics.** `s3-sink` consumer lands `user-activity` in S3; Glue/Athena power trending items, conversion funnels, popular destinations.

---

## 11. The Itinerary Generation Algorithm

This is your **technical highlight** — not just an API call, but a real algorithmic problem.

**Input:** destination, date range (N days), candidate spots (each with coords, rating, avg visit duration, opening hours), user preferences (pace → hours/day, interests → which categories to favor), optional start/end point (hotel).

**Problem shape:** select and order spots per day to maximize “value” (rating × interest match) while respecting a **time budget** (visit durations + travel time between stops) and **opening-hour windows**. This is essentially a **constrained, multi-day variant of the Orienteering / Travelling Salesman Problem with time windows** — a genuinely interesting optimization.

**Phased implementation:**

1. **v1 — Greedy + geographic clustering (start here):**
   - Cluster candidate spots by proximity (e.g. k-means on lat/lng, or grid buckets) into N day-groups so each day stays in one area (minimize cross-city travel).
   - Within a day, order stops by a nearest-neighbour heuristic starting from the hotel.
   - Compute travel time between consecutive stops (Distance Matrix API / OSRM) and pack until the daily time budget is full.
   - Respect opening hours: skip/reschedule a spot if arrival falls outside its window.

2. **v2 — Improve the route:** apply **2-opt** local search to reduce total travel time per day.

3. **v3 (stretch):** frame it explicitly as an optimization (weighted score, hard time-window constraints) and solve with a library (Google **OR-Tools** has a Java binding for routing with time windows). Mention this even if you stop at v1/v2.

**Output:** a JSON `plan` stored in `itineraries.plan`:
```json
{
  "days": [
    { "date": "2026-10-01", "stops": [
        { "placeId": "...", "name": "Fort", "arrive": "09:30", "leave": "11:00", "travelToNextMin": 15 },
        { "placeId": "...", "name": "Museum", "arrive": "11:15", "leave": "12:45" }
    ]}
  ]
}
```

**Interview line:** *“The itinerary generator is a constrained routing problem — I started greedy with geographic clustering and a nearest-neighbour order respecting opening hours, improved routes with 2-opt, and it generalizes to a TSP-with-time-windows I’d solve with OR-Tools at scale.”*

---

## 12. The ML Recommendation Component

**Keep it small and honest.** ML personalizes and ranks; it is not the whole system.

**Signal / data:** `user-activity` and `bookings` events in S3 — which users viewed/booked which events, trips, categories.

**Model options (pick one to start):**
- **Content-based filtering** (easiest, cold-start friendly): represent items by features (category, price band, city, tags); recommend items similar to what the user engaged with. Great v1.
- **Collaborative filtering** (matrix factorization via `implicit`/`surprise`): “users like you also booked…”. Stronger once you have interaction data.

**Serving pattern (the clever, interview-friendly part):** compute **offline, serve online**.
- A **batch job** (nightly, triggered by Lambda or a SageMaker training job) reads S3, trains/refreshes, and **writes each user’s top-N recommendations to Redis** (`recs:{userId}`).
- The Java `recommendation-service` just does a **fast key lookup** — no model inference on the request path.
- For a real-time touch, do lightweight rule-based recs from the user’s most recent session events off the Kafka stream.

**Interview line:** *“Recommendations are computed offline in a Python job over the S3 activity lake and served online as a Redis lookup — the ‘batch compute, online serve’ split real systems use. It’s content-based to start, with a collaborative-filtering upgrade path.”*

---

## 13. Caching Strategy

External APIs are **slow, rate-limited, and cost per call** — caching is essential and a strong talking point.

- **Places/attractions:** cache by `(lat,lng,category)` grid key, TTL hours.
- **Travel/flight/hotel searches:** cache by `(origin,destination,date)`, short TTL (prices change).
- **Recommendations:** precomputed in Redis, refreshed by batch job.
- **Popular events / search results:** cache hot queries.
- Discuss **cache invalidation** and **TTL trade-offs** (staleness vs. freshness), and **rate-limit protection** (cache shields you from hitting API quotas).

---

## 14. Security

- **Cognito** for auth; services validate JWTs.
- **Least-privilege IAM** roles per service (each can touch only its own resources).
- **Secrets Manager** for API keys and DB creds — never in code or env files committed to git.
- **HTTPS everywhere** (ALB/API Gateway TLS).
- Input validation, rate limiting at the gateway.
- Payments via **Stripe test mode** — never handle real card data directly.

---

## 15. Testing Strategy

| Layer | What & how |
|-------|-----------|
| **Unit (JUnit)** | Itinerary algorithm (known spots → assert order & timing), ranking logic, serializers. Fast, no AWS. |
| **Integration (LocalStack)** | Run Kafka/S3/Redis locally in Docker + LocalStack; publish events, assert consumers land correct data. Wire into CI. |
| **Contract/API tests** | Spring Boot test slices + Testcontainers for Postgres/Kafka. |
| **External API mocking** | WireMock to stub Places/Amadeus responses → deterministic tests, no live quota use. |
| **End-to-end correctness** | Push a known batch of events/bookings → assert analytics & recommendations match hand-computed expectations; test **idempotency** (replay events, no double-count). |
| **Load / throughput** | Drive activity at N events/sec; watch **Kafka consumer lag** in CloudWatch; reshard/scale if it climbs. |
| **Failure** | Malformed events → dead-letter topic; kill a consumer → recovers from offset. |

**Interview line:** *“I unit-tested the algorithmic core, ran integration tests against LocalStack + Testcontainers in CI so I never needed live AWS, mocked external APIs with WireMock, and load-tested to observe consumer lag.”*

---

## 16. CI/CD & Infrastructure as Code

- **Terraform** (or CDK) provisions everything: MSK, RDS, ElastiCache, S3, Glue, ECS/EKS, IAM, API Gateway. *“I `terraform apply` the whole stack”* is a strong signal.
- **GitHub Actions** pipeline: on push → build, unit + integration tests (LocalStack) → build Docker images → push to **ECR** → deploy to ECS/EKS.
- **CloudWatch** dashboards + alarms (consumer lag, error rate, latency).
- Keep environments (`dev`/`prod`) as Terraform workspaces; dev uses local Kafka to save cost.

---

## 17. Suggested Repository Structure

```
wanderly/
├── README.md                     # architecture diagram + design decisions
├── docs/                         # this design doc, ADRs
├── infra/                        # Terraform (or CDK)
│   ├── modules/
│   └── environments/{dev,prod}/
├── services/
│   ├── user-service/             # Spring Boot
│   ├── booking-service/
│   ├── places-service/
│   ├── itinerary-service/
│   ├── recommendation-service/
│   └── notification-service/
├── ml/                           # Python recommendation job
│   ├── train.py
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/                     # React (optional)
├── docker-compose.yml            # local: Kafka, Postgres, Redis, LocalStack
└── .github/workflows/ci.yml
```

Start as a **modular monolith** (`services/` as modules in one Spring Boot app) and split into separate deployables later if you want the microservices story — this avoids drowning in distributed-systems overhead too early.

---

## 18. Phased Roadmap

### Phase 0 — Foundations (Week 1)
- Repo, `docker-compose` (Kafka + Postgres + Redis + LocalStack), Spring Boot skeleton.
- Learn/confirm: Spring Boot basics, JPA, Docker. Set up Terraform skeleton.

### Phase 1 — The Spine / MVP (Weeks 2–4) ⭐ *finish this before anything else*
- `user-service` (Cognito auth or simple JWT to start) + preferences.
- `booking-service`: list events/trips, create booking, RDS.
- Kafka `bookings` + `user-activity` topics; `notification-service` consumer (email/mock).
- `places-service`: integrate Google Places / OpenTripMap for **F5** (nearby spots), Redis cache.
- `itinerary-service` **v1** greedy generator for **one city** (**F6**).
- **This alone is resume-worthy.**

### Phase 2 — Intelligence & Analytics (Weeks 5–7)
- `s3-sink` consumer → S3 raw/curated; Glue + Athena; simple dashboard.
- Python ML job (content-based) → Redis; `recommendation-service` re-ranking (**F7, F10**).
- Itinerary **v2** (2-opt), opening-hours handling.
- Payments (Stripe test) + `payments` topic (**F4**).

### Phase 3 — Breadth & Polish (Weeks 8+)
- Travel aggregation: commute modes + prices + hotels (Amadeus / mock) (**F8, F9**) with async parallel calls.
- React frontend + live demo (“book here → see recommendations/itinerary”).
- Full Terraform, CI/CD to ECS/EKS, CloudWatch alarms, load testing.
- Stretch: OR-Tools itinerary, SageMaker hosting, collaborative filtering.

> Everything in Phase 3 you can **speak to in interviews even if unfinished** — list as “next steps.”

---

## 19. Prerequisites — What to Learn Before Starting

**Must have before Phase 1:**
- **Java + Spring Boot** (REST controllers, Spring Data JPA, dependency injection).
- **Relational DB / SQL** basics and JPA entity mapping.
- **Docker** & docker-compose (run Kafka/Postgres/Redis locally).
- **Kafka fundamentals**: topics, partitions, producers/consumers, consumer groups, offsets. (Spring Kafka makes this approachable.)
- **REST + JSON**, HTTP status/error handling.

**Learn during (just-in-time):**
- **AWS basics**: IAM, S3, RDS, ECS/Fargate, MSK, ElastiCache — one service at a time as each phase needs it. Use the **AWS Free Tier**.
- **Terraform** basics (resources, variables, state).
- **Redis** (key/value, TTL).
- **Python ML basics** (pandas, scikit-learn) — only for the recommendation job.
- **Athena/Glue** and **Parquet/partitioning** concepts for analytics.
- **Optimization basics** (greedy, nearest-neighbour, 2-opt; OR-Tools if going deep) for the itinerary.

**Accounts/keys to set up:** AWS account (Free Tier), Google Cloud (Places/Maps API key with billing + quotas capped), Amadeus self-service, Stripe test keys.

---

## 20. Cost Considerations

- **Develop locally** (Docker Kafka/Postgres/Redis + LocalStack) → near-zero cost.
- Use **AWS Free Tier** and **tear down** with `terraform destroy` when not demoing. MSK and NAT gateways are the pricier items — spin up only when needed, or demo MSK briefly.
- **Cap external API quotas** (Google, Amadeus) to avoid surprise bills; caching also reduces call volume.
- Keep a small “demo mode” you can bring up for interviews, then destroy.

---

## 21. Risks & Scope Warnings

- **Scope creep is the #1 risk.** The full feature list is a multi-month build. **Finish the spine (Phase 1) first.** Three services genuinely working over Kafka beat ten half-built ones.
- **Don’t force Kafka everywhere.** The synchronous booking/search path stays REST. Kafka only for async fan-out. Over-engineering is a red flag to interviewers.
- **Real pricing data is gated.** Plan to mock flight/train/hotel prices; be upfront about it.
- **External API quotas/costs** can bite — cache and cap.
- **ML is a small ranking layer**, not the headline. Don’t oversell it.
- **AWS cost** — always `terraform destroy` after demos.

---

## 22. Resume Bullets & Interview Talking Points

**Resume bullets (tailor numbers once real):**
- *Built a Java/Spring Boot trip-planning & event-booking platform with an event-driven architecture on Apache Kafka (Amazon MSK), decoupling booking, notifications, payments, and analytics.*
- *Designed a day-by-day itinerary generator modeled as a constrained routing (TSP-with-time-windows) problem using geographic clustering, nearest-neighbour ordering, and 2-opt optimization.*
- *Integrated external Places/Maps/travel APIs behind a Redis caching layer to cut latency and stay within rate limits; simulated pricing where production uses paid partner feeds.*
- *Implemented a Python recommendation service (content-based → collaborative filtering) trained over an S3 data lake, served online via Redis for sub-ms lookups.*
- *Provisioned the full AWS stack with Terraform (MSK, RDS, ElastiCache, S3/Glue/Athena, ECS Fargate, Cognito) with CI/CD via GitHub Actions and LocalStack integration tests.*

**One-paragraph interview pitch:**
> “Wanderly is a trip-planning and event-booking platform with a Java/Spring Boot backend. The booking and search path is synchronous over REST and Postgres, but I used Kafka on MSK for the event-driven parts — a confirmed booking fans out to notifications, payments, analytics, and the recommender, so the API responds instantly and services stay decoupled. It aggregates attractions, transit, and hotels from external APIs cached in Redis to manage latency and rate limits, and builds itineraries with a route-optimization algorithm that respects opening hours and travel time. User activity streams through Kafka into an S3 data lake where a Python job trains a recommendation model and writes results to Redis for the backend to serve. Everything runs containerized on Fargate, provisioned with Terraform, tested against LocalStack in CI.”

**System-design questions to rehearse:** partition-key choice; handling consumer lag/back-pressure; idempotency in consumers; cache invalidation & TTL trade-offs; when NOT to use Kafka; how you’d scale the itinerary optimizer; cold-start in recommendations; failure handling & dead-letter topics.

---

*End of design document. Rename the project, adjust scope to your timeline, and build the spine first.*
