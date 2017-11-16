package com.ericguerra.ordertrail.projection;

import com.ericguerra.ordertrail.events.OrderEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.ericguerra.ordertrail.projection.OrderView.LineStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderProjectorTest {
    private static final Instant T = Instant.parse("2017-06-01T15:00:00Z");
    private long v;

    private OrderView placed() {
        OrderView view = new OrderView();
        OrderProjector.apply(view, new OrderEvent.Placed("e" + (++v), "W1001", v, T, "WEB", "C1", " Ana@Example.com ", List.of(
                new OrderEvent.Line("L1", "TENT", "Tent", 1, 34900, "SHIP", null),
                new OrderEvent.Line("L2", "STOVE", "Stove", 1, 5499, "SHIP", null),
                new OrderEvent.Line("L3", "LAMP", "Headlamp", 1, 4999, "PICKUP", "HOU01")), 45398));
        return view;
    }

    private void apply(OrderView view, java.util.function.LongFunction<OrderEvent> f) {
        v++;
        OrderProjector.apply(view, f.apply(v));
    }

    @Test
    void statusRollsUpFromLines() {
        OrderView o = placed();
        assertEquals("ana@example.com", o.email);
        assertEquals(OrderView.OrderStatus.PLACED, o.status());
        apply(o, n -> new OrderEvent.PaymentCaptured("e" + n, "W1001", n, T, 45398));
        assertEquals(OrderView.OrderStatus.PROCESSING, o.status());

        apply(o, n -> new OrderEvent.LinesShipped("e" + n, "W1001", n, T, List.of("L1"), "UPS", "1Z1", T.plusSeconds(86400 * 3)));
        assertEquals(OrderView.OrderStatus.PARTIALLY_SHIPPED, o.status());
        apply(o, n -> new OrderEvent.LinesShipped("e" + n, "W1001", n, T, List.of("L2"), "UPS", "1Z2", null));
        apply(o, n -> new OrderEvent.ReadyForPickup("e" + n, "W1001", n, T, List.of("L3"), "HOU01"));
        assertEquals(OrderView.OrderStatus.READY_FOR_PICKUP, o.status());

        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z1", "DELIVERED", "Houston, TX"));
        assertEquals(OrderView.OrderStatus.PARTIALLY_DELIVERED, o.status());
        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z2", "DELIVERED", "Houston, TX"));
        apply(o, n -> new OrderEvent.PickedUp("e" + n, "W1001", n, T, List.of("L3"), "HOU01"));
        assertEquals(OrderView.OrderStatus.COMPLETED, o.status());
        assertEquals("Picked up at store HOU01", o.timeline.get(o.timeline.size() - 1).message());
    }

    @Test
    void linesNeverMoveBackwards() {
        OrderView o = placed();
        apply(o, n -> new OrderEvent.LinesShipped("e" + n, "W1001", n, T, List.of("L1"), "UPS", "1Z1", null));
        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z1", "DELIVERED", null));
        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z1", "IN_TRANSIT", null)); // late scan
        apply(o, n -> new OrderEvent.LinesShipped("e" + n, "W1001", n, T, List.of("L1"), "FEDEX", "999", null)); // duplicate ship
        assertEquals(DELIVERED, o.lines.get("L1").status);
        assertEquals("1Z1", o.lines.get("L1").trackingNumber);
    }

    @Test
    void carrierExceptionsNeedAttentionUntilResolved() {
        OrderView o = placed();
        apply(o, n -> new OrderEvent.LinesShipped("e" + n, "W1001", n, T, List.of("L1", "L2"), "UPS", "1Z1", null));
        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z1", "EXCEPTION", "Weather delay"));
        assertEquals(DELIVERY_EXCEPTION, o.lines.get("L2").status, "scan applies to every line on the tracking number");
        assertEquals(OrderView.OrderStatus.ATTENTION, o.status());
        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z1", "OUT_FOR_DELIVERY", null));
        assertEquals(OUT_FOR_DELIVERY, o.lines.get("L1").status);
    }

    @Test
    void returnsAndCancellation() {
        OrderView o = placed();
        apply(o, n -> new OrderEvent.LinesShipped("e" + n, "W1001", n, T, List.of("L1"), "UPS", "1Z1", null));
        apply(o, n -> new OrderEvent.CarrierScan("e" + n, "W1001", n, T, "1Z1", "DELIVERED", null));
        apply(o, n -> new OrderEvent.Cancelled("e" + n, "W1001", n, T, "customer request"));
        assertEquals(DELIVERED, o.lines.get("L1").status, "delivered lines aren't cancelled");
        assertEquals(CANCELLED, o.lines.get("L2").status);
        assertEquals(OrderView.OrderStatus.CANCELLED, o.status());

        OrderView r = placed();
        apply(r, n -> new OrderEvent.LinesReturned("e" + n, "W1001", n, T, List.of("L2"), "POS"));
        assertEquals(OrderView.OrderStatus.PARTIALLY_RETURNED, r.status());
        assertTrue(r.timeline.get(r.timeline.size() - 1).message().contains("via store"));
    }
}
