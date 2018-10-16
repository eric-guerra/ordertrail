# OrderTrail

Omnichannel order tracking. Orders placed on the website, in the app, at a store register or over the phone, then shipped by carriers or picked up in store, all produce events on **Kafka**. OrderTrail projects them into one timeline per order. Customers track orders on a **React + Redux** page that updates live; store associates see every order for a customer.

> Portfolio reference implementation of a 2017 retail project (React/Redux, Spring Boot, PostgreSQL, Kafka), built with Spring Boot 3 and Java 21 sealed types.

```
web · app · POS · call center · WMS · carriers
          │  order-events (key = order number → per-order ordering)
          ▼
   OrderEventListener ──▶ OrderStore.accept(): dedupe → buffer gaps → OrderProjector → order_views (JSON + indexed cols)
          │ failures after retries → order-events.DLT                    │
          ▼                                                              ▼
   SSE /api/track/{no}/stream ◀── publish on apply           GET /api/track/{no}?email=…  ·  GET /api/customers/{id}/orders
```

## Backend (`backend/`)

- **Event model**: a sealed `OrderEvent` hierarchy (placed, payment captured, lines shipped, carrier scan, ready for pickup, picked up, returned, cancelled) with Jackson polymorphic typing
- **Exactly-once effect**: events are stored with unique `event_id` and `(order_no, version)` keys, so redeliveries are no-ops. **Out-of-order events** (version gaps from racing producers) are buffered and applied as soon as the gap fills. Long-lived gaps show up at `/api/ops/stuck`
- **Projection rules** ([OrderProjector](backend/src/main/java/com/ericguerra/ordertrail/projection/OrderProjector.java), pure and unit-tested): lines only move forward (a late "in transit" scan can't undo "delivered"); carrier scans apply to every line on a tracking number; exceptions flag the order ATTENTION until a later good scan clears them; cancellation only affects unfulfilled lines; returns override delivery. The order headline status (partially shipped, ready for pickup, completed…) is derived from the line statuses
- **Guest tracking** needs the order number *and* the checkout email (normalized and compared in constant time); a wrong email and an unknown order return the same 404
- Kafka error handling: exponential backoff, then a dead-letter topic; deserialization errors aren't retried

## Frontend (`frontend/`)

A Redux module ([src/tracking.js](frontend/src/tracking.js)) with validation, a lookup thunk, a live-update subscription (EventSource) that ignores late or older snapshots and other orders, grouping of lines into shipments, pickups and pending, plus headline copy and progress steps. The React page renders a progress tracker, per-shipment line statuses and the history timeline.

## Run and test

```bash
docker compose up --build
cd frontend && npm install && npm run build      # serve public/ behind the backend or any static host

cd backend && mvn test                           # projector unit tests; H2 integration (dedupe, gap buffering, tracking API)
cd frontend && npm test                          # Redux flow tests
```
