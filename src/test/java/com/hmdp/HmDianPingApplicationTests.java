package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testRedisIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            // 每个任务都生成100个Id并打印
            for(int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        for(int i = 2; i < 15; i++) shopService.cacheWarmUp((long) i, RedisConstants.LOCK_SHOP_TTL);
    }

    @Test
    void generatorToken() throws Exception{
        FileWriter output = new FileWriter("tokens.txt");
        for(int i = 1; i <= 2000; i++) {
            String token = UUID.randomUUID().toString(true);
            output.write(token + System.lineSeparator());
            UserDTO user = new UserDTO();
            user.setIcon("");
            user.setId((long) i);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            Map<String, Object> bean = BeanUtil.beanToMap(user, new HashMap<>(),
                    CopyOptions.create().setFieldValueEditor((filedName, filedValue) -> String.valueOf(filedValue)));
            stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, bean);
        }
        output.close();
    }

    @Test
    void deleteKey() throws Exception {
        FileReader t = new FileReader("tokens.txt");
        BufferedReader reader = new BufferedReader(t);
        String token;
        while((token = reader.readLine()) != null) {
            stringRedisTemplate.delete(token);
        }
        reader.close();
        t.close();
    }
}
