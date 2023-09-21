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
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.ClusterSlotHashUtil;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


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

    /**
     * <p>
     *     导入店铺坐标到redis
     * </p>
     */
    @Test
    void testShopLocation() {
        // 获取数据库中的所有店铺
        List<Shop> shops = shopService.list();

        // 根据店铺类型对店铺进行分类，将同类型的店铺放在一起
        Map<Long, List<Shop>> collect = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for(Map.Entry<Long, List<Shop>> entity : collect.entrySet()) {
            String key = RedisConstants.SHOP_GEO_KEY + entity.getKey();
            Iterable<RedisGeoCommands.GeoLocation<String>> geoLocation = entity
                    .getValue().
                    stream().
                    map(
                            shop->new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(),shop.getY()))
                    ).collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(key,geoLocation);
        }
    }

    @Test
    void name() {
        System.out.println(ClusterSlotHashUtil.calculateSlot("name"));
        System.out.println(ClusterSlotHashUtil.calculateSlot("sex"));
        System.out.println(ClusterSlotHashUtil.calculateSlot("hello"));
        System.out.println(ClusterSlotHashUtil.calculateSlot("name"));

    }
}
