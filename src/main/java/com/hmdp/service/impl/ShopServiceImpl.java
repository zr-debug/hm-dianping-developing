package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
//        return queryWithMutex(id);
//        return queryWithPassThrough(id);
        return queryWithLogicalExpire(id);
    }

    /**
     *<p>
     *     解决缓存穿透的代码逻辑
     *</p>
     *
     *
     * @author 阿瑞
     * @param id
     * @return Shop
     */
    public Result queryWithPassThrough(Long id) {
        //先从缓存中查找店铺信息（店铺信息在redis缓存中以字符串的形式存储）
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop;
        if(StrUtil.isNotBlank(shopJson)) {
            //缓存命中
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //如果代码走到这说明StrUtil.isNotBlank(shopJson)返回的是false，那么shopJson的值可能是null或 "" 、 "\t\n"之类的空字符串
        //如果是不是null，则证明shopJson是我们为了防止缓存穿透而存入缓存的空字符串
        if(shopJson != null) {
            return Result.fail("店铺信息不存在！");
        }
        //缓存未命中，从mysql数据库中查找
        shop = getById(id);
        if(shop == null) {
            //在redis中存入空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("店铺不存在！");
        }
        shopJson = JSONUtil.toJsonStr(shop);
        //将从mysql中查出的数据存入redis缓存
        stringRedisTemplate.opsForValue().set(key, shopJson, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    /**
     * <p>
     *     通过加互斥锁来解决缓存击穿（和缓存穿透）
     * </p>
     * @author 阿瑞
     * @param id
     * @return Result
     */
    public Result queryWithMutex(Long id) {
        try {
            //先从缓存中查找店铺信息（店铺信息在redis缓存中以字符串的形式存储）
            String key = RedisConstants.CACHE_SHOP_KEY + id;
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            //缓存未命中，从mysql数据库中查找
            //为了防止多个线程同时查询数据库造成缓存击穿，只有获取了锁的线程才能去查找数据库
            //获取当前店铺的锁
            while (true) {
                String shopJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(shopJson)) {
                    //缓存命中，直接返回
                    return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
                }
                //缓存命中但缓存值为空字符串，说明是为了防止缓存穿透而缓存的空字符串
                if (shopJson != null) return Result.fail("店铺信息不存在！");
                //缓存未命中，为了防止多线程同时未命中该缓存导致并发查询数据库给数据库带来压力，通过加互斥锁来保证同一时间
                //只有一个线程去数据库查询店铺编号为id的店铺信息
                if (tryLock(lockKey)) {
                    //此处做一个DoubleCheck，检查缓存中是否已经有数据，防止大量线程获取锁失败后在重新tryLock之前
                    //第一个获取到锁的线程刚好从数据库中查到数据并设置了缓存释放了锁，此时这些线程会对数据库做重复查询
                    shopJson = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(shopJson)) {
                        //缓存命中，直接返回
                        unLock(lockKey);
                        return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
                    }
                    if (shopJson != null) {
                        unLock(lockKey);
                        return Result.fail("店铺信息不存在！");
                    }

                    Shop shop = getById(id);
                    //在数据库中不存在
                    if(shop == null) {
                        //在redis中存入空值防止缓存穿透
                        stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                        unLock(lockKey);
                        return Result.fail("店铺不存在！");
                    }
                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    unLock(lockKey);
                    return Result.ok(shop);
                } else {
                    Thread.sleep(100);
                }
            }
        }catch (Exception e) {
            log.error("异常！");
            return Result.fail("程序执行出错！");
        }
    }

    /**
     * <p>
     *     尝试获取锁，底层逻辑是通过redis的setnx命令，在key存在时setnx失败则获取锁返回false
     * </p>
     * @param key
     * @return
     * @author 阿瑞
     */
    public boolean tryLock(String key) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key,
                RedisConstants.CACHE_LOCK_VALUE, 10, TimeUnit.SECONDS);
        //如果直接将aBoolean返回，拆包的时候可能会出现空指针，所以通过BooleanUtil工具类转换一下
        return BooleanUtil.isTrue(aBoolean);
    }

    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
    /**
     * <p>
     *     热点缓存预热
     * </p>
     * @author 阿瑞
     * @param id
     */
    public void cacheWarmUp(Long id, Long expireSeconds) throws InterruptedException {
        // 由于热点缓存预热默认数据库里一定存在相应数据，所以不做数据是否存在的检查
        Shop shop = getById(id);
        // 模拟重建缓存的延迟
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     *<p>
     *     通过逻辑过期来解决缓存击穿问题
     *</p>
     * @param id
     * @return
     * @author 阿瑞
     */
    public Result queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //由于热点缓存默认已经做过预热，所以shopJson默认不为null，不过为了程序健壮性考虑，还是检查一下，如果为null，直接返回失败，不做处理
        if(shopJson == null) {
            return Result.fail("缓存未命中，服务器没有为缓存预热！");
        }
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data,Shop.class);

        if(redisData.getExpireTime().isBefore(LocalDateTime.now())) {
            //热点缓存逻辑时间过期，先获取锁，若获取锁成功，则开启一个线程去做缓存数据的更新，并返回当前缓存中的过期数据；
            //若获取锁失败，则证明已经有一个线程在做缓存更新了，直接返回当前缓存中的过期数据即可
            if(tryLock(lockKey)) {
                //获取锁成功，做一个Double Check
                shopJson = stringRedisTemplate.opsForValue().get(key);
                redisData = JSONUtil.toBean(shopJson,RedisData.class);
                if(redisData.getExpireTime().isBefore(LocalDateTime.now())) {
                    CACHE_REBUILD_EXECUTOR.submit(() -> {
                        try {
                            //重建缓存
                            this.cacheWarmUp(id, RedisConstants.LOCK_SHOP_TTL);
                        }catch (Exception e) {
                            throw new RuntimeException(e);
                        }finally {
                            unLock(lockKey);
                        }
                    });
                } else unLock(lockKey);
            }
        }

        //缓存命中且逻辑过期时间晚于当前时间、缓存命中且逻辑过期时间早于当前时间（即缓存已逻辑过期）并且无论获取锁成功或失败，都会走到这一逻辑
        return Result.ok(shop);
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空！");
        }
        //先更新数据库
        updateById(shop);
        //删除缓存（店铺信息在redis缓存中以字符串形式存储）
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null) {
            // 前端请求中没有携带x，y参数，说明返回的店铺不按距离排序
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int to = from + SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(to));
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent().stream().skip(from).collect(Collectors.toList());
        if(CollectionUtil.isEmpty(content)) return Result.ok(Collections.emptyList());
        Map<Long, Distance> mapToDis = content
                .stream()
                .map(geo-> new AbstractMap.SimpleEntry<>(Long.valueOf(geo.getContent().getName()), geo.getDistance()))
                .collect(Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
        List<String> ids = content.stream().map(e->e.getContent().getName()).collect(Collectors.toList());
        String idsStr = StrUtil.join(",",ids);
        List<Shop> ret = query().in("id", ids).last("order by field(id," + idsStr + ")").list();
        ret.forEach(e->e.setDistance(mapToDis.get(e.getId()).getValue()));
        return Result.ok(ret);
    }
}
