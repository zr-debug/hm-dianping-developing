package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import com.hmdp.utils.RedisConstants;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * <p>
     *     判断电话号码格式是否正确，如果正确，则发送验证码
     * </p>
     *
     * @param phone
     * @param session
     * @return Result
     * @author 阿瑞
     * @since 2023-6-23
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //先判断电话格式是否正确
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("电话号码错误！");
        }
        //电话号码格式正确，通过hutool的RandomUtil工具类生成6位短信验证码
        String code = RandomUtil.randomNumbers(6);
        //将电话号码和生成的短信验证码保存到session中
//        session.setAttribute("phone",phone);
//        session.setAttribute("code",code);

        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        //将短信验证码保存到redis中，以电话号码为key来保存
        stringRedisTemplate.opsForValue().set(key, code);
        //设置一个验证码有效时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //模拟发送短信验证码
        log.debug("短信验证码：" + code + "，验证码将会在两分钟后过期");
        return Result.ok();
    }

    @Override
    public Result sign() {
        return null;
    }

    /**
     * <p>
     *     用户登陆
     * </p>
     *
     * @param loginForm
     * @param session
     * @return Result
     * @author 阿瑞
     * @since 2023-6-23
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
//        if(phone == null || !phone.equals(session.getAttribute("phone"))) {
//            return Result.fail("电话号码错误！");
//        }
//        String code = loginForm.getCode();
//        if(code == null || !code.equals(session.getAttribute("code"))) {
//            return Result.fail("短信验证码错误！");
//        }

        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        String code = stringRedisTemplate.opsForValue().get(key);
        if(code == null) {
            return Result.fail("手机号错误或验证码已过期！");
        }
        if(!code.equals(loginForm.getCode())) {
            return Result.fail("验证码错误！");
        }

        //通过电话号码检查数据库中是否已经存在该用户（即该用户是否已经有账号）
        User user = query().eq("phone", phone).one();

        //若用户账号不存在，则说明用户是第一次登陆，给用户创建一个账号并存到数据库
        if(user == null) {
            user = createUserWithPhone(phone);
        }

//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        //生成一个随机token，以token作为键将user存入redis中，然后将token返回前端
        //在后面的每次请求中，前端都会在http请求头的authorization首部字段中携带token值
        String token = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token,
                BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor(
                            (filedKey, filedName)-> filedName.toString())));
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        //随机生成一个用户名
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        LocalDateTime now = LocalDateTime.now();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        //存入数据库
        save(user);
        return user;
    }
}
