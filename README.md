# Wanderly

Trip planner for India: a Java/Spring Boot backend that is event-driven with Kafka, has a time-window-aware itinerary optimiser and email-verified sign-up, and personalises recommendations, plus a React web app. It's a planner only: nothing is sold or booked here.

- **Design:** [trip-event-platform-design.md](trip-event-platform-design.md) (the full plan)
- **Decisions and features built so far:** [DECISIONS.md](DECISIONS.md)

## What works today (Phase 0 + Phase 1 "spine", parts of Phase 2, and a React web app)

| Feature | Endpoint |
|---|---|
| F1 Sign up with email verification / login / forgot password / preferences | `POST /api/auth/register`, `POST /api/auth/verify`, `POST /api/auth/resend-code`, `POST /api/auth/login`, `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`, `GET /api/users/me`, `PUT /api/users/me/preferences` |
| F2 Search events and trip ideas | `GET /api/events?city=&category=&from=`, `GET /api/events/{id}`, `GET /api/trips` |
| List an event in any city (organisers; owner or admin can edit/remove) | `POST /api/events`, `PUT /api/events/{id}`, `DELETE /api/events/{id}`, `GET /api/events/mine` |
| F3 Save events to your plans (Kafka fan-out) | `PUT /api/saved-events/{eventId}`, `DELETE /api/saved-events/{eventId}`, `GET /api/saved-events` |
| F5 Nearby tourist spots for any Indian city (no API key), personalised, with OSM opening hours | `GET /api/places/nearby?lat=&lng=&radiusKm=&category=`, `GET /api/places/coverage` |
| City search across India (type-ahead, no key) | `GET /api/cities?q=` (instant, bundled list), `GET /api/cities/suggest?q=` (any city, town or village via Photon) |
| F6 Day-by-day itinerary (k-means, time-window insertion with a lunch break, time-aware 2-opt) | `POST /api/itineraries/preview` (public, not saved), `POST /api/itineraries` (save), `GET /api/itineraries` |
| F7 Food and attractions near an event | `GET /api/events/{id}/nearby` (public; also in the "Saved" inbox note) |
| F8 Getting there: flight, train, bus and car with door-to-door time and indicative fares | `GET /api/travel` |
| F9 Where to stay: real hotels near the destination with indicative prices | `GET /api/hotels` |
| F10 Personalised feed | `GET /api/recommendations/feed` |
| F11 Notifications and reminders (saved events within 24 h, trips the day before) | `GET /api/notifications` (written by the Kafka consumer) |
| F12 Analytics: Kafka → S3 data lake → SQL (DuckDB) → dashboard | `GET /api/analytics/summary` (admins) |

```
React app (:5173) ─/api─▶ Spring Boot (modular monolith) ─▶ Postgres (source of truth)
                             │   └─▶ Redis (places cache, user affinity, precomputed recs,
                             │              one-time codes + email-job state, via atomic Lua)
                             └─ async ─▶ Kafka: saved-events / user-activity / notifications / emails / dead-letter
                                           ├─▶ notification-service   → inbox notes + reminder emails (SMTP)
                                           ├─▶ email-worker           → sign-up and password-reset codes, idempotent under redelivery
                                           ├─▶ recommendation-service → affinity:{userId} in Redis
                                           └─▶ analytics-sink         → S3 data lake (SeaweedFS locally) → DuckDB SQL → dashboard
```

## Run it locally

Prerequisites: Docker, JDK 21, Maven 3.9+, Node 18+.

```bash
docker compose up -d                       # Postgres, Redis, Kafka (KRaft), Kafka UI :8081, Mailpit :8025, SeaweedFS (S3) :8333
cd backend && mvn spring-boot:run          # API on :8080; Flyway creates the schema and seeds demo data
cd frontend && npm install && npm run dev  # in a second terminal: the web app on :5173
```

Open **http://localhost:5173**. That's the Wanderly web app: sign up, pick your interests, save events, plan a trip, list an event. The dev server forwards `/api` to the backend.

**Any city in India, no API keys:**
- **Bengaluru and Jaipur** use curated data.
- **Popular destinations** use OpenStreetMap data bundled with the app.
- **Anywhere else** uses Wikipedia (sights, ranked by page views) plus OpenStreetMap (food, opening hours), fetched live.

To refresh or extend the bundled data (slow and polite to Overpass, resumable):

```bash
OSM_CONTACT=you@example.com python3 tools/fetch_osm_places.py   # then restart the backend
```

Optional: set `OPENTRIPMAP_API_KEY` to use OpenTripMap as the live source instead.

