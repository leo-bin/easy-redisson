package com.bins.easyredisson.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @author leo-bin
 * @date 2020/7/7 14:59
 * @apiNote 使用分布式锁
 */
@Slf4j
@Service
public class RedisService {

    @Resource
    private EasyRedissonClient easyRedissonClient;
    private static final int MAX_RETRY_COUNT = 30;
    ThreadLocal<Integer> count = new ThreadLocal<>();


    /**
     * 使用redisson实现的分布式锁
     */
    public boolean access(String key, long timeout) {
        EasyRedissonLock easyLock = easyRedissonClient.getLock(key);
        count.set(0);
        try {
            //原地自旋去拿锁
            while (!easyLock.tryLock(timeout)) {
                count.set(count.get() + 1);
                log.info("线程：{}抢占锁失败！再次尝试获取锁！重试次数：{}", Thread.currentThread().getName(), count.get());
                //自旋超过30次则自动失败
                if (count.get() == MAX_RETRY_COUNT) {
                    log.error("重试次数超过了最大限制：{}", MAX_RETRY_COUNT);
                    return false;
                }
            }
            log.info("线程：{}，成功拿到分布式锁啦！", Thread.currentThread().getName());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            easyLock.unLock();
        }
    }

}
