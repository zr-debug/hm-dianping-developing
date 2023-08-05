-- 检查优惠券下单资格
-- 参数列表
-- KEYS[1] 优惠券库存的key
-- KEYS[2] 下单用户集合的key
-- ARGV[1] 优惠券id
-- ARGV[2] 用户id
-- ARGV[3] 订单id
-- 返回值
-- 0 成功，满足下单条件
-- 其他值 失败，不满足下单条件
if(tonumber(redis.call('get', KEYS[1])) <= 0) then
    -- 库存不足
    return 1
end

if(redis.call('sismember', KEYS[2], ARGV[2]) == 1) then
    -- 重复下单
    return 2
end

-- 满足下单条件
-- 扣减库存
redis.call('incrby', KEYS[1], -1)
-- 加入优惠券下单用户集合
redis.call('sadd', KEYS[2], ARGV[2])

-- 向redis的stream消息队列中pub消息（xadd stream.orders * k1 v1 k2 v2 ...）
redis.call('xadd', 'stream.orders', '*', 'voucherId', ARGV[1], 'userId', ARGV[2], 'id', ARGV[3])

return 0