package redis.project.lock;

import redis.RedisUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.ZParams;

import java.util.List;
import java.util.UUID;

/**
 * 公平信号量锁
 * 限制一个资源最多同时能被多少个线程访问
 *
 * @author joy
 * @time 2019/10/17 16:55
 */
public class CountingFairSemaphore {

    /**
     * 允许访问计数
     */
    public static final int LIMIT = 6;

    /**
     * 超时时间
     */
    public static final int TIME_OUT = 1000;

    /**
     * 获取公平信号量锁
     *
     * @param lockName 锁名
     * @return
     */
    public static String acquireSemaphore(String lockName) {
        String uniqueId = UUID.randomUUID().toString();
        String lockOwnerKey = "SEMAPHORE:" + lockName + ":owner";
        String lockKey = "SEMAPHORE:" + lockName;
        String countKey = "SEMAPHORE_COUNT:" + lockName;
        long currentTime = System.currentTimeMillis();
        Jedis redis = RedisUtil.getRedis();

        Transaction trans = redis.multi();
        trans.zremrangeByScore(lockKey, 0, currentTime - TIME_OUT);

        // 将 (lockOwnerKey 里的 score * 1) + ( lockKey 里的 score * 0) 的交集写入 lockOwnerKey
        // 保证了在有效时间内按照先后顺序获得锁,而不会按时间来获取锁
        trans.zinterstore(lockOwnerKey, new ZParams().weightsByDouble(1, 0), lockOwnerKey, lockKey);

        // 自增计数器
        trans.incr(countKey);
        List<Object> results = trans.exec();
        int counter = ((Long) results.get(results.size() - 1)).intValue();

        // 将 [id => 当前时间] 写入计数信号量有序集合
        // 将 [id => 自增计数器] 写入计数信号量(所有者)有序集合
        // 如果写入成功计算是否超出上线, 超出上限则删除
        trans = redis.multi();
        trans.zadd(lockKey, currentTime, uniqueId);
        trans.zadd(lockOwnerKey, counter, uniqueId);
        trans.zrank(lockOwnerKey, uniqueId);
        results = trans.exec();
        int result = ((Long) results.get(results.size() - 1)).intValue();

        // 成功 取得信号量
        if (result < LIMIT) {
            return uniqueId;
        }

        // 失败 删除清除信号量
        trans = redis.multi();
        trans.zrem(lockKey, uniqueId);
        trans.zrem(lockOwnerKey, uniqueId);
        trans.exec();
        return null;
    }


    /**
     * 释放公平信号量锁
     *
     * @param lockName 锁名
     * @param uniqueId 信号量
     */
    public static void releaseSemaphore(String lockName, String uniqueId) {
        Transaction trans = RedisUtil.getRedis().multi();
        trans.zrem("SEMAPHORE:" + lockName + ":owner", uniqueId);
        trans.zrem("SEMAPHORE:" + lockName, uniqueId);
        trans.exec();
    }
}
