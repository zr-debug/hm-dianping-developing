package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //order值越小，执行的优先级越高
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(100);

        //给SpringMVC添加登录校验拦截器，且排除掉一些路径（对于一些不用登陆也可以访问的页面，不进行拦截，例如浏览
        // 店铺信息即使不登录也可以查看，所以排除掉，对于评论、付款等操作，则必须拦截，因为这些操作必须登录才被允许）
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/voucher/**"
                ).order(200);
    }
}