For developers:
- **Swagger UI** (http://localhost:8080/swagger-ui.html) is an interactive page for every endpoint. To get a token: `POST /api/auth/register`, find the 6-digit code in Mailpit, `POST /api/auth/verify` with it, copy the `accessToken`, click **Authorize** and paste it in.
- [docs/api.http](docs/api.http) has the same requests for VS Code REST Client or IntelliJ.
- **Mailpit** (http://localhost:8025) is a local inbox for every email the app sends: sign-up and password-reset codes, and reminders.
- **Kafka UI** (http://localhost:8081) shows the `saved-events`, `user-activity` and `emails` topics filling up as you use the app.

To run everything, including the backend, in containers, use `docker compose --profile app up -d --build`.

### Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local compose | Postgres |
| `REDIS_HOST`, `KAFKA_BOOTSTRAP` | `localhost` | Infra endpoints |
| `JWT_SECRET` | dev value | HS256 signing key (at least 32 bytes). **Override outside local dev.** |
| `OTP_SECRET` | dev value | HMAC key for verification codes stored in Redis (at least 32 bytes). **Override outside local dev.** |
| `PLACES_LIVE` | `true` | `false` = no live place lookups (curated and bundled data only) |
| `OPENTRIPMAP_API_KEY` | — | Optional: use OpenTripMap as the live places source instead of Wikipedia + Overpass |
| `WIKIPEDIA_API_URL` | `https://en.wikipedia.org/w/api.php` | Sights and page-view popularity (no key) |
| `OVERPASS_URL` | 3 public servers | Comma-separated Overpass servers for OpenStreetMap opening hours, tried in order |
| `PHOTON_URL` | `https://photon.komoot.io` | OpenStreetMap geocoder for city search-as-you-type (no key) |
| `OSM_CONTACT` | — | Your email or site, added to the User-Agent as the OpenStreetMap and Wikimedia usage policies ask |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` (Mailpit) | SMTP server. For real email, e.g. `smtp.gmail.com` / `587` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | — | SMTP login (for Gmail, use an [app password](https://myaccount.google.com/apppasswords)) |
| `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` | `false` | Set both to `true` for real providers |
| `MAIL_FROM` | `Wanderly <no-reply@wanderly.local>` | Sender address (must be allowed by your provider) |
| `MAIL_ENABLED` | `true` | `false` = print emails, including codes, to the log instead of sending |
| `OSRM_URL` | public OSRM demo | Road distance/time for travel options; empty = straight-line estimate |
| `LAKE_ENDPOINT` | `http://localhost:8333` (SeaweedFS) | S3 endpoint for the analytics lake; **empty = real AWS S3** |
| `LAKE_BUCKET` / `LAKE_REGION` / `LAKE_ACCESS_KEY` / `LAKE_SECRET_KEY` | `wanderly-lake` / `us-east-1` / `dev` / `dev` | Lake location and local credentials |
| `ANALYTICS_ENABLED` | `true` | `false` = don't run the lake sink |
| `ADMINS` | — | Comma-separated admin emails: the analytics dashboard, and editing or removing any listed event. Empty = no admins, and analytics is open to any logged-in user (dev only). `ANALYTICS_ADMINS` still works as a fallback. |

## Tests

```bash
cd backend
mvn test      # 68 unit tests: planner + day scheduler, reminders, ranking, places mapping, city search, travel, hotels, DuckDB analytics SQL (no Docker)
mvn verify    # + 14 end-to-end tests (PlannerFlowIT) on real Postgres, Kafka and Redis via Testcontainers (needs Docker)
LIVE_OSM=true mvn test -Dtest=OsmHoursClientLiveTest   # optional: calls the real Overpass API
```

The end-to-end tests cover the save fan-out (idempotent, inbox-only), listing and editing events (owner-only), reminders for saved events and trips (sent once across reruns), password reset (old sessions signed out), consumer idempotency, itineraries, and every verification rule: single-use codes, the 5-attempt limit, concurrent submissions, no account enumeration, and an idempotent email worker. Testcontainers is pinned to 1.21.4 for Docker Engine 29 compatibility.

There's no CI workflow in the repo yet; run `mvn verify` before pushing.

## Layout

```
backend/src/main/java/com/wanderly/
  user/            F1 auth (JWT), profile, preferences, email verification (OtpStore: Redis + Lua)
  catalog/         F2 events (seeded + user-listed, venue geocoding), trip ideas
  saved/           F3 saved events, after-commit Kafka relay
  places/          F5 curated, bundled OSM, live Wikipedia + Overpass (or OpenTripMap) places, OSM hours, city search (Photon)
  itinerary/       F6 planner: k-means per day → time-window insertion with lunch → time-aware 2-opt/relocate
  recommendation/  F5/F7/F10 ranking, affinity consumer, feed
  notification/    F11 idempotent consumers, hourly reminders (events + trips), inbox, email worker, SMTP sender
  travel/          F8 airports (OurAirports), OSRM road routes, travel planner (parallel fan-out)
  stay/            F9 hotels from Photon, tiering and indicative pricing
  analytics/       F12 lake sink (Kafka batch → S3), DuckDB queries, dashboard API
  messaging/       topics + event payloads + publisher
  config/          security, Kafka topics and dead-letter handling, typed properties
backend/src/main/resources/
  db/migration/    Flyway V1 schema, V2 seed catalog, V3/V4 email verification, V5 bookings → saved events
  places/          curated places dataset; osm-cities.json (bundled OpenStreetMap data)
  cities/          85 Indian destinations (geocoded)
  travel/          116 Indian airports (OurAirports, public domain)
frontend/src/
  pages/           Explore, Plan a trip, Discover, My trips, List an event, Inbox, Profile
  components/      cards, event dialog, login / verify-code / reset-password dialog, city search, nearby panel, toasts
  saved.jsx        which events the user saved, shared across pages
  api.js           REST client (JWT in localStorage)
infra/seaweedfs/   local S3 credentials for the lake
docs/api.http      request walkthrough
tools/fetch_osm_places.py  builds the bundled OpenStreetMap city data (Overpass + Wikipedia page views)
```

## Next up

- **Algorithm:** a time-window-aware itinerary scheduler, which fixes late starts and cross-city days.
- **Data:** events beyond the three demo cities (an organiser or admin flow); finish bundling all 83 destinations.
- **Phase 2:** the Python recommender, which reads the S3 lake and writes `recs:{userId}`. (Payments were deliberately dropped: Wanderly is a planner.)
- **Phase 3:** Terraform and ECS deploy, password reset, rate limiting.

See [DECISIONS.md → Not built yet](DECISIONS.md#not-built-yet).
