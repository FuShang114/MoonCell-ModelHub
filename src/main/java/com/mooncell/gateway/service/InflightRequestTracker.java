package com.mooncell.gateway.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 飞行中请求跟踪器
 * 用于跟踪当前正在处理的请求数量
 */
@Component
public class InflightRequestTracker {
    private final AtomicInteger inflight = new AtomicInteger(0);

    /**
     * 请求开始时的回调
     */
    public void onStart() {
        inflight.incrementAndGet();
    }

    /**
     * 请求结束时的回调
     */
    public void onEnd() {
        int v;
        do {
            v = inflight.get();
            if (v <= 0) {
                return;
            }
        } while (!inflight.compareAndSet(v, v - 1));
    }

    /**
     * 获取当前飞行中的请求数量
     * @return 当前正在处理的请求数
     */
    public int inflight() {
        return inflight.get();
    }
}