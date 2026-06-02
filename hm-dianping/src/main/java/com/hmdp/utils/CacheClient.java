package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j

public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    //将任意java对象序列化为json并储存在string类型的key中，并设置过期时间
    public void set(String key,Object value,Long time,TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }
    //将任意java对象序列化为json并储存在string类型的key中,并设置逻辑过期
    public void setWithLogicExpire(String key,Object value,Long time,TimeUnit timeUnit) {
        //用redisdata进行封装
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入reis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透工具类封装
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFailback,
                                         Long time,TimeUnit timeUnit){
        String key =keyPrefix + id;
        //从redis查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，不存在的话
        if(StrUtil.isNotBlank(json)){
            //存在直接返回
            return JSONUtil.toBean(json,type);

        }
        //判断命中的是不是空值
        //上面isnotblack就是判断含有数据的，只有有数据的时候才会返回true,null和\t\n,""都是false
        //而这个shopjson!=null,说明shopjson含有的是\t\n以及"",就是redis中所设置的的空数据，然后直接返回错误就行了
        if(json!=null){
            return null;
        }
        //不存在，从数据库查询对应的信息
        R r=dbFailback.apply(id);

        //如果数据库不存在，抛出错误
        if(r ==null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",2,TimeUnit.MINUTES);

            return null;
        }
        //如果存在把查到的信息插入到redis
      set(key,r,time,timeUnit);
        //把信息返回
        return r;
    }


    //定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期
    public <R,ID> R queryWithLogicalExpire(String keyPrefixed, ID id, Class<R> type, Function<ID,R> dbFailback,
                                           Long time,TimeUnit timeUnit){
        String key =keyPrefixed  + id;
        //从redis查缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isBlank( Json)){
            //不存在直接返回
            return null;
        }
        //命中需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean( Json, RedisData.class);
        JSONObject jsonShop =(JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonShop,type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return r;
        }

        //过期的话，需要缓存重建
        //缓存重建
        //获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取到锁之后要先检测redis缓存是否过期
            String reCheck=stringRedisTemplate.opsForValue().get(key);
            RedisData rd = JSONUtil.toBean(reCheck, RedisData.class);
            LocalDateTime expireTime1 = rd.getExpireTime();
           R r1 = JSONUtil.toBean((JSONObject) rd.getData(), type);
            if (expireTime1.isAfter(LocalDateTime.now())) {
                //未过期，直接返回店铺信息
                return r1;
            }
            //过期，获取到锁的话，开启新线程，重写缓存信息
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{//查询数据库
                    R r2 = dbFailback.apply(id);
                    //写入redis
                    setWithLogicExpire(key,r2,time,timeUnit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);}});}
        //返回过期的店铺信息
        return r;

    }
    //获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);}
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }






}
