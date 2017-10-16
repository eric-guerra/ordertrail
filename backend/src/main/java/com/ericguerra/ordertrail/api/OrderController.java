package com.ericguerra.ordertrail.api;

import com.ericguerra.ordertrail.events.OrderEvent;
import com.ericguerra.ordertrail.projection.OrderStore;
import com.ericguerra.ordertrail.projection.OrderView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderStore store;
    private final LiveUpdates live;

    public OrderController(OrderStore store, LiveUpdates live) {
        this.store = store;
        this.live = live;
    }

    /**
     * Guest order lookup: order number + the email used at checkout. Both a wrong email and an
     * unknown order return the same 404, so order numbers can't be probed.
     */
    @GetMapping("/track/{orderNo}")
    public ResponseEntity<?> track(@PathVariable String orderNo, @RequestParam String email) {
        Optional<OrderView> view = store.load(orderNo.trim().toUpperCase(Locale.ROOT));
        if (view.isEmpty() || !emailMatches(view.get().email, email)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "We couldn't find an order with that number and email."));
        }
        return ResponseEntity.ok(OrderDto.from(view.get()));
    }

    @GetMapping(path = "/track/{orderNo}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String orderNo, @RequestParam String email) {
        String no = orderNo.trim().toUpperCase(Locale.ROOT);
        Optional<OrderView> view = store.load(no);
        if (view.isEmpty() || !emailMatches(view.get().email, email)) throw new NotFound();
        return live.subscribe(no);
    }

    /** Store associates / customer service: every order for a customer across channels. */
    @GetMapping("/customers/{customerId}/orders")
    public List<OrderDto> byCustomer(@PathVariable String customerId, @RequestParam(defaultValue = "20") int limit) {
        return store.byCustomer(customerId, Math.min(Math.max(limit, 1), 100)).stream().map(OrderDto::from).toList();
    }

    /** HTTP ingestion fallback (same idempotent path as the Kafka listener). */
    @PostMapping("/events")
    public Map<String, Object> ingest(@RequestBody OrderEvent event) {
        OrderStore.Outcome outcome = store.accept(event);
        if (outcome == OrderStore.Outcome.APPLIED) store.load(event.orderNo()).ifPresent(v -> live.publish(v.orderNo, OrderDto.from(v)));
        return Map.of("eventId", event.eventId(), "outcome", outcome);
    }

    @GetMapping("/ops/stuck")
    public List<String> stuck() {
        return store.stuckOrders(Instant.now().minus(Duration.ofMinutes(10)));
    }

    /** Constant-time comparison of normalized emails. */
    static boolean emailMatches(String stored, String given) {
        if (stored == null || given == null) return false;
        byte[] a = stored.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = given.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class NotFound extends RuntimeException {}
}
