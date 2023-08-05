-- lua脚本，由于redis服务器保证了lua脚本执行时的原子性，同时在执行脚本时，服务器不会执行其他命令，所以不会有并发问题
-- KEYS[1]是传入的key参数，表示redis中的分布式锁对应的键，由于不同的业务和场景key值不同，所以作为传入参数由调用者传入
-- ARGV[1]是调用者线程的线程标识，由于不是redis中的key，所以由调用者通过argv传入
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁，del key
    redis.call('del', KEYS[1])
end
-- 如果释放锁失败，返回0
return 0