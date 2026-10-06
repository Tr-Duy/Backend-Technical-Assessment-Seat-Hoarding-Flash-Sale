package com.hoangha.flashsale.queue;

import com.hoangha.flashsale.dto.OrderMessage;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class InMemoryOrderQueue implements OrderQueue {

    private final LinkedBlockingQueue<OrderMessage> queue = new LinkedBlockingQueue<>();

    @Override
    public void publish(OrderMessage message) {
        queue.offer(message);
    }

    @Override
    public OrderMessage poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
