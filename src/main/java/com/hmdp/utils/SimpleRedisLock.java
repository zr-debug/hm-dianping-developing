package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    // 业务名称（锁名）
    private String name;

    private StringRedisTemplate stringRedisTemplate;
    // 锁前缀
    private static final String KEY_PREFIX = "lock:";

    // 锁标识的前缀，为了防止不同服务器上相同线程id的线程产生相同的锁表示，将锁标识取为uuid + threadId
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识，在释放锁时检查线程标识，如果标识相同，说明是自己创建的锁，然后释放
        // 防止线程A获取锁后阻塞，锁达到超时时间自动释放，此时另一个线程B获取了锁，然后阻塞线程A苏醒，执行完操作后释放了B获取的锁
        // A释放完B的锁后，相当于B在无锁操作，于是现在如果有线程C也要获取锁，则C可以成功获取锁，于是B和C在临界区并发执行
        // 所以在释放锁时需要判断线程标识，看看是不是自己创建的锁，如果不是，则释放锁失败
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId,
                timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    // 可惜这个函数还是存在问题，若线程A获取锁后执行完所有操作，开始释放锁，此时存放在redis数据库中的线程标识和当前线程A的线程
    // 标识相同，进入if分支中，在开始执行delete操作前，线程A阻塞了，一直阻塞到线程A的锁在redis中过期，此时线程B成功获取锁
    // 然后线程A苏醒，继续执行未执行的delete操作，可是此时的锁已经不是线程A的那把锁了，但是线程A还是错误的释放了线程B的锁
    // 导致线程B在没有锁住临界区的情况下执行临界区的代码，这时线程C也成功获取锁并执行临界区代码，于是就出现了两个线程同时进入
    // 临界区的情况，造成并发问题
    // 这个问题提醒我们如果在并发线程中有：判断-->操作 这种逻辑，一定要注意由于判断和操作不是原子执行的
    // 可能发生在判断条件满足后开始执行操作的这段真空时间，有另一个操作改变了判断条件的属性，导致判断不成立这种情况
//    @Override
//    public void unLock() {
//        // 释放锁
//        if((ID_PREFIX + Thread.currentThread().getId())
//                .equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + name)))
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
