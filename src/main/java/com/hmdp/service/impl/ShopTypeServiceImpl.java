package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        List<String> shopList = stringRedisTemplate.opsForList()
                .range(RedisConstants.SHOP_TYPE_LIST_KEY, 0, -1);
        List<ShopType> shops;
        if(CollectionUtil.isNotEmpty(shopList)) {
            //缓存命中
            shops = shopList
                    .stream()
                    .map(shopJson->JSONUtil.toBean(shopJson,ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shops);
        }
        //redis缓存未命中，从mysql数据库中查询
        shops = query().orderByAsc("sort").list();
        if(CollectionUtil.isEmpty(shops)) {
            //数据库中没有数据
            return Result.fail("数据库中没有数据！");
        }
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.SHOP_TYPE_LIST_KEY,
                shops.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList()));
        return Result.ok(shops);
    }
}
