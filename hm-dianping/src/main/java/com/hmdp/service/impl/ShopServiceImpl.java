package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
   @Resource
   private StringRedisTemplate stringRedisTemplate;
    /**
     * 根据id查缓存
     * @param id
     * @return
     */
    public Result queryById(Long id) {
//       // 缓存穿透
//        Shop shop = queryWithPassThrough(id);

//        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
            //返回
        return Result.ok(shop);

    }
    //互斥锁解决缓存击穿方法
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，不存在的话
        if(StrUtil.isNotBlank(shopJson)){
            //存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);}
        //判断命中的是不是空值
        //上面isnotblack就是判断含有数据的，只有有数据的时候才会返回true,null和\t\n,""都是false
        //而这个shopjson!=null,说明shopjson含有的是\t\n以及"",就是redis中所设置的的空数据，然后直接返回错误就行了
        if(shopJson!=null){
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop=null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否成功
            //4.3如果失败则休眠，并重新从redis中查询
            while (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4如果成功，查看redis中是否存在缓存信息，存在的话返回，不存在的话查询数据库
            String shopJson1 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson1)) {
                return JSONUtil.toBean(shopJson1, Shop.class);
            }
            // 从数据库查询对应的信息
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            //如果数据库不存在，抛出错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                return null;
            }
            //如果存在把查到的信息插入到redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + RandomUtil.randomLong(10, 20), TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw  new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);

        }
        //把信息返回
        return shop;

    }
//定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //不存在直接返回
            return null;
        }
        //命中需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonShop =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonShop, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return shop;
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
            Shop shop1 = JSONUtil.toBean((JSONObject) rd.getData(), Shop.class);
            if (expireTime1.isAfter(LocalDateTime.now())) {
                //未过期，直接返回店铺信息
                return shop1;
            }
            //过期，获取到锁的话，开启新线程，重写缓存信息
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{saveShop2Redis(id,20L);}catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);}});}
        //返回过期的店铺信息
        return shop;

    }
    //把shop添加到redis当中
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        //模拟真实状况休眠一下
        Thread.sleep(200);
        //2.查询逻辑国企
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }



    //缓存穿透函数封装
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，不存在的话
        if(StrUtil.isNotBlank(shopJson)){
            //存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断命中的是不是空值
        //上面isnotblack就是判断含有数据的，只有有数据的时候才会返回true,null和\t\n,""都是false
        //而这个shopjson!=null,说明shopjson含有的是\t\n以及"",就是redis中所设置的的空数据，然后直接返回错误就行了
        if(shopJson!=null){
            return null;
        }
        //不存在，从数据库查询对应的信息
        Shop shop = getById(id);
        //如果数据库不存在，抛出错误
        if(shop ==null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",2,TimeUnit.MINUTES);

            return null;
        }
        //如果存在把查到的信息插入到redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL+ RandomUtil.randomLong(10,20), TimeUnit.MINUTES);
        //把信息返回
        return shop;
    }

    //获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);}
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    /**
     * 店铺更新
     * @param shop
     * @return
     */
    @Transactional
    public Result updateShop(Shop shop) {
        Long shopId = shop.getId();
        if(shopId == null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ shopId);

        return Result.ok();
    }
}
