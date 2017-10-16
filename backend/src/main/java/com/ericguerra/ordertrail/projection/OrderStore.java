package com.ericguerra.ordertrail.projection;

import com.ericguerra.ordertrail.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists events and the derived view.
 *
 * Events are stored in `order_events` (the source of truth) with a unique (order_no, version); the view
 * in `order_views` is a JSON document per order plus indexed columns for lookups. Gaps in the version
 * sequence (Kafka is per-partition ordered, but producers can race) are buffered: an event whose
 * version isn't next waits in the table and is applied once the gap fills.
 */
@Repository
public class OrderStore {
    public enum Outcome { APPLIED, DUPLICATE, BUFFERED }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrderStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Outcome accept(OrderEvent event) {
        Integer exists = jdbc.queryForObject("select count(*) from order_events where event_id = ? or (order_no = ? and version = ?)",
                Integer.class, event.eventId(), event.orderNo(), event.version());
        if (exists != null && exists > 0) return Outcome.DUPLICATE;

        jdbc.update("insert into order_events (event_id, order_no, version, type, payload, occurred_at, applied) values (?, ?, ?, ?, ?, ?, false)",
                event.eventId(), event.orderNo(), event.version(), event.getClass().getSimpleName(), write(event), Timestamp.from(event.occurredAt()));

        OrderView view = load(event.orderNo()).orElseGet(OrderView::new);
        boolean appliedAny = false;
        // Apply this event and any buffered successors that are now contiguous.
        for (OrderEvent next : pendingFrom(event.orderNo(), view.version + 1)) {
            OrderProjector.apply(view, next);
            jdbc.update("update order_events set applied = true where event_id = ?", next.eventId());
            appliedAny = true;
        }
        if (!appliedAny) return Outcome.BUFFERED;
        save(view);
        return Outcome.APPLIED;
    }

    private List<OrderEvent> pendingFrom(String orderNo, long nextVersion) {
        List<OrderEvent> out = new ArrayList<>();
        List<String> payloads = jdbc.queryForList("select payload from order_events where order_no = ? and applied = false and version >= ? order by version",
                String.class, orderNo, nextVersion);
        long expected = nextVersion;
        for (String p : payloads) {
            OrderEvent e = read(p);
            if (e.version() != expected) break;
            out.add(e);
            expected++;
        }
        return out;
    }

    public Optional<OrderView> load(String orderNo) {
        List<String> rows = jdbc.queryForList("select doc from order_views where order_no = ?", String.class, orderNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(readView(rows.get(0)));
    }

    public List<OrderView> byCustomer(String customerId, int limit) {
        return jdbc.queryForList("select doc from order_views where customer_id = ? order by placed_at desc limit ?", String.class, customerId, limit)
                .stream().map(this::readView).toList();
    }

    /** Orders with a gap that has persisted (lost event?) — surfaced on the ops dashboard. */
    public List<String> stuckOrders(Instant olderThan) {
        return jdbc.queryForList("select distinct order_no from order_events where applied = false and occurred_at < ?", String.class, Timestamp.from(olderThan));
    }

    private void save(OrderView v) {
        String doc = write(v);
        int updated = jdbc.update("update order_views set doc = ?, status = ?, version = ?, updated_at = ? where order_no = ?",
                doc, v.status().name(), v.version, Timestamp.from(v.updatedAt), v.orderNo);
        if (updated == 0) {
            jdbc.update("insert into order_views (order_no, customer_id, email, status, version, placed_at, updated_at, doc) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    v.orderNo, v.customerId, v.email, v.status().name(), v.version,
                    v.placedAt == null ? null : Timestamp.from(v.placedAt), Timestamp.from(v.updatedAt), doc);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderEvent read(String s) {
        try {
            return json.readValue(s, OrderEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderView readView(String s) {
        try {
            return json.readValue(s, OrderView.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
