package com.bins.easyredisson.script;

/**
 * @author leo-bin
 * @date 2020/7/7 0:22
 * @apiNote 封装一些lua脚本
 */
public interface LuaScript {

    Long SUCCESS = 1L;

    /**
     * 获取锁
     *
     * @apiNote 理解：
     * 这段lua脚本可以分为三个部分去理解
     * 第一部分就是第一个if ... then ... end，大概就是判断key是否存在，不存在才能接着往下走，否则就走下一个if逻辑了
     * 第二部分就是第二个if ... then ... end，这里就是判断是否进入重入的逻辑
     * 如果第二个参数相同的话，说明是同一个线程，那就会将原来的key中的值+1
     * 第三部分就是最后的return语句了，这里就是获取当前key的剩余过期时间并将之返回
     * ARGV[1]=key的过期时间
     * ARGV[2]=key的值
     * pexpire和expire作用相同，不过时间单位默认是毫秒，后者则是秒
     * pttl：获取key的过期时间
     */
    String LOCK_LUA = "if (redis.call('exists', KEYS[1]) == 0) then " +
            "redis.call('hset', KEYS[1], ARGV[2], 1); " +
            "redis.call('pexpire', KEYS[1], ARGV[1]); " +
            "return nil; " +
            "end; " +
            "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
            "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
            "redis.call('pexpire', KEYS[1], ARGV[1]); " +
            "return nil; " +
            "end; " +
            "return redis.call('pttl', KEYS[1]);";


    /**
     * 续锁，重新给key续上过期时间
     */
    String KEEP_LOCK_LUA = "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
            "redis.call('pexpire', KEYS[1], ARGV[1]); " +
            "return 1; " +
            "end; " +
            "return 0;";


    /**
     * 释放锁
     *
     * @apiNote 理解：
     * 第一个if逻辑是判断key是否存在，如果不存在就直接返回1表示释放锁成功
     * 第二个if逻辑是判断key中的value是否和当前线程id一致，如果不一致说明不能释放锁，返回空
     * 第三个if逻辑中设置了一个本地counter变量，用来记录锁的重入次数，每次都执行counter-1的操作
     * 只有当counter<=0时才能删除这个key，并返回1表示成功
     * 如果counter>0则重新设置过期时间，让其他线程来释放这个锁，并返回0，表示释放锁失败
     */
    String UNLOCK_LUA = "if (redis.call('exists', KEYS[1]) == 0) then " +
            "return 1; " +
            "end;" +
            "if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then " +
            "return nil;" +
            "end; " +
            "local counter = redis.call('hincrby', KEYS[1], ARGV[1], -1); " +
            "if (counter > 0) then " +
            "redis.call('pexpire', KEYS[1], ARGV[2]); " +
            "return 0; " +
            "else " +
            "redis.call('del', KEYS[1]); " +
            "return 1; " +
            "end; " +
            "return nil;";


}
