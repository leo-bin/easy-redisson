package com.bins.easyredisson.service;

import com.bins.easyredisson.util.CommonUtil;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


/**
 * @author leo-bin
 * @date 2020/7/7 0:40
 * @apiNote
 */
@Component
public class EasyRedissonClient {

    @Resource
    private RedisTemplate redisTemplate;

    /**
     * 根据key拿到一个分布式锁
     *
     * @param key 锁的key
     * @return 分布式锁
     */
    public EasyRedissonLock getLock(String key) {
        return new EasyRedissonLock(key, CommonUtil.UUID32(), redisTemplate);
    }

}
