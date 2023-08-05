package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    // 保存秒杀优惠券库存的前缀
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    // 优惠券下单用户set的前缀
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    // 店铺类型列表缓存的key
    public static final String SHOP_TYPE_LIST_KEY = "SHOP_TYPE_LIST_KEY";

    // 代表redis锁的常量
    public static final String CACHE_LOCK_VALUE = "LOCKED";

    public static final String CACHE_FOLLOW_LIST = "follows:";
}
