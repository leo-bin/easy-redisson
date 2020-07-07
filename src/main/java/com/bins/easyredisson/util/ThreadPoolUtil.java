package com.bins.easyredisson.util;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author leo-bin
 * @date 2020/7/7 16:41
 * @apiNote
 */
@Slf4j
public class ThreadPoolUtil {

    /**
     * 手动创建线程池
     */
    private static ThreadFactory factory = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r);
        }
    };


    /**
     * 指定线程池的参数配置：
     * 1.核心线程数：2
     * 2.线程池最大线程数量：5
     * 3.非核心线程在空闲时候的存活时间：500毫秒
     * 4.阻塞队列：ArrayBlockingQueue<20>,容量设置为20
     * 5.当阻塞队列打满时的拒绝策略：DiscardOldest，抛弃年龄最大的线程
     */
    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(2,
            5,
            500L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(20),
            factory,
            new ThreadPoolExecutor.DiscardOldestPolicy());


    @PostConstruct
    public void init() {
        log.info("全局线程池:watchDogThreadPool 已经启动！");
    }


    public static ThreadPoolExecutor getExecutor() {
        return executor;
    }

}
