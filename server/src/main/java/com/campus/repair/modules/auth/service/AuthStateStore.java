package com.campus.repair.modules.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 认证态存储（连续输错计数 / 图形验证码），Redis 优先、进程内内存兜底。
 *
 * <p>设计动机：{@code local} profile 只排除了 Redisson 自动配置，并未排除 Spring Data Redis，
 * 而登录/验证码链路此前强依赖 Redis —— 本机没起 Redis 时登录直接 500，与 README「开箱即用、无外置依赖」
 * 的承诺矛盾。此处与 {@code OrderNoGenerator}（单号发号器本地 AtomicLong 兜底）、
 * {@code DispatchEngine}（无 Redisson 时本地 ReentrantLock 兜底）采用同一套降级策略。</p>
 *
 * <p><b>注意</b>：内存兜底是单实例语义，多实例/生产部署必须保证 Redis 可用，否则失败计数与验证码
 * 无法跨节点共享，防爆破能力会下降。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthStateStore {

    /** 兜底容器上限，防止异常流量下的无界增长 */
    private static final int MAX_LOCAL_ENTRIES = 10_000;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private final ConcurrentHashMap<String, LocalEntry> localStore = new ConcurrentHashMap<>();
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    /**
     * 读取键值。Redis 调用失败时回落到内存；Redis 正常但键不存在时直接返回 null（不回落到脏内存值）。
     */
    public String get(String key) {
        try {
            ValueOperations<String, String> ops = resolveValueOperations();
            return ops.get(key);
        } catch (Exception e) {
            warnFallback(e);
        }
        return getLocal(key);
    }

    /**
     * 写入带过期时间的键值。
     */
    public void set(String key, String value, Duration ttl) {
        try {
            ValueOperations<String, String> ops = resolveValueOperations();
            ops.set(key, value, ttl);
            return;
        } catch (Exception e) {
            warnFallback(e);
        }
        setLocal(key, value, ttl);
    }

    /**
     * 删除键值。两处存储都清理，避免 Redis 恢复后残留过期的本地计数。
     */
    public void delete(String key) {
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                throw new IllegalStateException("未装配 StringRedisTemplate");
            }
            redisTemplate.delete(key);
        } catch (Exception e) {
            warnFallback(e);
        }
        localStore.remove(key);
    }

    private ValueOperations<String, String> resolveValueOperations() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw new IllegalStateException("未装配 StringRedisTemplate");
        }
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        if (ops == null) {
            throw new IllegalStateException("Redis ValueOperations 不可用");
        }
        return ops;
    }

    private String getLocal(String key) {
        LocalEntry entry = localStore.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMillis() <= System.currentTimeMillis()) {
            localStore.remove(key);
            return null;
        }
        return entry.value();
    }

    private void setLocal(String key, String value, Duration ttl) {
        if (localStore.size() >= MAX_LOCAL_ENTRIES) {
            long now = System.currentTimeMillis();
            localStore.entrySet().removeIf(e -> e.getValue().expireAtMillis() <= now);
        }
        localStore.put(key, new LocalEntry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    private void warnFallback(Exception e) {
        if (fallbackWarned.compareAndSet(false, true)) {
            log.warn("[AuthStateStore] Redis 不可用，认证限流/验证码已降级为进程内内存存储（单实例语义，生产环境请确保 Redis 可用）: {}",
                    e.getMessage());
        }
    }

    private record LocalEntry(String value, long expireAtMillis) {
    }
}
