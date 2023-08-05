package com.hmdp.utils;

/**
 * <p>
 *     锁接口，定义了锁相关的操作
 * </p>
 * @since 2023-07-20
 * @author 阿瑞
 */
public interface ILock {
    /**
     * <p>
     *     尝试获取锁，当获取锁失败时，返回false而不是阻塞
     * </p>
     * @Param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return true代表获取锁成功；false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * <p>
     *     释放锁
     * </p>
     */
    void unlock();
}
