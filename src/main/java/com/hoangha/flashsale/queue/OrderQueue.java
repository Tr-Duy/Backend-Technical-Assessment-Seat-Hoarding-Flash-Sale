package com.hoangha.flashsale.queue;

import com.hoangha.flashsale.dto.OrderMessage;

public interface OrderQueue {
    void publish(OrderMessage message);
    OrderMessage poll(long timeoutMs) throws InterruptedException;
}
