package com.campus.repair.modules.workorder.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class OrderNoGenerator {

    private final StringRedisTemplate redisTemplate;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final AtomicLong LOCAL_FALLBACK_COUNTER = new AtomicLong(1);

    public String generateOrderNo() {
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        String key = "workorder:seq:" + dateStr;
        Long seq = 1L;

        try {
            if (redisTemplate != null && redisTemplate.getConnectionFactory() != null) {
                seq = redisTemplate.opsForValue().increment(key);
                if (seq != null && seq == 1) {
                    redisTemplate.expire(key, 2, TimeUnit.DAYS);
                }
            } else {
                seq = LOCAL_FALLBACK_COUNTER.getAndIncrement();
            }
        } catch (Exception e) {
            seq = LOCAL_FALLBACK_COUNTER.getAndIncrement();
        }

        if (seq == null) seq = 1L;
        return String.format("BX%s%06d", dateStr, seq);
    }
}
