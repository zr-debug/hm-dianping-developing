package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.CACHE_FOLLOW_LIST + userId;
        // 当isFollow为true是，表示当前用户要关注followUserId用户；当isFollow为false时，表示当前用户要取关followUserId用户
        if(isFollow) {
            // 关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        } else {
            // 取关，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.CACHE_FOLLOW_LIST + userId;
        Boolean member = stringRedisTemplate.opsForSet().isMember(key, followUserId.toString());
        // Integer count = query().eq("user_id",userId).eq("follow_user_id", followUserId).count();
        return Result.ok(member);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = RedisConstants.CACHE_FOLLOW_LIST + userId;
        String key2 = RedisConstants.CACHE_FOLLOW_LIST + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect.isEmpty()) return Result.ok(new ArrayList<>());
        List<User> users = userService.listByIds(intersect);
        List<UserDTO> ret = users.stream().map(entity-> BeanUtil.copyProperties(entity,UserDTO.class)).collect(Collectors.toList());
        return Result.ok(ret);
    }
}
