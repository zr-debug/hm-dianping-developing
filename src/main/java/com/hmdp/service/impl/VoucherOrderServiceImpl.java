package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 这个阻塞队列存储订单信息
    private static final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private volatile IVoucherOrderService proxy;

    // 此注解的作用是让spring在创建bean对象并为bean对象注入所有属性后调用该函数进行一些需要用到注入的属性进行初始化的场景
    // 通过继承InitializerBean或者在让spring使用带参构造函数注入属性也可以达到相同目的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        private static final String stream = "stream.orders";
        @Override
        public void run() {
            // 该线程循环从订单队列中取出订单信息并在mysql中创建订单
            while(true) {
                try {
                    // 获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders >
                    List<MapRecord<String, Object, Object>> orders = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(stream, ReadOffset.lastConsumed())
                    );
                    if(CollectionUtil.isEmpty(orders)) {
                        // 没有取到消息，进行下一次循环
                        continue;
                    }
                    VoucherOrder order = BeanUtil.fillBeanWithMap(orders.get(0).getValue(),new VoucherOrder(),true);
                    // 创建订单
                    proxy.handleVoucherOrder(order);
                    // 发送ack，标识消息已经得到正确处理，将该消息从未决队列中移除 XACK stream.orders g1 orders.get(0).getId()
                    stringRedisTemplate.opsForStream().acknowledge(stream,"g1",orders.get(0).getId());
                } catch (Exception e) {
                    log.error("处理订单异常,尝试重新处理异常消息", e);
                    while (true) {
                        try {
                            // 如果在处理消息的过程中出现异常，说明这条消息没有被成功处理，所以需要从未决消息队列中取出该条未被成功处理的消息继续处理
                            List<MapRecord<String, Object, Object>> orders = stringRedisTemplate.opsForStream().read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create(stream, ReadOffset.from("0"))
                            );
                            if(CollectionUtil.isEmpty(orders)) {
                                // 未决消息消息已被确认(说明此时pending-list里面已经没有异常消息了)，退出循环去处理消息队列中从未被取出过的消息
                                break;
                            }
                            VoucherOrder order = BeanUtil.fillBeanWithMap(orders.get(0).getValue(),new VoucherOrder(),true);
                            // 创建订单
                            proxy.handleVoucherOrder(order);
                            // 发送ack，标识消息已经得到正确处理，将该消息从未决队列中移除 XACK stream.orders g1 orders.get(0).getId()
                            stringRedisTemplate.opsForStream().acknowledge(stream,"g1",orders.get(0).getId());
                        } catch (Exception exp) {
                            log.error("处理订单异常,尝试重新处理异常消息", exp);
                            try {
                                // 防止频繁的循环抛出异常
                                Thread.sleep(200);
                            } catch (InterruptedException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    }
                }
            }
        }
    }

    /*
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            // 该线程循环从订单队列中取出订单信息并在mysql中创建订单
            while(true) {
                try {
                    // 获取订单信息
                    VoucherOrder order = orderTasks.take();
                    // 注意，此处不能从UserHolder中获取用户id，只能从阻塞队列取出的订单信息里获取用户id
                    // 因为该线程和拦截下单用户id的线程不是同一个线程，拦截用户id的线程是springmvc框架创建的
                    // 而该线程是我们自己在业务代码中通过线程池创建的，所以这个线程的ThreadLocal中没有存储相应的userid

                    // 创建订单
                    proxy.handleVoucherOrder(order);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }
     */

    @Transactional
    public void handleVoucherOrder(VoucherOrder order) {
        // 由于前面的redis脚本判断已经保证一个用户只能有一个线程走到这一步，所以这里不用加分布式锁来保证服务器集群并发安全性
        // 先将数据库中对应优惠券的库存减一
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0)
                .update();
        if(!success) {
            log.error("库存扣减失败");
            return;
        }
        save(order);
    }

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     *
     * <p>
     *     执行秒杀券相关操作，当用户下单优惠券后，对优惠券库存进行减少
     *     因为该函数同时涉及到对两张表的更新操作，所以要加事物，以便在发生异常时回滚
     * </p>
     * @author 阿瑞
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        List<String> keys = new ArrayList<>();
        keys.add(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        keys.add(RedisConstants.SECKILL_ORDER_KEY + voucherId);
        long orderId = redisIdWorker.nextId("order");
        Long ret = stringRedisTemplate.execute(SECKILL_SCRIPT, keys,
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId));
        if(ret.intValue() != 0) {
            return Result.fail(ret == 1 ? "库存不足" : "不能重复下单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        /*
        // 用户满足下单要求
        VoucherOrder order = new VoucherOrder();
        order.setVoucherId(voucherId);
        long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        order.setUserId(UserHolder.getUser().getId());
        try {
            orderTasks.put(order);
        } catch (Exception e) {
            log.error("处理订单异常", e);
        }
         */
        return Result.ok(orderId);
    }

    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 判断秒杀是否结束
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已经结束！");
        }
        // 判断是否还有库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("优惠券已被抢光！");
        }

        Long userId = UserHolder.getUser().getId();

//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLocked = lock.tryLock();
        if(!isLocked) {
            // 获取锁失败，一般当获取锁失败时有两种方法：1、重试 2、返回错误信息
            // 对于一人一单业务，当获取锁失败时表明已经有一个线程通过该用户的用户id成功获取了锁，说明当前线程
            // 其实是一个不合法线程，就算重试成功获取了锁，执行的时候也会发现数据库中已经存在该用户下的订单（如果之前获取锁的线程没有发生异常的话）
            // 从而不会下单，所以对于一人一单业务，获取锁失败直接返回错误信息就行
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象（事物）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }
     */


    /**
     * <p>
     *     一人一单逻辑
     * </p>
     * @param voucherId
     * @return
     * @author 阿瑞
     */

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 获取登陆用户的id作为下单用户id
        Long userId = UserHolder.getUser().getId();

        // 一人一单逻辑（同一个用户购买同一个优惠券只能购买一张)
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("每位用户仅限一张！");
        }

        // 以上都不满足，则用户可以正常下单，扣减库存（当库存大于0时才扣减，这是对乐观锁的优化，可以保证并发安全）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0) //where voucher_id = voucherId and stock > 0
                .update();

        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 生成全局唯一id作为订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        // 设置下单的代金券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);

    }
}
