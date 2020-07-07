package com.bins.easyredisson.service;

import com.bins.easyredisson.task.WatchDogTask;
import com.bins.easyredisson.util.ThreadPoolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import static com.bins.easyredisson.script.LuaScript.*;

/**
 * @author leo-bin
 * @date 2020/7/7 0:09
 * @apiNote 仿照redisson实现简易版分布式锁
 */
@Slf4j
public class EasyRedissonLock {

    private RedisTemplate redisTemplate;
    /**
     * 锁的名字
     */
    private String lockName;
    /**
     * 锁的唯一id，这里就是线程id
     */
    private String id;
    /**
     * 默认锁的释放时间：30秒
     */
    private long internalLockLeaseTime = 30 * 1000;
    /**
     * 看门狗定时任务集合
     */
    private static final ConcurrentHashMap<String, WatchDogTask> WATCH_DOGS = new ConcurrentHashMap<>(16);


    public EasyRedissonLock(String lockName, String id, RedisTemplate redisTemplate) {
        this.lockName = lockName;
        this.id = id;
        this.redisTemplate = redisTemplate;
    }


    /**
     * 尝试拿锁
     *
     * @param leaseTime 锁的释放时间(单位默认是毫秒)
     * @return 释放加锁成功
     */
    public boolean tryLock(long leaseTime) {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(LOCK_LUA, Long.class);
        //锁过期的剩余时间
        Long ttl = (Long) redisTemplate.execute(redisScript, Collections.singletonList(lockName), leaseTime, id);
        //剩余时间为null说明拿到锁了
        if (ttl == null) {
            //开启看门狗
            startWatchDogTask(leaseTime);
            return true;
        }
        return false;
    }


    /**
     * 释放锁
     */
    public void unLock() {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
        Object result = redisTemplate.execute(redisScript, Collections.singletonList(lockName), id, internalLockLeaseTime);
        //当前没有锁可以释放
        if (result == null) {
            throw new IllegalMonitorStateException("attempt to unlock lock, not locked by current thread by node id: "
                    + id + " thread-id: " + Thread.currentThread().getId());
        }
        //释放锁成功，取消看门狗的定时任务
        if (result.equals(SUCCESS)) {
            cancelWatchDogTask();
            log.info("线程：{}，锁成功被释放了！", Thread.currentThread().getName());
        }
    }


    /**
     * 看门狗任务，继续给key设置过期时间，相当于续锁
     */
    public void startWatchDogTask(long timeout) {
        //说明定时任务已经存在直接返回就行
        if (WATCH_DOGS.containsKey(lockName)) {
            return;
        }
        //异步开启一个新的看门狗线程去监听锁的状态
        WatchDogTask task = new WatchDogTask(redisTemplate, timeout, lockName, id);
        ThreadPoolUtil.getExecutor().execute(task);

        //放入看门狗集合中，如果put失败就取消任务
        if (WATCH_DOGS.putIfAbsent(lockName, task) != null) {
            task.cancelTask();
        }
    }


    /**
     * 锁已经成功被释放了，取消看门狗任务
     */
    private void cancelWatchDogTask() {
        WatchDogTask task = WATCH_DOGS.remove(lockName);
        //删除失败手动取消任务
        if (task != null) {
            task.cancelTask();
        }
    }


}
