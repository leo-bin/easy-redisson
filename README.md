# easy-redisson
**仿照redisson实现了一个基于RedisTemplate的分布式锁，实现可重入式锁，看门狗机制**



## 前言

```xml
首先Redisson是一个使用Java实现的redis客户端工具。
但是我们之前的项目中已经使用了Jedis和RedisTemplate作为redis客户端了，感觉在使用Redisson的话就有点重复了。
一个稳定可用的分布式锁需要解决因为网络或者其他原因导致的断线重连，过期续锁的功能，而Jedis和RedisTemplate都无法提供支持。
所以这里打算自己写一个基于Jedis的分布式锁。
```



**既然是仿照，那就非常有必要撸一把Redisson的源码了**

**所以这里我们从源码开始一步一步的解读Redisson是如何实现分布式锁的**



**先看一个整体的流程图**

![](https://bins-pic.oss-cn-shanghai.aliyuncs.com/mypic/redisson执行流程图.jpg)



## 加锁

![](https://bins-pic.oss-cn-shanghai.aliyuncs.com/mypic/Redisson加锁流程图.png)

```java
1.每次加锁时都会向redis服务器发送一段lua脚本，这段脚本的功能就是给指定的某个key设置一个随机值，并且设置过期时间。
2.脚本首先会判断key是否已经存在，如果存在说明有其他线程正在执行任务，那就原地阻塞或者不等待直接返回false，表示获取锁失败。
3.如果不存在，那就直接set值。并设置key的过期时间。
```

**脚本如下：**

```java
这段lua脚本可以分为三个部分去理解：

第一部分：
第一个if ... then ... end，大概就是判断key是否存在
不存在才能接着往下走，否则就走下一个if逻辑了。

第二部分：
就是第二个if ... then ... end，这里就是判断是否进入重入的逻辑
如果第二个参数相同的话，说明是同一个线程，那就会将原来的key中的值+1。

第三部分：
就是最后的return语句了，这里就是获取当前key的剩余过期时间并将之返回。

ARGV[1]=key的过期时间
ARGV[2]=key的值
pexpire和expire作用相同，不过时间单位默认是毫秒，后者则是秒
pttl：获取key的过期时间
```

```java
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
```



**执行完之后，现在redis中key的结构可以理解成这样子：**

```java
mylock：{
“i554d4fd5f4d54f5d4f54544”:{1}
}
```

相当于：

**<key,<key,value>>**

其中第一个key指的是分布式锁的key

第二个key指的是分布式锁的唯一id(每次获取锁的时候动态生成的，可以理解为线程id)

最后一个value就是当前这个线程所持有锁的重入次数



## 可重入式机制

这里的可重入式机制就是依赖于上面说过的LUA脚本的第二段lf逻辑：

```java
"if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
            "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
            "redis.call('pexpire', KEYS[1], ARGV[1]); " +
            "return nil; " +
            "end; " +
```





## 释放锁

既然之前一个分布式锁有了重入机制，原理就是不断的对value值+1

那么这里只要依次执行-1操作就行了，直到判断出这个时候的counter<=0说明可以放心的删掉这个锁了

同样的，我们照样可以使用一段lua脚本来实现：

**解释：**

第一个if逻辑是判断key是否存在，如果不存在就直接返回1表示释放锁成功。

第二个if逻辑是判断key中的value是否和当前线程id一致，如果不一致说明不能释放锁，返回空。

第三个if逻辑中设置了一个本地counter变量，用来记录锁的重入次数，每次都执行counter-1的操作。

只有当counter<=0时才能删除这个key，并返回1表示释放锁成功。

如果counter>0则重新设置过期时间，让其他线程来释放这个锁，并返回0，表示释放锁失败



![](https://bins-pic.oss-cn-shanghai.aliyuncs.com/mypic/Redisson解锁流程图.png)

```java
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
```







## 过期续锁（看门狗机制）

之所以有看门狗机制的出现，就是为了应对锁过期了，但是业务还未执行完毕。

这个时候就可以增加一个监听器，每隔一定的时间去查看现在的key的过期时间是否到了。

如果没到，那就自动更新锁的过期时间。

同样的，我们还是可以使用LUA脚本进行逻辑的封装：

```java
    String KEEP_LOCK_LUA = "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
            "redis.call('pexpire', KEYS[1], ARGV[1]); " +
            "return 1; " +
            "end; " +
            "return 0;";
```



## 完整的实现

**先将上面提到的LUA脚本进行封装：**

```java
public interface LuaScript {
    Long SUCCESS = 1L;

    /**
     * 获取锁
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
```



### 1）加锁

加锁的逻辑其实很简单，大部分其实已经封装在了LUA脚本中了

这边只要判断执行结果并启动看门狗任务就行





**关键代码：**

```java
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
```



### 2）看门狗机制

这里不同于源码的实现，为了图简单，我这里是配置了一个全局线程池

并异步的去开启一个看门狗线程，同时启动一个定时器Timer(jdk自带的)

每隔一段时间就去查询key的过期时间，如果还没过期的话，那就执行续锁的逻辑

这个续锁的逻辑同样封装在了LUA脚本中了



除此之外，这里还参照了源码的一个细节

就是为了实现重入式锁的功能，如果每次执行拿锁的动作就要开启一个看门狗任务

理论其实上一个线程只要一个看门狗线程就行了

那如果还是一个线程但是开启了多个看门狗任务怎么办？

简单！我们可以用一个Map来存所有的看门狗任务！

每次开启看门狗任务之前只需要通过Contains方法判断任务是否存在过就行

考虑到多线程的环境下，这里使用ConcurrentHashMap来存：

**<key就是锁的名字，value就是一把锁对应的看门狗任务>**

```java
/**
 * 看门狗定时任务集合
 */
private static final ConcurrentHashMap<String, WatchDogTask> WATCH_DOGS = new ConcurrentHashMap<>(16);
```



**关键代码：**

```java
    /**
     * 看门狗任务，继续给key设置过期时间，相当于续锁
     */
    public void startWatchDogTask(long timeout) {
        //说明定时任务已经存在直接返回就行
        if (WATCH_DOGS.containsKey(lockName)) {
            return;
        }
        //通过全局线程池，异步开启一个新的看门狗线程去监听锁的状态
        WatchDogTask task = new WatchDogTask(redisTemplate, timeout, lockName, id);
        ThreadPoolUtil.getExecutor().execute(task);

        //放入看门狗集合中，如果put失败就取消任务
        if (WATCH_DOGS.putIfAbsent(lockName, task) != null) {
            task.cancelTask();
        }
    }
```



**这里给出WatchDogTask的源码：**

```java
@Slf4j
public class WatchDogTask implements Runnable {
    /**
     * 看门狗任务默认调度时间
     */
    private long defaultTimeout = 30 * 1000;
    private RedisTemplate redisTemplate;
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
    
    public WatchDogTask(RedisTemplate redisTemplate, long timeout, String lockName, String id) {
        this.redisTemplate = redisTemplate;
        this.timeout = timeout;
        this.lockName = lockName;
        this.id = id;
    }

    
    @Override
    public void run() {
        watch();
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
```



### 3）释放锁

释放锁没有什么重要的地方需要讲解了，直接看代码就行

无非就是执行脚本，判断执行结果就行了



**关键代码：**

```java
    public void unLock() {
        RedisScript<Long> redisScript = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
        Object result = redisTemplate.execute(redisScript, Collections.singletonList(lockName), id, internalLockLeaseTime);
        //当前没有锁可以释放
        if (result == null) {
            throw new IllegalMonitorStateException("attempt to unlock lock, not locked by current thread by node id: "+ id + " thread-id: " + Thread.currentThread().getId());
        }
        //释放锁成功，取消看门狗的定时任务
        if (result.equals(SUCCESS)) {
            cancelWatchDogTask();
            log.info("线程：{}，锁成功被释放了！", Thread.currentThread().getName());
        }
    }
```



```java
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
```





## 测试

为了测试方便，我们可以将分布式锁的功能进行封装

**值得一提的是我们并没有在获取锁失败的时候不断的进行重试，二是直接将失败的结果返回**

**之所以这么做的愿意就是这里通过返回的结果进行进行自定义的重试机制**

**比如说这里设置了一旦失败了，最多可以重试30次，超过30次就直接返回获取锁失败**

```java
@Slf4j
@Service
public class RedisService {
    @Resource
    private EasyRedissonClient easyRedissonClient;
    //最大重试次数
    private static final int MAX_RETRY_COUNT = 30;
    //使用ThreadLocal来记录每一个线程的尝试次数
    ThreadLocal<Integer> count = new ThreadLocal<>();


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
```



### 1）测试代码

**这里用的SpringBoot中自带的单元测试**

同时开启两个线程去竞争锁

每抢到一次锁就记录下访问次数

每个线程执行50次，所以最后的记录结果一定是100

```java
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
```



### 2）测试结果

<img src="https://bins-pic.oss-cn-shanghai.aliyuncs.com/mypic/redis实现分布式锁测试结果.png" style="zoom:200%;" />



**最后的结果：**

<img src="https://bins-pic.oss-cn-shanghai.aliyuncs.com/mypic/redis实现分布式锁测试结果2.png" style="zoom:200%;" />





## 最后

当然，这里实现的很简陋，还有很多功能都没有实现。

比如说如何应对网络异常的问题还有主从模式下出现的同一把锁被多个节点拿到的问题等等。

**所有的代码都放在了github上，感兴趣可以pull         https://github.com/leo-bin/easy-redisson.git**

**真没想到秋招提前批这么快就开始了，加油吧！**









## 参考

1.https://mp.weixin.qq.com/s/y_Uw3P2Ll7wvk_j5Fdlusw

2.[http://ifeve.com/%E6%85%A2%E8%B0%88-redis-%E5%AE%9E%E7%8E%B0%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81-%E4%BB%A5%E5%8F%8A-redisson-%E6%BA%90%E7%A0%81%E8%A7%A3%E6%9E%90/](http://ifeve.com/慢谈-redis-实现分布式锁-以及-redisson-源码解析/)

3.https://blog.csdn.net/ice24for/article/details/86177152

4.https://juejin.im/post/5e6727e16fb9a07cc845b9ba



