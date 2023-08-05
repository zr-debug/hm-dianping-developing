package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * <p>
 *     基于redis的分布式全局Id生成器
 * </p>
 */
@Component
public class RedisIdWorker {

    // 开始时间戳 2022年1月1日 0:00:00
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    // 序号位长度
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     *
     * @param keyPrefix 业务前缀，不同的业务有不同的全局Id，以此参数为键区分不同业务的全局Id
     * @return
     */
    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        timeStamp <<= COUNT_BITS;

        // 获取当前日期，精确到天，以天为单位生成序列号（即每一天序列号都从0开始自增）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 生成序列号（该函数对应redis的INCR指令，当给定的Key在redis中不存在时，INCR指令会先将Key的值初始化为0，再将Key
        // 的值增加指定步长，默认INCR的步长为1）
        long number = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        timeStamp += number;
        return timeStamp;
    }
}
