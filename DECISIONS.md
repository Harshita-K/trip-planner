# Wanderly: Build Log, Decisions and Features

This is a living record of **what was built, why it exists, and which choices were made along the way** (plus the alternatives that were rejected). It complements the [design doc](trip-event-platform-design.md): the design doc is the plan, and this file records what actually happened.

**How to maintain it:** when you make a non-obvious choice, add a numbered entry under *Decisions*. When a feature ships, move it from *Not built yet* to *Features built*.

---

## Status at a glance

| Phase (design §18) | Status |
|---|---|
| Phase 0: repo, docker-compose, Spring Boot skeleton | ✅ Done |
| Phase 1: the spine (auth, booking, Kafka, notifications, places + cache, itinerary v1) | ✅ Done |
| Phase 2: intelligence and analytics | 🟡 Mostly done: itinerary v2 (2-opt, opening hours) and a **time-window-aware v3 scheduler with lunch** (D52), content-based re-ranking, real-time affinity, the `recs:{userId}` feed contract, **F12 analytics (Kafka → S3 lake → SQL → dashboard)**. Payments were dropped on purpose (D47), and booking became saving (D51). The Python ML job is out of scope by choice. |
| Phase 3: breadth and polish | 🟡 Started: React web app, real email (SMTP/Mailpit), email-verified sign-up, **forgot password** (D55), any-city places with no keys, **user-listed events** (D54), **trip reminders** (D53), **F8 travel options and F9 stays**. Paid AWS (Terraform, ECS, Cognito…) is out of scope by choice. |

---

## Features built

| ID | Feature | Purpose | Where | Endpoint(s) |
|---|---|---|---|---|
| F1 | Sign up (with email verification), login, forgot password, profile, preferences | Identify users, and prove each owns their email with a single-use 6-digit code (Redis, emailed by an async worker; D36–D38). Forgotten passwords are reset the same way, signing out other sessions (D55). Preferences (interests, budget, pace) drive personalisation and itinerary pacing. | `user/` | `POST /api/auth/register`, `POST /api/auth/verify`, `POST /api/auth/resend-code`, `POST /api/auth/login`, `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`, `GET /api/users/me`, `PUT /api/users/me/preferences` |
| F2 | Search and browse events and trip ideas | Synchronous catalog browsing: filter by city, category and date, paged. Public, no login required. Trip ideas open the planner with a place and length. | `catalog/CatalogService` | `GET /api/events`, `GET /api/events/{id}`, `GET /api/trips`, `GET /api/trips/{id}` |
| — | List an event | Anyone signed in can list an event in any city, so coverage isn't limited to the seeded three cities. The venue is geocoded near the city. Only the lister (or an admin) can edit or remove it (D54). | `catalog/CatalogService` | `POST /api/events`, `PUT /api/events/{id}`, `DELETE /api/events/{id}`, `GET /api/events/mine` |
| F3 | Save events to your plans | The planner's "I'm going" (D51). Idempotent in one SQL statement; emits `event.saved` / `event.unsaved` to Kafka after commit, which drives the inbox note, affinity, analytics and reminders. | `saved/SavedEventService`, `SavedEventRelay` | `PUT /api/saved-events/{eventId}`, `DELETE /api/saved-events/{eventId}`, `GET /api/saved-events` |
| F5 | Nearby tourist spots | Finds attractions near any coordinate, with **no API key or account**: curated data in Bengaluru and Jaipur, pre-fetched OpenStreetMap data for the bundled destinations, and live Wikipedia (sights, ranked by page views) plus OpenStreetMap (food, opening hours) anywhere else (D44–D45). OpenTripMap is used instead if a key is configured. Anonymous callers get results ranked by rating and distance; logged-in users also get interest and affinity boosts. | `places/`, `recommendation/PlaceRanker` | `GET /api/places/nearby`, `GET /api/places/coverage` |
| — | City search (India) | Search-as-you-type: instant matches from 85 bundled destinations (old names included), plus every city, town and village in India from Photon, with no API key (D43). | `places/CityDirectory` | `GET /api/cities?q=`, `GET /api/cities/suggest?q=` |
| F6 | Day-by-day itinerary | The algorithmic highlight. It turns a destination and date range into an ordered, timed plan per day built *around* opening hours (D52), with a lunch break and a nearby restaurant, travel time and the user's pace. Anyone can preview a plan; logged-in users save it as JSONB (D35). | `itinerary/` | `POST /api/itineraries/preview` (public), `POST /api/itineraries`, `GET /api/itineraries`, `GET /api/itineraries/{id}` |
| F7 | Food and attractions near an event | Helps plan the rest of the day around an event. Public; personalised with a token. Also in the "Saved" inbox note. | `recommendation/NearbySuggestionService` | `GET /api/events/{id}/nearby` |
| F10 | Personalised feed | Home-page event recommendations. Serves `recs:{userId}` from Redis when an offline job has written it, and otherwise uses a content-based score (popularity = how often an event is saved). | `recommendation/RecommendationService` | `GET /api/recommendations/feed` |
| F11 | Notifications and reminders | An inbox note when you save an event (no email), reminders for saved events within 24 hours, and a day-before reminder for saved trips with day 1's timetable (D53). Delivered by a Kafka consumer, stored in an inbox, reminders emailed over SMTP (Mailpit locally). Times are shown in IST, amounts in ₹ (D33). | `notification/` | `GET /api/notifications` |
| F8 | Getting there | Flight, train, bus and car options between any two places: door-to-door time, indicative fares per class, departures, CO₂, and Fastest/Cheapest/Greenest badges. Road distance is real (OSRM); fares and timetables are simulated (D48). | `travel/` | `GET /api/travel` |
| F9 | Where to stay | Real hotels, guest houses and hostels near the destination (OpenStreetMap via Photon), tiered budget/comfort/luxury, with indicative prices for the actual nights, filtered by budget (D49). | `stay/` | `GET /api/hotels` |
| F12 | Analytics | Consumer group `analytics-sink` lands `user-activity` and `saved-events` in an S3 data lake (Hive-partitioned NDJSON). DuckDB SQL over the lake feeds a dashboard: daily activity, funnel, top cities, interests, routes and most-saved events (D50). | `analytics/` | `GET /api/analytics/summary` (admins) |
| — | User-activity stream | Every meaningful action (view, search, itinerary, save) goes to `user-activity`. This feeds real-time affinity now and the S3 lake and ML later. | `messaging/EventPublisher`, `recommendation/ActivityConsumer` | (internal) |
| — | Email worker | Sends sign-up and password-reset codes and "account already exists" notices off the request path. Idempotent under Kafka redelivery (D38). | `notification/EmailWorker` | (internal, topic `emails`) |
| — | Dead-letter handling | Retries a failing message twice, then parks it on `dead-letter` so one bad message cannot block a partition. | `config/KafkaConfig` | (internal) |

### Supporting pieces

- **Kafka topics and consumer groups:**

  | Topic | Key | Consumed by |
  |---|---|---|
  | `saved-events` | userId | `notification-service` |
  | `user-activity` | userId | `recommendation-service` |
  | `notifications` | userId | `notification-service` (reminders) |
  | `emails` (1-hour retention) | recipient email | `email-worker` |
  | `dead-letter` | none | for inspection; failures land here after retries |

  `user-activity` and `saved-events` are also read by `analytics-sink` (F12).

- **Redis keys:**

  | Key | What | TTL |
  |---|---|---|
  | `places:{provider}:{lat},{lng}:r{km}:{cats}` | cached nearby places (D17) | 6 h |
  | `affinity:{userId}` | sorted set of category scores (D23) | 30 days |
  | `recs:{userId}` | precomputed feed, written by the future ML job (D24) | set by the job |
  | `otp:{email}` | pending code: `{hash, attempts, id}` (D38) | 10 min |
  | `otp:cooldown:{email}` / `otp:notice:{email}` | resend and "account exists" throttles | 60 s |
  | `pwreset:{email}` / `pwreset:cooldown:{email}` | pending password-reset code and its resend throttle (D55) | 10 min / 60 s |
  | `auth:revoked-before:{userId}` | tokens issued before this epoch-ms are rejected (after a password reset, D55) | token TTL (12 h) |
  | `photon:venue:{lat},{lng}:{venue, city}` | geocoded venue for a listed event (D54) | 30 days |
  | `ratelimit:{rule}:{ip}` | token bucket `{tokens, ts}` per client IP (D58) | until refilled |
  | `email:job:{jobId}` | worker idempotency state, `sending:{token}` → `sent` | 30 s lease / 24 h |
  | `osmhours:{type}/{id}` / `osmhours:wd:{Qid}` | OpenStreetMap opening_hours per OSM object or Wikidata id (`""` = none tagged) (D40, D45) | 7 days |
  | `photon:in:{query}` | Photon city suggestions (D43) | 30 days |
  | `road:{from}:{to}` | OSRM road distance and time (D48) | 30 days |
  | `travel:{from}:{to}:{date}:{n}` | computed travel options (D48) | 1 h |
  | `hotels:{lat},{lng}` | stays near a ~1 km cell, from Photon (D49) | 7 days |

