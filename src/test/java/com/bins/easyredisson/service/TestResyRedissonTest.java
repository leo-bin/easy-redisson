package com.bins.easyredisson.service;

import com.bins.easyredisson.EasyRedissonApplicationTests;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import javax.annotation.Resource;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
class TestResyRedissonTest extends EasyRedissonApplicationTests {

    @Resource
    private RedisService redisService;
    private static AtomicInteger apiAccessCount = new AtomicInteger(0);


    @Test
    public void testEasyLock() {
        String redisLockKey = "redis.lock.key";
        long timeout = 5000L;

        //模拟线程一
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 50; i++) {
                    try {
                        //模拟网络延时
                        Thread.sleep(10L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (redisService.access(redisLockKey, timeout)) {
                        log.info("接口访问次数记录成功！次数+1,现在api被访问了：{}次", apiAccessCount.incrementAndGet());
                    } else {
                        log.info("接口访问次数记录失败！");
                    }
                }
                log.info("线程{}结束了，此时的api访问次数是:{}", Thread.currentThread().getName(), apiAccessCount.get());
            }
        }).start();


        //模拟线程二
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 50; i++) {
                    try {
                        //模拟网络延时
                        Thread.sleep(10L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (redisService.access(redisLockKey, timeout)) {
                        log.info("接口访问次数记录成功！次数+1,现在api被访问了：{}次", apiAccessCount.incrementAndGet());
                    } else {
                        log.info("接口访问次数记录失败！");
                    }
                }
                log.info("线程{}结束了，此时的api访问次数是:{}", Thread.currentThread().getName(), apiAccessCount.get());
            }
        }).start();

        //主线程必须处于运行状态
        for (; ; ) {

        }
    }

}