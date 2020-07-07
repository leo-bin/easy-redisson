package com.bins.easyredisson;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author leo-bin(leo_bins@163.com)
 * @date 2020/7/7 0:09
 * @apiNote easy-redisson
 * 1.仿照redisson实现简易版分布式锁
 * 2.因为已经有很多redis的客户端了，这里为了简便就没有从头开始写一个新的客户端，直接用的RedisTemplate
 * 3.因为本人的时间和能力都有限，所以有些代码写的确实不咋地，功能也是十分的简陋
 * 4.这里只是仿照redisson实现了一把可重入式分布式锁，并且自带看门狗功能
 * 5.纯属学习
 */
@SpringBootApplication
public class EasyRedissonApplication {

    public static void main(String[] args) {
        SpringApplication.run(EasyRedissonApplication.class, args);
    }

}