- **Where data lives:**

  | Store | Holds |
  |---|---|
  | PostgreSQL | Source of truth: users, preferences, events (seeded and user-listed), trip ideas, saved events, itineraries, notifications |
  | Redis | Codes (TTL), caches, affinity scores, email-job state |
  | Kafka | Every event, retained for replay; consumers re-read history (that's how the lake backfilled) |
  | S3 data lake (SeaweedFS locally, AWS S3 in production) | Raw event history for analytics, `raw/{topic}/dt=…/hour=…/` |
  | DuckDB (embedded) | Holds no data: SQL engine over the lake, the local stand-in for Athena |

- **Database migrations (Flyway):** `V6` adds the transactional `outbox` table (D56).
- **Earlier migrations:** `V1` schema, `V2` seed catalog, `V3` email-verified columns (plus a pending-codes table, since removed), `V4` drop that table because codes moved to Redis, `V5` bookings → `saved_events` (live event bookings carried over), drop seats and trip prices, add `events.created_by` (D51, D54).
- **Local stack** (`docker-compose.yml`): Postgres 16, Redis 7, Kafka 3.8 in KRaft mode (no ZooKeeper), Kafka UI on :8081, and Mailpit (a local mail server with an inbox UI on :8025). There's an optional `app` profile that also runs the backend container.
- **Data sources:**

  | Data | Source | Live? |
  |---|---|---|
  | Places in Bengaluru and Jaipur (38) | Hand-curated `places/seed-places.json`, with opening hours and visit durations | No (demo data) |
  | Places in the bundled destinations | OpenStreetMap, pre-fetched via Overpass by `tools/fetch_osm_places.py` into `places/osm-cities.json`, with Wikipedia page views added | Bundled (refresh by rerunning the script) |
  | Sights anywhere else in India | Wikipedia geosearch (no key), ranked by 30-day page views | Yes, cached 24 h |
  | Food and nightlife anywhere else | OpenStreetMap via Overpass (no key) | Yes, cached 24 h when complete |
  | Opening hours for live places | OpenStreetMap `opening_hours` (by OSM id or Wikidata id), fetched in the background; category estimate until then | Yes, cached 7 days |
  | Optional: OpenTripMap | Replaces the Wikipedia/Overpass live layer if `OPENTRIPMAP_API_KEY` is set | Yes |
  | City list (85 destinations) | `cities/india.json`, geocoded once via Nominatim | Bundled |
  | Any other city, town or village | Photon (OpenStreetMap geocoder, no key), as you type | Yes, cached 30 days |
  | Events (9) and trip ideas (4) | Flyway `V2` seed, dates relative to migration time | No (demo data) |
  | User-listed events | Listed in the app (D54); venues geocoded via Photon | Yes |
  | Road distance and drive time | OSRM public server (no key) | Yes, cached 30 days |
  | Airports (116 with scheduled flights) | OurAirports open data (public domain), `travel/airports-in.json` | Bundled |
  | Train, bus and flight fares and timetables | Simulated (formulas in `TravelPlanner`) | No (labelled "indicative") |
  | Hotels and guest houses | OpenStreetMap via Photon (no key) | Yes, cached 7 days |
  | Hotel prices | Simulated (formulas in `HotelPricing`) | No (labelled "indicative") |
- **Tests (91, all passing):**
  - **73 unit tests** (`mvn test`): the code cipher (round trip, fresh nonce, bound to job and recipient, tamper and wrong-key rejection, no plaintext in the Kafka JSON), rate-limit routing, the day scheduler (time-aware 2-opt untangles crossings, never makes a day worse or infeasible, closing times and weekly closures, eating before a visit that would run past the lunch window), the planner (late openers go later instead of causing waits, a day still fills when the top picks don't fit, lunch inside the window and between stops, a nearby open restaurant not repeated across days, waiting reported only when unavoidable, plus clustering, pace, weekly closures, determinism), the trip-reminder text, travel planner (modes, rail and airport rules, last-minute pricing, per-car pricing, determinism), hotel tiering and pricing, DuckDB analytics SQL over a local lake (de-duplication, monotonic funnel, net saves, empty lake), k-means, travel estimator, ranker, geo maths, the OSM opening_hours parser (12 cases), OpenTripMap mapping, Overpass places (categories, English names, notability, page views, outages), Wikipedia sights (filtering, classification, page-view ranking), the hours enricher (OSM id and Wikidata paths), and city search (Photon).
  - **1 opt-in live test**, `LIVE_OSM=true mvn test -Dtest=OsmHoursClientLiveTest`, which calls the real Overpass API.
  - **17 end-to-end tests** in `PlannerFlowIT` (`mvn verify`, real Postgres, Kafka and Redis via Testcontainers):
    - login is rate limited per client IP (429 + Retry-After), other IPs unaffected
    - the session is an httpOnly, SameSite=Lax cookie; writes need the CSRF header; logout and stale cookies are cleared
    - the outbox relays committed messages and never rolled-back ones; enqueueing outside a transaction fails
    - saving fans out to an inbox-only note with nearby picks; saving and unsaving are idempotent; nearby is public
    - organisers list events in any city; only they can edit or remove them; seeded events are admin-only; validation; deleting removes saves
    - reminders for a saved event and a trip starting tomorrow, delivered once across hourly reruns
    - forgot password: unknown emails get the same 202, wrong and reused codes fail, old sessions are signed out
    - notification consumer idempotency
    - multi-day itinerary
    - public nearby places
    - unverified login blocked
    - code works only once
    - 5 wrong attempts burn the code
    - 8 concurrent correct submissions → exactly one wins
    - sign-up doesn't reveal existing accounts
    - email worker sends a redelivered job once and skips stale codes
    - city search plus the coverage report
- **CI:** not set up yet. There's no `.github/workflows` file in the repo, so run `mvn verify` locally.
- **Swagger UI** (`/swagger-ui.html`, springdoc 2.6): a browser interface for trying every endpoint, with an Authorize button for the JWT. It's public, alongside `/v3/api-docs`.
- **API walkthrough:** `docs/api.http` (sign up → read the code in Mailpit → verify → use the token).
- **Web app** (`frontend/`, React 18 + Vite, http://localhost:5173). Everything a user needs, with no API knowledge required:

  | Page | What you can do there | Features used |
  |---|---|---|
  | Explore (home) | Search any city (or "All of India"); a city without events offers to list one, discover places or plan a trip there; filter by category, see "Picked for you" with reasons, open an event to save it and see food and sights around it; trip ideas open the planner | F2, F3, F7, F10 |
  | Plan a trip | No login needed. Search any Indian city (cities without data are greyed out), choose dates, pace and interests; get a day-by-day timeline with times, travel between stops, a lunch break with a nearby restaurant, and a note when a stop opens after you arrive; reopen saved plans | F6 |
  | Discover | Search any city or pick "All of India" (popular cities merged by score), set radius and category; results ranked for you, with "Closed Mon" and where each place's hours come from | F5 |
  | My trips | Saved events (details, nearby, remove), saved trip plans (open in the planner), and events you listed (edit, remove) | F3, F6, F7 |
  | List an event (account menu, Explore) | Title, category, city (any in India), venue, date and time, entry fee, description; edit later from My trips | — |
  | Plan a trip → Getting there | Pick your origin and travellers; compare flight, train, bus and car with door-to-door time, fares, departures and CO₂ | F8 |
  | Plan a trip → Where to stay | Real stays near the destination for your dates; Budget/Comfort/Luxury filter; links to Google Maps and OpenStreetMap | F9 |
  | Analytics (account menu) | Stat tiles, activity per day (with a table view), funnel, top cities, interests, routes, most saved | F12 |
  | Inbox (bell icon) | "Saved" notes and reminders delivered via Kafka; an unread badge polls every 8 s | F11 |
  | Profile | Interests, pace and budget. Shown as onboarding right after sign-up | F1 |
  | Log in / Sign up dialog | Appears when an action needs an account. Sign-up has a second "Check your email" step: a 6-digit code that auto-submits, a resend with a 60 s countdown, and "wrong email? go back". "Forgot password?" sends a reset code, then takes the code and a new password | F1 |

---

## Decisions

Each entry gives the choice, why it was made, and what it costs.

### Architecture

**D1. Modular monolith first, not six deployables.**
One Spring Boot app, with packages that mirror the design's services (`user`, `booking`, `places`, `itinerary`, `recommendation`, `notification`). *Why:* the design doc (§5, §17, §21) warns that scope creep and distributed-systems overhead are the main risk. One deployable keeps the focus on features while keeping the seams visible. Kafka consumers already use separate consumer groups, as they would as separate services. *Cost:* the boundaries are not enforced by the compiler. Two shortcuts exist today: `RecommendationService` reads `EventRepository`, and `ReminderScheduler` reads `BookingRepository`. Both would become API calls or events after a split. *Upgrade path:* add Spring Modulith or ArchUnit tests to enforce package boundaries, then split along them.

**D2. Java 21 + Spring Boot 3.3, with virtual threads on.**
Java 21 is the current LTS and has records, pattern switches and `Math.clamp`. Virtual threads (`spring.threads.virtual.enabled`) make blocking I/O (JDBC, Redis, the external places API) cheap without going reactive.

**D3. Synchronous where the user waits, Kafka where they don't.** (design §1)
Search, view, book and itinerary generation are REST calls backed by Postgres and Redis. Notifications, affinity updates, reminders and **all email sending** are asynchronous. Kafka is **not** awaited on the request path: publishes are fire-and-forget, so even sign-up returns without waiting for the mail server.

### Data and persistence

**D4. Flyway owns the schema, and Hibernate only maps it (`ddl-auto: none`).**
*Why:* schema changes are reviewed SQL that is versioned in git, and the same migrations run in dev, CI and RDS. `none` rather than `validate` avoids false alarms from Postgres-specific types (`text[]`, `jsonb`).

**D5. Seat inventory uses one atomic conditional UPDATE.** *(Superseded by D51: no seats any more.)*
`update events set available = available - :qty where id = :id and available >= :qty` returns 0 when the seats are gone, which becomes a 409 response. *Why:* check and decrement happen in a single statement, so two concurrent bookings for the last seat cannot both succeed, and no row lock is held across application code. *Rejected:* `SELECT … FOR UPDATE` (holds a lock longer) and optimistic `@Version` (forces retries under contention). A DB `CHECK (available >= 0)` backs this up.

**D6. Bookings snapshot `item_title`.** *(Superseded by D51: there are no bookings now.)*
It works like an order line. The bookings list needs no join, and a booking still reads correctly if the event is later renamed.

**D7. Bookings are confirmed immediately. The `pending` status is reserved for Phase 2.** *(Superseded by D51.)*
There is no payment integration yet (F4). When Stripe test mode lands, the flow becomes `pending` → payment event → `confirmed`, and the schema and events already allow for it.

**D8. Itinerary plans are stored as JSONB.**
The plan is read and written as a whole and never queried by stop, so a document column fits. Dates and times inside it are plain strings (`"2026-10-01"`, `"09:30"`), which keeps the JSON exactly in the design-doc shape and avoids depending on Hibernate's JSON mapper configuration for `java.time`.

### Events and Kafka

**D9. Publish after commit (`@TransactionalEventListener(AFTER_COMMIT)`), not a transactional outbox (yet).** *(Superseded by D56: the outbox is built.)*
*Why:* consumers must never see a booking that rolled back, such as one where seat reservation failed. *Cost:* if the app crashes between commit and publish, that event is lost, because this is at-most-once publishing. *Upgrade path:* a transactional outbox table polled by a relay, or Debezium CDC. This is noted in `EventPublisher`.

**D10. Payloads are JSON strings (`StringSerializer`), not Spring's `JsonSerializer` with type headers.**
*Why:* type headers bake Java class names into the messages. The Phase 2 Python ML job and the S3 sink need to read these topics too, so the contract is plain JSON (documented in the `messaging/*Event` records).

**D11. Partition keys follow the design** (§6): `bookings` is keyed by `bookingId`, `user-activity` and `notifications` by `userId`, and `emails` by recipient address. *Why:* per-key ordering means one booking's confirm and cancel events, and one user's session, stay in order. The idempotency test relies on this too. *(Update: `bookings` became `saved-events`, keyed by `userId`, D51.)*

**D12. The notification consumer is idempotent, and the affinity consumer deliberately is not.**
Notifications: `source_event_id` is UNIQUE. The consumer checks for an existing row, inserts, then sends, all in one transaction. A racing duplicate loses on the constraint. If sending fails, the row rolls back and Kafka retries. Affinity is a soft, decaying score, so a replay that double-counts a view has no visible effect, and deduplication there would cost more than it saves.

**D13. Retry twice, then send to the dead-letter topic. Malformed JSON is never retried.**
A `DefaultErrorHandler` with `FixedBackOff(1s, 2)` and a `DeadLetterPublishingRecoverer` to `dead-letter`. `JsonProcessingException` and `IllegalArgumentException` are not retryable, because retrying cannot fix a bad payload. One exception gets its own policy: `EmailInFlightException` (another delivery holds the email job's lease) is retried every 5 s up to 12 times, outlasting the 30 s lease (D38).

**D14. Reminder event ids are deterministic (`reminder:{bookingId}`).** *(Now `reminder:event:{userId}:{eventId}` and `reminder:itinerary:{id}`, run hourly, D53.)*
The daily `@Scheduled` job may run on several instances or be re-run. The deterministic id and the idempotent consumer together guarantee one reminder per booking. In AWS this job becomes an EventBridge-scheduled Lambda emitting the same message.

### Places and caching

**D15. A `PlacesProvider` interface with two implementations: a curated seed dataset (default) and OpenTripMap (opt-in).** *(Superseded by D39: both are now used together, routed by location.)*
*Why seed by default:* the app runs and tests pass with zero API keys, and the results are deterministic. The dataset also has what free APIs lack, namely opening hours and typical visit durations, which the itinerary needs. *Why OpenTripMap over Google Places:* it's free with no billing account, which the design (§8, §20) favours. Missing hours are filled with category defaults. *Cost:* the OpenTripMap provider has not been exercised against the live API yet.

**D16. Cache-aside through a small `JsonCache` helper, not `@Cacheable`.**
*Why:* explicit keys and TTLs are easy to explain, and it degrades gracefully: a Redis outage or a stale payload shape is logged and falls through to the source. `@Cacheable` with Redis also needs polymorphic type info for `List<Record>` values, which is brittle.

**D17. Places are cached on a ~1.1 km grid.**
The key is `places:{provider}:{lat 2dp},{lng 2dp}:r{radius}:{categories}`. The provider is queried from the cell centre with a +1 km radius pad, then distances are recomputed from the caller's real location and filtered. *Why:* nearby searches from almost the same spot share one external call, which protects rate limits and cost (design §13). TTL is 6 hours. The provider name in the key means switching providers never serves the wrong data.

### Itinerary algorithm (F6)

**D18. Heuristic pipeline: score → pool → k-means (one cluster per day) → nearest neighbour → 2-opt → time-window scheduling → spill-over.**
This is design §11 v1 and v2 together, because 2-opt is about 20 lines once there is a route.
- *Score:* `rating/5`, multiplied by 1.5 when the category is one of the user's interests.
- *Pool:* the top `days × maxStops × 1.5`, so clustering isn't pulled around by low-value filler.
- *k-means* keeps each day in one part of the city. *2-opt* removes crossing legs left by nearest neighbour.
- *Scheduling:* if you arrive before opening, you wait. A stop is skipped if the visit would end after closing or after the pace's day end. Skipped stops get a second chance on any day with room, and are otherwise listed as `unscheduled`.

**D19. Deterministic farthest-point k-means initialisation (no randomness).**
The same request always gives the same plan, which makes tests stable and user-facing behaviour predictable. It follows the k-means++ idea (spread the seeds apart) without the randomness.

**D20. Travel time is haversine × 1.4 road factor at 22 km/h, plus 5 minutes overhead, behind a `TravelTimeEstimator` interface.**
It's the design's documented fallback: free, instant and monotonic in distance. OpenRouteService or OSRM can replace it without touching the planner. 2-opt optimises straight-line km, which ranks orderings the same way.

**D21. Pace profiles:** relaxed is 10:00–17:00 with at most 3 stops, balanced is 09:30–18:30 with at most 5, and packed is 08:30–20:30 with at most 7. Itineraries exclude `food` as stops; meals are a lunch break with a suggested restaurant (D52). The maximum trip length is 7 days.

**Known limitations (honest list for interviews):** the route is ordered first and scheduled second, so a place that opens late can cause idle waiting. There is no lunch slot and no return-to-hotel leg. The time-window-aware fix is design §11 v3 (OR-Tools VRPTW). *Update: ordering and scheduling now happen together, with lunch (D52). Still no return-to-hotel leg.*

### Recommendations

**D22. Ranking is a transparent weighted score, not a model (yet).**
Places: `0.45·rating + 0.30·interest match + 0.15·recent affinity + 0.10·proximity`. Feed: `0.40·interest + 0.30·affinity + 0.20·popularity (fraction sold) + 0.10·soonness`. Each result carries a human-readable `reason`. *Why:* the design says ML is a small ranking layer. This gives a working, explainable baseline, and it doubles as the cold-start fallback once the model exists.

**D23. Real-time affinity comes from Kafka and is stored in a Redis sorted set.**
`ActivityConsumer` (group `recommendation-service`) adds weights per category: search 0.5, view 1, itinerary 2, booking 5. They go into `affinity:{userId}` with a 30-day TTL. Scores are normalised to 0..1 when read. This is the "real-time touch" from design §12.

**D24. The feed contract for the future ML job is fixed now:** `recs:{userId}` holds a JSON array of event ids in rank order. The Python batch job only has to write that key, and the Java side already serves it. It filters out sold-out and past events, and falls back to the content-based score when the key is absent.

### Security and API

**D25. Locally issued HS256 JWTs, validated by Spring's OAuth2 resource server.**
*Why not Cognito yet:* it needs AWS for every local run and test. Because validation already goes through the standard resource server, moving to Cognito means swapping the `JwtDecoder` for `issuer-uri`, and controllers are unchanged. The JWT subject is the user id. Passwords are hashed with BCrypt. A token is only ever issued for a **verified** email (D36). The secret must be at least 32 bytes (checked at startup) and comes from `JWT_SECRET`, which would be Secrets Manager in AWS.

**D26. Browsing is public, and personalisation is opportunistic.**
Public: `GET` on events, trips and places, `POST /api/itineraries/preview` (D35), `/api/auth/**`, Swagger and health checks. If a valid token is present, public endpoints personalise and emit activity events. Everything that writes, and everything user-specific, requires a token.

**D27. Errors are RFC 7807 problem details.**
Domain errors (`ApiException` → 400, 401, 403, 404, 409 or 429) and bean-validation errors share one response shape. An optional machine-readable `code` field (e.g. `EMAIL_NOT_VERIFIED`) lets the frontend branch without parsing messages. `@Validated` is intentionally **not** put on controllers, because in Spring 6.1 that switches to AOP validation, which returns 500s instead of 400s.

### Testing and tooling

**D28. Pure algorithm classes, plus one Testcontainers integration test class. No LocalStack yet.**
The planner, ranker and 2-opt have no Spring and no I/O, so they're tested with fast plain JUnit (design §15). `PlannerFlowIT` (formerly `BookingFlowIT`) uses real Postgres, Kafka and Redis through `@ServiceConnection`. It swaps in a capturing `NotificationSender` to read emailed codes and count sends per Message-ID. LocalStack is deferred until an AWS service (S3) is actually used. **Testcontainers is pinned to 1.21.4** in `pom.xml`: the 1.19.8 version Spring Boot 3.3 ships uses a Docker API version that Docker Engine 29 rejects ("Status 400").

**D29. No Maven wrapper is committed.**
It couldn't be generated when the project was scaffolded (no JDK or Maven on the machine yet). Use a locally installed Maven, or add one with `mvn wrapper:wrapper`.


**D30. Swagger UI for developers; the React web app for users (D31).**
Swagger is auto-generated from the controllers, so it never drifts from the API and costs one dependency. Since D36, getting a token there takes a step: register, read the code in Mailpit, call `/api/auth/verify`, then Authorize.


**D31. A React + Vite single-page app, with no UI framework or router library.**
*Why:* React matches design §4. Plain CSS with design tokens keeps the "Golden hour" theme fully custom: sand background, deep teal, terracotta actions, marigold highlights, a Fraunces serif for headings, and postcard-style event cards with date stamps. Hash routing (`#/plan`) keeps it to two runtime dependencies (react, react-dom). The Vite dev server proxies `/api` to :8080, so the backend needs no CORS config. *Cost:* no TypeScript, and only six pages, so this would be revisited if the app grows.

**D32. Login is asked for at the moment of need, not up front.** *(The token now lives in an httpOnly cookie, not localStorage, D59.)*
Browsing, Discover, trip packages and **planning** work logged out. Booking, saving a plan and the inbox open a login dialog that explains why ("Log in to complete your booking."). Sign-up goes straight to an interests screen, because interests drive every ranking. The JWT is kept in `localStorage`, and a 401 clears it and reopens the login dialog. *Cost:* `localStorage` tokens are readable by XSS. An httpOnly-cookie session or the Cognito hosted UI is the production path.

**D33. Notification text is formatted for people:** times show in IST ("Mon, 12 Oct 2026, 8:00 pm IST") and amounts in rupees (₹1598). All seeded destinations are in India; per-user time zones are the general fix.


**D34. City choice is a type-to-search combobox, and the default scope is "All of India".** *(The fixed city list and "Coming soon" entries were replaced by live city search in D41.)*
One `CityCombobox` is used on Explore, Discover and Plan, following the WAI-ARIA combobox pattern: typing filters with the match highlighted, ↑/↓/Enter/Esc work, and a typed exact match is accepted on blur. *Why:* buttons don't scale past a few cities, and typing is what people expect for a city. Cities we don't cover yet (Mumbai, Delhi…) are listed but disabled as "Coming soon". Goa is selectable on Explore (it has events) but disabled on Plan and Discover (no mapped sights), so nobody can pick a city that would return an empty or failed result. "All of India" (value `''`) means no city filter on Explore. On Discover it runs one nearby search per mapped city in parallel and merges by score, because the backend's nearby search is radius-based. Plan has no "All of India" option, because an itinerary needs one base city.


**D35. Anyone can plan a trip; only saving needs an account.**
`POST /api/itineraries/preview` is public: it runs the same planner and returns the plan without storing it. `POST /api/itineraries` (generate and save) still requires login. Visitors see a "This plan isn't saved yet" banner. "Save this plan" opens sign-up with `stay: true`, so they stay on the Plan page instead of going to onboarding, and the save runs automatically once they're logged in. *Why:* the planner is the product's best demo, so it shouldn't sit behind a sign-up wall. Saving re-runs the identical request, and because the planner is deterministic (D19) the saved plan matches what they were looking at. *Cost:* a public compute endpoint. It's cheap (cached places, at most 7 days), but it should get gateway rate limiting before production.


**D36. Email ownership is verified with a one-time code before an account works.** *(Storage, delivery and response rules revised by D38. The flow below still holds.)*
`register` creates the user with `email_verified = false`, stores a **BCrypt hash** of a 6-digit `SecureRandom` code (never the code itself), and emails it. No token is issued until `POST /api/auth/verify` succeeds. Login to an unverified account returns `403` with `code: EMAIL_NOT_VERIFIED`; the UI then re-sends a code and shows the code step. *Why:* the `@Email` check only validates format. Sending a code is the only way to prove someone controls the inbox. Rules and why:
- **10-minute expiry** and **5 attempts per code.** At most 5 guesses out of 1,000,000, so brute force is impractical. Wrong guesses are counted even though the request fails (`@Transactional(noRollbackFor = ApiException.class)`).
- **60-second resend cooldown**, so the endpoint can't be used to spam someone's inbox.
- **`resend-code` always returns 202** and says nothing about whether the email has an account (no account enumeration). The `EMAIL_NOT_VERIFIED` hint is only given after a correct password.
- **Unverified addresses can be re-registered.** Whoever proves ownership gets the account, so nobody can squat on someone else's email by signing up first.
- The account and code are saved in one transaction, and the email is sent **after commit**, so a slow mail server never holds a DB transaction. If sending fails, the user can resend.
- Accounts created before this change were marked verified by migration `V3`.

**D37. Email goes out over plain SMTP; Mailpit catches it locally.** *(Verification emails are now sent by an async worker, per D38.)*
`SmtpEmailSender` (Spring `JavaMailSender`) now sends both verification codes and booking emails. In dev it points at Mailpit from docker-compose, so every email is visible at http://localhost:8025 without a real provider. For real inboxes, only env vars change: `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH=true`, `MAIL_STARTTLS=true` and `MAIL_FROM`. That works with Gmail (an app password), Amazon SES SMTP, SendGrid, Resend and others. `MAIL_ENABLED=false` falls back to logging emails, codes included. The mail health check is disabled so a mail outage doesn't mark the API down. *Cost:* sign-up emails are sent synchronously in the request (bounded by 5 s SMTP timeouts). At scale they'd move onto the `notifications` topic like booking emails.


**D38. Verification codes live in Redis and are emailed by an idempotent async worker.** *(Codes in Kafka messages are now encrypted, D57.)*
This replaces the Postgres-stored, synchronously emailed codes of D36 and D37 (migration `V4` drops the table).

| Requirement | How it's met | Where |
|---|---|---|
| Cryptographically secure | `SecureRandom.nextInt(1_000_000)`, uniform 000000–999999. Job IDs are `UUID.randomUUID()`, also from a CSPRNG. | `EmailVerificationService` |
| Stored in Redis, never in plain text | `otp:{email}` hash `{hash, attempts, id}`. `hash` = HMAC-SHA256(`OTP_SECRET`, email:code). | `OtpStore` |
| Expires automatically | Redis TTL of 600 s on the code and 60 s on the resend cooldown. No cleanup job is needed. | `OtpStore` |
| API doesn't wait for the email provider | Register and resend write to Redis, publish an `EmailJob` to the `emails` topic and return **202**. Consumer group `email-worker` does the SMTP call. | `AuthService`, `EmailWorker` |
| Atomicity | Two Lua scripts that Redis runs atomically. **ISSUE**: claim the cooldown with `SET NX EX`, replace the code, set the TTL. **VERIFY**: compare, `HINCRBY attempts`, `DEL` on success or on the 5th miss. | `OtpStore` |
| Max 5 attempts | Counted inside VERIFY; the 5th miss deletes the code. Concurrent guesses can't get past the counter. | `OtpStore` |
| Single use | The successful comparison and the `DEL` happen in the same script. Test: 8 parallel correct submissions give exactly one 200. | `OtpStore`, `BookingFlowIT` |
| No account enumeration | Register always returns 202 with the same message. A registered address gets an "account exists, just log in" email instead of a code, and the password is hashed on every path so response time is similar. Resend always returns 202. Every verify failure (wrong, expired, used, burned, unknown email) returns the same 400 "That code is invalid or has expired." `EMAIL_NOT_VERIFIED` is only returned after a correct password. | `AuthService`, `EmailVerificationService` |

**At-least-once delivery and idempotency.** Kafka can redeliver a job, for example when the worker sends the email and then crashes before its offset is committed. The worker keeps a per-job state machine in Redis, `email:job:{jobId}`:
- **Claim:** `SET NX` with a 30 s lease value `sending:{token}`.
- **Duplicate after success:** the key reads `sent` (kept 24 h), so the job is skipped.
- **Lease held elsewhere:** throws `EmailInFlightException`, which the Kafka error handler retries every 5 s up to 12 times, outlasting the lease.
- **SMTP failure:** the worker releases its own lease (Lua compare-and-delete on the token) and rethrows, so the job is retried and eventually dead-lettered.
- **Stale jobs:** a code job is skipped unless its `jobId` still equals the current `otp:{email}.id`. A replaced, used or expired code is never emailed.
- **The unavoidable window:** a crash after SMTP accepts the email but before the key is set to `sent`. The lease then expires and the email goes out once more. Exactly-once delivery is impossible over SMTP. What we make idempotent is the *effect*: the duplicate carries the same code and the same `Message-ID` (`<jobId@wanderly.local>`), and the code's state in Redis (validity, attempts) is untouched.

*Trade-offs:*
- **HMAC instead of BCrypt** for the code, because the comparison must run inside Lua to stay atomic, and that needs a deterministic hash. Brute force is already capped at 5 guesses in 10⁶.
- **The code is plain text in the Kafka message.** That's why the `emails` topic has 1-hour retention and the code dies after 10 minutes or one use. Encrypting the field is the upgrade path.
- **Generic errors cost some convenience:** users don't see "N attempts left".
- **Redis is now required for sign-up.** Elsewhere it's an optional cache.


**D39. Places come from curated data where we have it and from OpenTripMap everywhere else.** *(Superseded by D44–D45: no key is needed now. OpenTripMap remains an optional live source when a key is set.)*
`PlacesService` routes each search by location. Within 25 km of a curated place (Bengaluru, Jaipur), the hand-checked dataset is used. Elsewhere it uses `OpenTripMapPlacesProvider`, which is only enabled when `OPENTRIPMAP_API_KEY` is set. Without a key, other cities return no places, and the UI greys them out using `GET /api/places/coverage`. Details:
- OpenTripMap kinds map to our categories in a fixed priority order (museums before historic before natural…).
- The rating maps from OpenTripMap's `rate` (1–3, "h" for heritage) onto 3.65–4.95.
- Duplicate names are dropped (the same site often appears as both a node and a building).
- Places tied to an OSM object get the stable id `osm-<type>-<id>`.

The live place list is cached for 24 h on the ~1 km grid (D17). *Why OpenTripMap:* it's free, covers all of India and is built on OSM and Wikidata. Google Places has better ratings and hours but needs billing. *Cost:* coverage and ratings are uneven, and the provider mapping is only tested against a recorded response until a key is configured.

**D40. Real opening hours from OpenStreetMap (Overpass), parsed into a weekly model, applied after the cache.**
- **Model:** each `Place` has a typical daily window, `closedOn` weekdays, and a `hoursSource` of `curated`, `osm` or `estimated`. The UI shows "Closed Mon" and "Hours from OpenStreetMap" or "Hours estimated".
- **Planner:** never schedules a place on its closed weekday. It retries the place on another day, and lists it as unscheduled if no day fits.
- **Parser (`OpeningHours`):** covers the common OSM subset: `24/7`; weekday lists and ranges, including wrap-around; later rules override earlier ones; split ranges; past-midnight and `24:00`; open-ended `18:00+`; `off`. Holiday tokens are ignored. Anything else (months, sunrise, "by appointment") is **rejected rather than guessed**, which falls back to a category estimate.
- **Reliability:** public Overpass servers are often overloaded. During this build the main server returned 504 for minutes at a time, and the mirrors timed out from this network. So:
  - hours are cached per OSM object for 7 days, read with one `MGET`;
  - each search sends one batched query, one request at a time;
  - failover runs across `OVERPASS_URL` servers, 10 s each and 15 s total;
  - a 2-minute circuit breaker follows any failure;
  - failures are never cached.
- **Hours are applied after the places-list cache** (`OsmHoursEnricher`), so an outage can't freeze estimates into the 24 h cache. Hours appear on a later request once Overpass recovers.

*Cost:* the first search in a new area can take a few extra seconds. The typical window simplifies days with different hours.

**D41. City search: a bundled list while typing, Nominatim only on request.** *(Replaced by D43: Photon gives true type-ahead with no account. The bundled list stays as the instant tier.)*
Typing searches `cities/india.json` (85 destinations, geocoded once via Nominatim) in memory: instant and with no external calls. It matches name prefixes, then substrings, then state names ("raj" finds the Rajasthan cities). For anything else, the dropdown offers "Search “…” across India", which makes **one** Nominatim call. *Why:* Nominatim's usage policy forbids search-as-you-type and caps clients at 1 request/second with an identifying User-Agent. We comply with:
- an explicit action only;
- a server-side throttle of at least 1.1 s between calls;
- a User-Agent of `Wanderly/0.1` plus `OSM_CONTACT`;
- 30-day caching of every answer.

Results are restricted to India (`countrycodes=in`, `featureType=settlement`). Each city is flagged `curated` when it's covered by the hand-made data. Explore, Discover and Plan all take any city, and a city without events links to Discover and Plan for it. *Upgrade path:* Geoapify or Photon for true type-ahead geocoding.

**D42. OpenStreetMap attribution is shown in the footer.** *(Updated in D46.)*
OSM data is licensed under ODbL, which requires visible credit: "Places © OpenStreetMap contributors (ODbL) via OpenTripMap · Geocoding by Nominatim", with a link to osm.org/copyright.


**D43. Photon for city search-as-you-type (no API key), on top of the instant bundled list.**
The user asked for providers that need no account. Photon (komoot, built on OpenStreetMap) allows type-ahead, which Nominatim forbids, and needs no key. Two tiers are shown together in one dropdown:
1. **Instant:** `GET /api/cities` filters the 85 bundled destinations in memory. Ranking is name prefix, then common old names (Bangalore, Bombay, Pondicherry, Ooty, Cochin, Trivandrum, Allahabad…), then substring or state.
2. **Everything else:** `GET /api/cities/suggest` asks Photon. It's limited to India's bounding box and then filtered to `countrycode = IN` (the box also covers parts of neighbouring countries; a Bangladeshi village came back in testing). Results are restricted to `place=city|town|village` and ranked city > town > village, because Photon alone put the village "Ud" above Udaipur. Duplicates are removed, at most 6 are returned, and answers are cached 30 days in Redis.

*Why:* in testing, Photon took **8–12 s per query** from this network, far too slow to block typing on. So the bundled matches render at once, Photon results are appended when they arrive with a "Searching all of India…" row, requests are debounced (300 ms, at least 2 characters), and superseded requests are **aborted** (`AbortController`) so a slow old answer can never overwrite a newer query. Fair use: at most 2 concurrent Photon calls from the server, an identifying User-Agent, and caching. If Photon fails, the tier simply returns `[]` and isn't cached; the instant tier still works. The Nominatim lookup endpoint was removed.

*Note:* city search only finds the city. Its places still need OpenTripMap (`OPENTRIPMAP_API_KEY`) outside the curated cities. A no-key alternative, places straight from Overpass, is noted under *Not built yet*.


**D44. No-key places: OpenStreetMap via Overpass, pre-fetched for popular cities.**
The user didn't want accounts or cards (OpenTripMap needs sign-up; Google needs billing). The only no-account place data comes from OpenStreetMap and Wikimedia.
- **`OverpassPlacesProvider`** maps OSM tags to our categories in priority order (a museum inside a fort is a museum; a historic temple is religious). Places of worship and parks must have a Wikidata link, otherwise every street shrine and pocket park floods the results. Restaurants are only queried when food is asked for. `name:en` is preferred, because many Indian places are tagged in local script. Hours come from `opening_hours` in the same answer.
- **Ranking without reviews:** a tag-based notability score (Wikipedia link, heritage, tourism tag, completeness). Where page views are known (D45), they're used instead. The UI shows a "Notable" badge, never stars.
- **Reliability is the hard part.** While building this, public Overpass returned **429, 504 and 27-second query timeouts** for long stretches, and mirrors were unreachable from this network. It also reports timeouts as **HTTP 200 with an error `remark`**, which `OverpassClient` now treats as a failure. Mitigations:
  - bounding-box queries instead of `around:` (much cheaper);
  - a shared client with at most 2 concurrent requests, failover, 12 s per server and a 2-minute circuit breaker;
  - **pre-fetching the bundled destinations** with `tools/fetch_osm_places.py` into `places/osm-cities.json`. The script is polite (one request at a time, backoff, resumable). It stores raw OSM tags, which are interpreted at startup by the same Java parser as live data. Those cities are served from memory: about 30 ms, no network.
- **Routing, most reliable first:** curated → bundled OSM → live open data (D45).

*Cost:*
- The bundle is a snapshot; rerun the script to refresh it. Because Overpass rate-limits it, a full build of 83 cities takes hours; the app uses whatever is bundled.
- The ODbL licence applies to the bundled data: attribution is shown, and changes to the database itself would have to be shared alike.

**D45. Wikipedia as the fast, reliable sights layer; page views as popularity; hours fetched in the background.**
For cities outside the bundle, sights come from Wikipedia: `generator=geosearch` plus coordinates, descriptions, Wikidata ids and 30-day page views in one query, about 1 s and consistently available.
- **Filtering:** geosearch returns every article with coordinates (the city itself, constituencies, stations, colleges), so places are classified by their **short description first, then the title**. "Jag Mandir" alone reads as a temple, but its description says palace. Non-sights are excluded. The 10 km radius cap is accepted.
- **Popularity:** page views map onto 3.2–4.8 on a log scale (about 100 → 3.8, 2k → 4.3, 100k+ → 4.8). The bundle build also attaches page views via Wikidata → enwiki title, so **the Taj Mahal (117,872 views a month) ranks above roadside statues** that only have a Wikidata link. That was observed before the fix.
- **Page views arrive over several `continue` rounds.** Not following them made Jagdish Temple score as if it had 0 views. Both the client and the script merge all rounds. The script also never records a failed lookup as "0 views", which was a bug found during the Wikimedia 429s. It paces requests at about 1 per second and honours `Retry-After`.
- **Food and nightlife** for those cities come from Overpass (`OpenDataPlacesProvider`). If Overpass fails, the sights still return, and the **partial result isn't cached**, so it's retried next time.
- **Hours never block a request.** `OsmHoursEnricher` returns cached hours instantly and fetches misses on a virtual thread, by OSM id, or by Wikidata id inside a bounding box. The next request gets real hours. This took Udaipur's first search **from 37 s to 4.4 s**. Real-world `opening_hours` like seasonal `week 08-36 …` rules are rejected by the parser and stay "estimated". Only a minority of Indian sights have hours tagged in OSM, so that label matters.

*Cost:* Wikipedia has few articles around very small towns (Ziro returned none within 8 km). The UI then suggests a bigger radius.

**D46. Footer credits match the sources actually used.**
"Places © OpenStreetMap contributors (ODbL) and Wikipedia (CC BY-SA) · City search by Photon". The earlier text still named OpenTripMap and Nominatim after they'd stopped being used; the browser screenshot check caught it.


**D47. No payment gateway: Wanderly is a planner.**
The user decided against F4 (Stripe). Booking stays a confirmed reservation with no checkout, and travel (F8) and stays (F9) link out to check and book elsewhere. The `pending` booking status (D7) is left unused rather than removed, in case it's ever wanted. *Superseded by D51: booking itself is gone.*

**D48. F8 Getting there: real distances, simulated fares, parallel fan-out.**
`TravelService` fetches the road route (OSRM) and the nearest airport at each end **in parallel** (`CompletableFuture` on virtual threads, the design doc's fan-out), then the pure `TravelPlanner` builds the options:
- **Flight:** only over 300 km with an airport within 120 km at both ends (116 airports from OurAirports, a major airport preferred). 1,500 + 4.2/km, ×1.9 within 2 days down to ×1.0 three weeks out, +12% on Fri/Sun.
- **Train:** about road × 1.05 at 58 km/h. Sleeper, AC 3-tier and AC 2-tier at about 0.45, 1.25 and 1.80 per km; +30% within 2 days (Tatkal-style). Not offered to hill or island destinations without a railhead (Leh, Port Blair, Manali, Gangtok…).
- **Bus:** under 1,200 km, road time × 1.3; non-AC and AC sleeper.
- **Car:** fuel + tolls per car (4 seats), with a one-way cab estimate in the notes.

Door-to-door time includes airport and station access and check-in buffers, so modes compare fairly. Badges compare per-person cost (car split across occupants), time and CO₂. Timetables are deterministic per route and date. *Why simulate:* Indian rail, bus and airline fare feeds are paid partner APIs, which the design doc anticipated. Everything simulated is labelled "indicative". Road routes are cached 30 days, plans 1 hour. OSRM's public server is a demo: one call at a time, fall back to an estimate (flagged) on failure, self-host in production. Live result: Delhi → Udaipur, 671 km by road, flight about 5 h door to door from about ₹4–5k, train from ₹360 (Cheapest, Greenest).

**D49. F9 Where to stay: real places from Photon, tiered and priced honestly.**
Overpass was too busy for hotel queries, so Photon (OSM, no key) runs three searches in parallel (hotel, guest house, hostel, filtered by `osm_tag=tourism:*`) within about 6 km, cached 7 days. OSM has no rates, so `HotelPricing` estimates them per night:
- base by tier, ±25% per property, ×1.3 in metros and ×1.15 in leisure hotspots;
- +15% on Fri/Sat nights and +15% from October to March;
- one room per two guests.

**Tiering lesson:** the first rule treated "Palace" as luxury, which labelled "Palace View Guest House" and "Hotel Rani Palace" as luxury. "Palace" is very common in ordinary Indian hotel names. Now the OSM type wins (guest houses and hostels are always budget), and only chains (Taj, Oberoi, Leela, Lalit, ITC…) or resort/spa/heritage count as luxury. Results matching the user's budget preference come first, with "show all" to see the rest. Each card links to Google Maps and OpenStreetMap.

**D50. F12 Analytics: Kafka → S3 lake → SQL, with idempotent, lossless ingestion.**
- **Ingest:** a new consumer group, `analytics-sink`, uses a batch listener (up to 1,000 records or about 5 s) to write NDJSON to `s3://wanderly-lake/raw/{topic}/dt=YYYY-MM-DD/hour=HH/part-{partition}-{firstOffset}.ndjson`. The Hive layout lets Athena or DuckDB skip partitions. Names derived from partition and offset make **redelivered batches overwrite instead of duplicate**, and queries also de-duplicate by `eventId`. Offsets are committed only after the write. On failure the batch retries every 10 s **indefinitely**: analytics would rather lag than drop data, and this group's lag doesn't touch user-facing consumers. On first start it **backfilled the full Kafka history**.
- **Storage:** an S3 API via the AWS SDK, with SeaweedFS in docker-compose. MinIO's Docker images are no longer published. In production, `LAKE_ENDPOINT` is left empty and the SDK uses real S3 with the default credential chain.
- **Query:** DuckDB (embedded) reads the lake over S3 with `httpfs`: partition pruning on `dt`, `row_number()` de-duplication, aggregates for daily activity, event types, cities, interests, travel routes, most booked and the funnel. It's the local stand-in for Athena with the same SQL style. Results are cached 20 s. `/api/analytics/summary` is for admins: `ANALYTICS_ADMINS` emails, or any logged-in user when that's empty (dev only).
- **Funnel correctness:** the first version counted independent events, so "planned" (5) exceeded "explored" (3). It's now "reached **at least** this step", monotonic by construction (8 → 8 → 6 → 3 live), and asserted in tests.
- **Bugs found integrating with S3-compatible storage:**
  1. Batch retries are **silent by default**. The sink now logs every failed write before rethrowing.
  2. AWS SDK ≥ 2.30 sends **CRC checksums with aws-chunked uploads**, which SeaweedFS, MinIO and older Ceph reject. The fix is `requestChecksumCalculation(WHEN_REQUIRED)`.
  3. SeaweedFS rejects signed requests until **S3 identities are configured**: `infra/seaweedfs/s3.json` with local-only `dev/dev`.

  Once fixed, the sink caught up by itself with zero lag.
- **Dashboard:** stat tiles, activity per day (hover tooltips and a table view) and ranked bars. One data colour, `#008f9e`, which passed the palette validator's lightness, chroma and contrast checks. The theme's darker teal failed the chroma check (it reads as grey).

*Cost:* there's no curated Parquet zone or Glue catalog yet (AWS-only). Dates are UTC partitions. DuckDB downloads its `httpfs` extension on first use.

### Planner, not a shop (2026-10-06)

**D51. Booking is replaced by saving events.**
With no payments (D47) a "booking" was a seat reservation nobody paid for, which is a shop's concept. Wanderly is a planner, so you **save** an event to your plans instead. There are no seats, quantities, totals or refunds. `price` stays, as an indicative entry fee for budgeting ("Free" when 0).
- **Data:** `saved_events (user_id, event_id)`, unique per pair, cascading when either side is deleted. `V5` carries every confirmed event booking over as a save (duplicates collapse into one, cancelled ones are skipped), then drops `bookings`, the seat columns and trip prices. Booked trip packages have no equivalent and are dropped.
- **Idempotent in one statement:** `insert … on conflict do nothing` returns 1 or 0, so a double-click can't fail or publish twice; only a real change emits a message. Unsave is the same (`delete` returns the count).
- **Kafka:** the `bookings` topic becomes `saved-events`, keyed by **userId** (not by item, D11), so one user's save and unsave stay in order. The same consumers react: the notification consumer writes an **inbox-only** note with F7 picks (no email: the user just did it), affinity weighs `event.saved` like a booking (5.0), and the analytics sink lands it in the lake ("most saved" counts net saves).
- **Trip packages become trip ideas:** no price, just a place and a length; the card opens the planner with those values.
- **Feed popularity** (D22) is now how often an event is saved, relative to the most-saved candidate (ignored under 2 saves), instead of the fraction of seats sold.
- **F7 moved** to `GET /api/events/{id}/nearby`, which is public: it's about the venue, not about a purchase.

*Cost:* old `bookings` lake files stay where they are and aren't read any more; the funnel's last step is now "saved an event".

**D52. Itinerary v3: route and timetable are built together (time-window insertion, with lunch).**
The D18 pipeline ordered stops by distance and then checked opening hours, so a nearby place that opened at noon could start the day with two hours of waiting (seen live: day 2 of a Bengaluru plan waited for Toit while Cubbon Park and Lalbagh were dropped). Now `DayScheduler` does both at once:
- **Simulate** a route from the day start: travel, wait if early, visit; infeasible if a visit ends after closing time, after the day ends, or falls on a weekly closing day.
- **Insert** places best-score first, each where the day stays feasible and **finishes earliest** (then: least travel). Late openers naturally land late in the day. If the top picks can't fit, the next ones take their slots instead of the day coming up short.
- **Improve** with a time-aware 2-opt plus relocate (move one stop), accepting a change only if it stays feasible and finishes earlier or travels less. This is plain 2-opt's job when hours don't bind.
- **Spill-over** puts leftovers on whichever day absorbs them with the smallest delay, not just the first day with room.
- **Lunch:** each day has a break (relaxed 75, balanced 60, packed 45 minutes) that starts inside **12:00–14:00**: at the first gap from 12:00, or earlier if the next visit would otherwise run past 14:00. The break suggests the best-rated food place within 1.5 km of where you are, open for the whole break and not repeated across days (`rating − 0.5 × km`); otherwise "grab something nearby". The walk is counted inside the break.
- **Output:** `Day.lunch` (start, end, after which stop, suggestion) and `Stop.waitMin` when a wait can't be avoided. Plans saved earlier still load: the new fields are simply absent.

Still a heuristic, not OR-Tools VRPTW: days are at most 7 stops, so insertion plus local search is instant and deterministic, and it's explainable in an interview. *Cost:* one more places lookup per plan (food, cached), and no return-to-hotel leg yet.

**D53. Reminders run hourly and cover saved trips too.**
`ReminderScheduler` runs at the top of every hour (it was daily at 09:00):
- **Saved events** starting in the next 24 hours: "Coming up: …".
- **Saved itineraries** starting tomorrow (IST): "Tomorrow: your trip to …" with day 1's timetable, lunch included. Sent from 09:00 IST, so nobody is emailed at 3 am.

Each reminder's id is deterministic (`reminder:event:{user}:{event}`, `reminder:itinerary:{id}`) and is the notification's unique source id, so reruns, restarts and multiple instances deliver it once (D10), and ids already in the inbox aren't even re-queued. Hourly also means a missed run is caught up an hour later instead of a day later.

**D54. Anyone signed in can list an event, in any city.**
India has no public events API (BookMyShow and District have none), so the catalogue was stuck at three seeded cities. Organisers fill it instead: title, category (the same list as interests), city from the city picker, venue, start time (future, at most a year ahead), entry fee and description.
- **Location:** the venue is geocoded with Photon near the city's centre (best effort, cached 30 days, must be within 40 km); otherwise the event is pinned to the city centre. "Nearby" and the feed then work as for seeded events.
- **Ownership:** `events.created_by`. Only the lister or an admin can edit or remove an event; seeded events belong to nobody, so only admins can change them. Removing an event removes its saves (FK cascade).
- **Admins** generalise the analytics list: `ADMINS` (with `ANALYTICS_ADMINS` as a fallback). Empty means no admins. Analytics alone keeps the "open to any logged-in user when empty" dev behaviour.
- **Abuse limits:** at most 20 upcoming listings per user (admins exempt), field length caps, and categories from a fixed list. There's no moderation queue yet.

**D55. Forgot password reuses the sign-up code machinery and signs out other sessions.**
`POST /api/auth/forgot-password` always answers 202. Only verified accounts get a code, and the response is identical either way (no account enumeration, as in D37). The code lives under its own keys (`pwreset:{email}`, same 10 minutes, 5 attempts, single use and 60 s resend as D38), and its HMAC input is domain-separated, so a sign-up code can never reset a password. The email worker treats it like a sign-up code: idempotent, and stale codes are skipped.

`POST /api/auth/reset-password` hashes the new password first (the slow part), then consumes the code atomically, saves the hash and returns a fresh token. **Other sessions end:** JWTs are stateless, so the reset writes `auth:revoked-before:{userId}` = now (epoch ms) to Redis for one token lifetime, and the JWT decoder rejects older tokens. Tokens now carry `iat_ms` because the standard `iat` is whole seconds, too coarse to separate the token from just before a reset from the one issued by it. The check fails open if Redis is down, like every other Redis use, bounded by the 12-hour token TTL.

### Reliability and security hardening (2026-10-06)

**D56. Transactional outbox for messages that describe a database change.**
D9 published after commit, so a crash between the commit and the Kafka send lost the message (the save existed, but no inbox note, affinity or analytics). Now `Outbox.enqueue` writes the message to an `outbox` table **in the same transaction** as the change, and `OutboxRelay` sends it.
- **Atomic:** the change and its message commit or roll back together, without distributed transactions. `enqueue` requires a transaction (`Propagation.MANDATORY`), so a misuse fails loudly.
- **Fast path:** an after-commit hook nudges the relay, so the message usually leaves within milliseconds. A 1-second poll is the safety net for restarts and Kafka outages.
- **At-least-once:** a row is marked published only after Kafka acknowledges it (`acks=all`); a crash in between sends it again, and every consumer already de-duplicates by event id.
- **Order:** rows go out by id, one at a time, and a failure stops the batch, so a later message for a key never overtakes an earlier one.
- **Several instances:** `SELECT … FOR UPDATE SKIP LOCKED` splits pending rows between relays without duplicates or waiting.
- **Housekeeping:** published rows are deleted after 7 days; `attempts` and `last_error` show stuck messages.
- **Scope:** saves, unsaves and the activity events of saves and saved itineraries. Searches and views stay fire-and-forget (nothing in the database to be consistent with), codes depend on Redis rather than Postgres, and reminders already re-queue hourly until delivered (D53).

*Cost:* one extra insert per change and a polling query per second. CDC (Debezium reading the Postgres WAL) is the heavier alternative that removes the polling.

**D57. One-time codes are encrypted inside Kafka messages.**
Codes used to travel in plain text on `emails` (mitigated only by 1-hour retention). Now `CodeCipher` encrypts them with **AES-256-GCM**: a fresh 12-byte nonce per message, and the job id plus recipient as associated data, so a ciphertext can't be moved to another job or address. The key is derived from `OTP_SECRET` with HMAC-SHA256 under its own label, so there's nothing new to configure and it's independent of the key that hashes codes in Redis. The worker decrypts before taking its send lease; a bad ciphertext is an `IllegalArgumentException`, which is dead-lettered without retries.

**D58. Per-IP rate limits as Redis token buckets.**
Login, sign-up/resend/forgot (each sends an email), code entry (verify, reset) and the planner get per-client-IP buckets: 10 per 5 min, 5 per 10 min, 10 per 5 min and 30 per 5 min by default (`wanderly.rate-limit.*`). Over the limit: **429** with `Retry-After` and a problem-detail body (`code: RATE_LIMITED`).
- **Algorithm:** a token bucket in one Lua script (refill by elapsed time, take or compute the wait), atomic like `OtpStore`, shared by all instances, timed by Redis `TIME` so app-server clocks don't matter. Bucket4j would do the same; the ~15-line script avoids a dependency and matches the existing pattern.
- **Client IP:** `request.getRemoteAddr()`, with `server.forward-headers-strategy=native` so Tomcat takes `X-Forwarded-For` **only from internal addresses** (a local proxy or private load balancer); clients can't spoof theirs. The Vite dev proxy forwards it (`xfwd`).
- **Fails open** if Redis is down, like every other Redis use.
- **Testing lesson:** the first test saw no 429 because Apache HttpClient honours `Retry-After` and silently waited 30 s and retried. That test uses the JDK client.

**D59. The browser session is an httpOnly cookie, with a custom-header CSRF guard.**
D32's JWT in `localStorage` could be read by any script, so an XSS bug could steal a 12-hour token. Now login, verify and reset-password also set `wanderly_session`: **httpOnly**, `SameSite=Lax`, `Path=/api`, `Secure` when `COOKIE_SECURE=true`, expiring with the token. The web app never sees the token; on load it asks `/api/users/me` who it is.
- **CSRF:** cookies are sent automatically, so on writes the cookie only counts when the request also has `X-Requested-With`. Another site can't add a custom header without a CORS preflight, which this API never approves; with `SameSite=Lax` that's the standard defence, so Spring's CSRF tokens stay off.
- **API clients** still use `Authorization: Bearer` (it wins over the cookie), and the token stays in the login response for them.
- **Logout** is `POST /api/auth/logout` (scripts can't delete an httpOnly cookie). A 401 caused by an expired or revoked cookie also deletes it, so the browser recovers on its own; auth endpoints ignore the cookie so logging in always works.
- The old `localStorage` token is removed on first load.

---

## Not built yet

| Item | Design ref | Why deferred |
|---|---|---|
| Python ML job writing `recs:{userId}` | §12, Phase 2 | **Out of scope by choice.** The content-based score is the feed (D22, D51). The serving contract (D24) remains if it's ever wanted. |
| Payments (F4) | §10 | **Out of scope by choice** (D47, D51). |
| Cognito, Terraform, ECS/ECR deploy, CloudWatch, Glue/Athena | §7, §14, §16 | **Out of scope by choice:** no paid AWS. Local equivalents: JWT, docker-compose, SeaweedFS + DuckDB. A free-tier host (Render, Fly.io, Neon, Upstash) is the deploy path. |
| OR-Tools VRPTW, return-to-hotel leg | §11, D52 | The heuristic scheduler covers time windows and lunch; an exact solver and the evening leg back are refinements. |
| Notify savers when an event is edited or removed | D54 | Today a removed event silently disappears from their plans. It would be a Kafka message from the catalog. |
| Moderation for user-listed events | D54 | Per-user caps exist; a report button or admin review queue doesn't. |
| Live fare and hotel-rate feeds | D48, D49 | Paid partner APIs (rail/bus aggregators, airline GDS, hotel channel managers). The service seams are in place. |
| Per-account lockout and CAPTCHA | D58 | Per-IP limits exist; a distributed attack from many IPs on one account isn't throttled per account yet. |
| Outbox via CDC (Debezium) | D56 | Replaces polling with the Postgres WAL; heavier infrastructure. |
| CI workflow, Maven wrapper | D29 | No `.github/workflows` file exists yet. |

---

## Build environment note

- 2026-09-30: scaffold built. `mvn test` passed (20/20 unit tests).
- 2026-10-05: first live run on the dev Mac (JDK 21, Maven 3.9.9, Docker 29.8). `docker compose up -d` and `mvn spring-boot:run` both work, the 2 Flyway migrations apply, and startup takes about 4.5 s. A smoke test through the real API passed, with 0 ERROR lines in the log:
  - register, preferences and event search
  - booking, which leaves 23 seats
  - the Kafka-delivered confirmation, including the nearby picks
  - overbooking: 10 + 10 seats succeed, and the next 10 get a 409
  - nearby places, a 2-day itinerary, the fallback feed, and Swagger UI
- 2026-10-05: `mvn verify` passed for the first time: 20 unit tests plus 5 end-to-end tests in `BookingFlowIT` on real Postgres, Kafka and Redis (Testcontainers). Those cover booking fan-out, overbooking, consumer idempotency, itinerary generation, public places, and email verification (blocked login, wrong code, right code). This required pinning Testcontainers 1.21.4, because the 1.19.8 version Boot 3.3 ships is rejected by Docker Engine 29 with a 400 error.
- 2026-10-05: email verification redesigned (D38): codes in Redis, an async idempotent worker, uniform responses. `mvn verify` gives 20 unit + 10 end-to-end, all passing. Live checks:
  - register returns 202 in about 0.4 s, without waiting for SMTP
  - Redis holds only the HMAC hash, with TTLs of 600 s and 60 s
  - the worker state key reads `sent`
  - reusing a code gets the generic 400, and the key is deleted after use
  - re-registering an existing email gives a response identical to a new sign-up
  - the browser flow (sign up → Mailpit code → verify → onboarding) works with no console errors
- Frontend checks (headless Chromium, every change): sign-up and onboarding, personalised feed, booking with nearby picks, My trips, itinerary, Discover "All of India", Inbox, the mobile layout, the city combobox keyboard flow, anonymous plan → save after sign-up. Bugs fixed along the way: hero clipping the dropdown, raw ISO timestamps in emails, the sticky header covering the save banner, and a misleading "new code sent" message during the cooldown.
- Dev machine setup (macOS, low disk at first): JDK 21 in `~/Library/Java/JavaVirtualMachines/temurin-21.jdk`, Maven 3.9.9 in `~/.local/apache-maven-3.9.9`, both on PATH via `~/.zshrc`. Homebrew installs failed until disk space was freed.
- 2026-10-05: more cities (D39–D42). `mvn verify` gives 35 unit + 11 end-to-end tests, all passing. The opt-in live Overpass test passed against real OSM data: CSMVS museum `Tu-Su 10:15-18:00` became 10:15–18:00 closed Monday; Leopold Cafe `Mo-Su 07:30-01:00` became open until midnight. Earlier the same day Overpass returned 504 for several minutes, which shaped D40's failover and circuit breaker. Live checks:
  - city search "ud" → Udaipur, Puducherry; "raj" → the Rajasthan cities
  - Nominatim lookup "Ziro" → Arunachal Pradesh, 3 s first time and 8 ms cached
  - Bengaluru stays curated
  - without a key, other cities return no places and are greyed out in the UI
  - browser flows (Plan, Explore→Mumbai links, Discover "All of India", footer attribution) work with no console errors
  - **Live OpenTripMap data is pending an API key.**
- 2026-10-05: Photon city search (D43). `mvn verify` gives 39 unit + 11 end-to-end tests, all passing. The end-to-end test caught a ranking bug: the alias "Udhagamandalam" put Ooty above Udaipur for "ud", so real names now rank before aliases. Live checks:
  - "pondi" → Puducherry and "bangal" → Bengaluru, instantly
  - Photon "zir" → Indian results only (Ziro first); "ud" → Udaipur, Udupi, Udhampur…
  - cached repeats in 9 ms
  - in the browser, "Searching all of India…" shows while Photon works, fast typing never shows stale results, and there are no console errors
- 2026-10-05/06: no-key places (D44–D46). `mvn verify` gives 48 unit + 11 end-to-end tests, all passing. Live results:
  - **Chittorgarh** (not bundled): search via Photon → plan in 7.4 s → Kalika Mata Temple → Samadhishvara Temple → Vijaya Stambha → Kirti Stambha → Bhojunda Stromatolite Park.
  - **Udaipur** (live): Jagdish Temple, Lake Palace and Lake Pichola top the list. The first search took 4.4 s (37 s before background hours) and repeats about 40 ms. Real OSM hours appear for the Car Museum (09:00–21:00) and Sajjangarh Biological Park.
  - **Agra** (bundled): Taj Mahal, then Agra Fort, in about 30 ms; restaurants carry OSM hours.
  - **Bundle status:** about 11 of 83 cities fetched so far (Overpass rate limits). Rerun `python3 tools/fetch_osm_places.py`, which resumes, and restart the backend to load more.
- 2026-10-06: F8, F9 and F12 (D47–D50). `mvn verify` gives 60 unit + 11 end-to-end tests, all passing. Live checks:
  - Delhi → Udaipur travel options in 1.2 s with a real OSRM distance
  - 24 Udaipur stays in 2.2 s
  - the browser flow (plan → Getting there with Delhi → Where to stay with the Luxury filter) shows no refetch loops and no console errors
  - two fresh searches reached the dashboard in about 12 s; the lake backfilled 7 files from Kafka history with zero lag

  Also fixed today:
  - the bundle script no longer aborts when one city keeps failing (15 cities bundled now, with page views);
  - `.env` lines that `source` couldn't parse (an unquoted app password with spaces, `MAIL_FROM` with `<…>`).
- **Observed itinerary weakness (D21):** a 2-day Bengaluru plan started day 2 at 12:00 (waiting for Toit to open) and crossed the city, while Cubbon Park and Lalbagh went unscheduled. This is the ordering-then-scheduling limitation in practice. The fix is a time-window-aware insertion scheduler.
- 2026-10-06: planner, not a shop (D51–D55). Bookings became saved events, trip packages became trip ideas, events can be listed in any city, forgot password, hourly reminders including trips, and the v3 scheduler with lunch. `mvn verify` gives 68 unit + 14 end-to-end tests, all passing, with `V5` applied by Flyway on Testcontainers Postgres. Checks:
  - `V5` against V1–V4 data with real bookings: two confirmed bookings of one event became one save, the cancelled one was skipped, and `bookings` was dropped
  - new backend on an isolated stack (separate Postgres, Redis and Kafka containers, so the running dev app and its consumer groups were untouched) plus the built frontend in headless Chromium: sign-up, save an event (card shows "Saved", inbox note with nearby picks), list an event in Udaipur, a 2-day Bengaluru plan, My trips → open a saved plan, forgot password → new password, and a 3-day Jaipur plan at phone width (no horizontal scroll). No console errors.
  - The Bengaluru plan that used to wait for Toit (D52) now has Cubbon Park and Lalbagh on day 1 with lunch at MTR 0.5 km away, and Toit at 12:10 on day 2 with no waiting.
- 2026-10-06: hardening (D56–D59): outbox, encrypted codes, rate limits, cookie sessions. `mvn verify` gives 73 unit + 17 end-to-end tests, all passing, with `V6` applied. Browser check on an isolated stack: an old localStorage token is removed; the session cookie is httpOnly, SameSite=Lax, Path=/api and invisible to `document.cookie`; the session survives a reload; saving works (cookie + CSRF header); logout deletes the cookie; logging back in works. Redis held the rate-limit buckets, and the outbox held 2 rows (the save and its activity), both published.
