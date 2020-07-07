package com.bins.easyredisson.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.Timer;
import java.util.TimerTask;

import static com.bins.easyredisson.script.LuaScript.*;


/**
 * @author leo-bin
 * @date 2020/7/7 0:50
 * @apiNote 看门狗任务，实现续锁
 */
@Slf4j
public class WatchDogTask implements Runnable {

    /**
     * 看门狗任务默认调度时间
     */
    private long defaultTimeout = 30 * 1000;
    private RedisTemplate redisTemplate;
    /**
     * 用户自定义调度时间
     */
    private long timeout;
    private String lockName;
    private String id;
    private Timer timer;
    private TimerTask task = new TimerTask() {
        @Override
        public void run() {
            RedisScript<Long> redisScript = new DefaultRedisScript<>(KEEP_LOCK_LUA, Long.class);
            Object result = redisTemplate.execute(redisScript, Collections.singletonList(lockName), timeout, id);

            //续锁成功
            if (SUCCESS.equals(result)) {
                log.info("看门狗线程：{}，续锁成功！", Thread.currentThread().getName());
            } else {
                log.error("无法更新锁的过期时间！");
            }
        }
    };


    @Override
    public void run() {
        watch();
    }

    public WatchDogTask(RedisTemplate redisTemplate, long timeout, String lockName, String id) {
        this.redisTemplate = redisTemplate;
        this.timeout = timeout;
        this.lockName = lockName;
        this.id = id;
    }


    /**
     * 开启看门狗任务
     */
    public void watch() {
        log.info("看门狗线程：{}已经启动", Thread.currentThread().getName());
        timer = new Timer();
        //延迟执行
        long delay = timeout / 3;
        //间隔执行
        long intervalTime = timeout / 3;

        //提交任务
        timer.scheduleAtFixedRate(task, delay, intervalTime);
    }


    /**
     * 取消定时任务
     */
    public void cancelTask() {
        if (timer != null) {
            timer.cancel();
            log.info("看门狗线程：{}的任务已经结束！", Thread.currentThread().getName());
        }
    }
}
