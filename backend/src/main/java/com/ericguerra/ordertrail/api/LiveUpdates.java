package com.ericguerra.ordertrail.api;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-sent events per order number, so an open tracking page updates the moment a scan arrives. */
@Component
public class LiveUpdates {
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String orderNo) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.computeIfAbsent(orderNo, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> subscribers.getOrDefault(orderNo, List.of()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(String orderNo, Object payload) {
        for (SseEmitter e : subscribers.getOrDefault(orderNo, List.of())) {
            try {
                e.send(SseEmitter.event().name("order").data(payload));
            } catch (IOException | IllegalStateException ex) {
                e.completeWithError(ex);
            }
        }
    }

    public int subscriberCount(String orderNo) {
        return subscribers.getOrDefault(orderNo, List.of()).size();
    }
}
