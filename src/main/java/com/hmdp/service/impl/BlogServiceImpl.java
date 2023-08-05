package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null) return Result.fail("笔记不存在！");
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        UserDTO curUser = UserHolder.getUser();
        if(curUser == null) {
            // 用户未登陆，无需在查询Blog列表或查看Blog详情时判断用户是否点赞
            return;
        }
        Double score = stringRedisTemplate.opsForZSet().score(key,
                String.valueOf(curUser.getId())
        );
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key,
                String.valueOf(userId)
        );
        if(score == null) {
            // 用户没有点赞过该博客，可以点赞
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success) stringRedisTemplate.opsForZSet().add(key,
                    String.valueOf(userId),
                    System.currentTimeMillis()
            );
            else return Result.fail("点赞失败");
        } else {
            // 用户已经给该博客点过赞，取消点赞
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success) stringRedisTemplate.opsForZSet().remove(key,
                    String.valueOf(userId)
            );
            else return Result.fail("取消点赞失败");
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> rank = stringRedisTemplate.opsForZSet().range(key,0,4);
        Iterator<String> iterator = rank.iterator();
        List<UserDTO> userDTOS = new ArrayList<>();
        while(iterator.hasNext()) {
            String userId = iterator.next();
            User user = userService.getById(Long.valueOf(userId));
            userDTOS.add(BeanUtil.copyProperties(user,UserDTO.class));
        }
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        // 将消息(blog的id)推送到粉丝的收件箱中
        List<Follow> follows = followService.query().eq("follow_user_id",user.getId()).list();

        long nowTime = System.currentTimeMillis();
        for(Follow follow : follows) {
            // 粉丝id
            Long fansId = follow.getUserId();
            // 推送消息
            String key = RedisConstants.FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),nowTime);
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();

        // 用户收件箱
        String key = RedisConstants.FEED_KEY + userId;
        // 查询收件箱消息
        Set<ZSetOperations.TypedTuple<String>> blogIds =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(CollectionUtil.isEmpty(blogIds)) {
            // 收件箱没有内容，返回空
            return Result.ok();
        }
        ScrollResult ret = new ScrollResult();
        ret.setOffset(1);
        ret.setMinTime(Long.MAX_VALUE);
        List<Long> ids = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> blogId : blogIds) {
            ids.add(Long.valueOf(blogId.getValue()));
            Long score = blogId.getScore().longValue();
            if(ret.getMinTime().equals(score)) {
                ret.setOffset(ret.getOffset() + 1);
            } else {
                ret.setMinTime(score);
                ret.setOffset(1);
            }
        }
        String idsStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id",ids).last("order by field(id," + idsStr + ")").list();
        blogs.forEach(this::queryBlogUser);
        ret.setList(blogs);
        return Result.ok(ret);
    }
}
