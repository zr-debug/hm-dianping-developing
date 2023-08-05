package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 *     拦截所有路径，作用：每次用户访问hmdp，则对redis中的缓存过期时间做一次刷新，若用户在过期时间内有访问操作，则重置过期时间
 * </p>
 *
 * @author 阿瑞
 * @since 2023-6-27
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");

        if(token == null || token.isEmpty()) {
            //没有token，说明还没有登陆，放行
            return true;
        }
        Map<Object, Object> entries =
                stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);

        if(entries.isEmpty()) {
            //用户不存在，直接放行，让后面的拦截器去处理
            return true;
        }

        //更新redis过期时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        UserDTO user = BeanUtil.toBean(entries, UserDTO.class);
        //用户存在，将用户信息存入ThreadLocal，防止多线程并发访问ThreadLocal时的资源争用
        UserHolder.saveUser(user);
        //放行
        return true;
    }
}
