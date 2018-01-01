package com.ericguerra.ordertrail;

import com.ericguerra.ordertrail.events.OrderEvent;
import com.ericguerra.ordertrail.projection.OrderStore;
import com.ericguerra.ordertrail.projection.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderTrailIntegrationTest {
    @Autowired OrderStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private static final Instant T = Instant.parse("2017-06-01T15:00:00Z");

    @BeforeEach
    void clean() {
        jdbc.update("delete from order_events");
        jdbc.update("delete from order_views");
    }

    private static OrderEvent.Placed placed(String no, long version) {
        return new OrderEvent.Placed(no + "-e" + version, no, version, T, "APP", "C9", "ben@example.com",
                List.of(new OrderEvent.Line("L1", "SHOE", "Trail Runner", 1, 12999, "SHIP", null)), 12999);
    }

    @Test
    void duplicatesAreIgnoredAndGapsAreBufferedUntilFilled() {
        var shipped = new OrderEvent.LinesShipped("A-e3", "A1", 3, T.plusSeconds(60), List.of("L1"), "UPS", "1Z9", null);
        var paid = new OrderEvent.PaymentCaptured("A-e2", "A1", 2, T.plusSeconds(30), 12999);

        assertEquals(OrderStore.Outcome.APPLIED, store.accept(placed("A1", 1)));
        assertEquals(OrderStore.Outcome.DUPLICATE, store.accept(placed("A1", 1)));
        assertEquals(OrderStore.Outcome.BUFFERED, store.accept(shipped), "v3 waits for v2");
        assertEquals(OrderView.LineStatus.ORDERED, store.load("A1").orElseThrow().lines.get("L1").status);
        assertEquals(List.of("A1"), store.stuckOrders(Instant.parse("2030-01-01T00:00:00Z")));

        assertEquals(OrderStore.Outcome.APPLIED, store.accept(paid));
        OrderView view = store.load("A1").orElseThrow();
        assertEquals(3, view.version, "buffered v3 applied right after v2");
        assertEquals(OrderView.OrderStatus.SHIPPED, view.status());
        assertTrue(store.stuckOrders(Instant.parse("2030-01-01T00:00:00Z")).isEmpty());
    }

    @Test
    void guestTrackingRequiresMatchingEmail() throws Exception {
        store.accept(placed("W2002", 1));
        mvc.perform(get("/api/track/w2002").param("email", " BEN@example.com "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.lines[0].name").value("Trail Runner"))
                .andExpect(jsonPath("$.timeline[0].message").value("Order placed via mobile app"))
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get("/api/track/W2002").param("email", "someone@else.com")).andExpect(status().isNotFound());
        mvc.perform(get("/api/track/NOPE").param("email", "ben@example.com")).andExpect(status().isNotFound());
    }

    @Test
    void httpIngestionAndCustomerView() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                {"type":"ORDER_PLACED","eventId":"x1","orderNo":"P3003","version":1,"occurredAt":"2017-06-02T10:00:00Z",
                 "channel":"POS","customerId":"C9","email":"ben@example.com","totalCents":100,
                 "lines":[{"lineId":"L1","sku":"MUG","name":"Mug","qty":1,"unitPriceCents":100,"fulfillment":"CARRY_OUT"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        store.accept(placed("W2003", 1));
        mvc.perform(get("/api/customers/C9/orders"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderNo").value("P3003")); // newest first
    }
}
