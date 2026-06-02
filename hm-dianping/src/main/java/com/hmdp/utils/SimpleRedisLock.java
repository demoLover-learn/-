package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
  private String name;
  private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString();
    //DefaultRedisScript：spring用来执行脚本的工具类
    private static final DefaultRedisScript UNLOCK_SCRIPT;
    //static:在类第一次被加载的时候就会执行，用来给UNLOCK_SCRIPT赋值
    static {
        //创建脚本本身
        UNLOCK_SCRIPT = new DefaultRedisScript();
        //告诉脚本容器脚本文件存在的位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回值的类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    /**
     * 尝试获取锁
     * @param timeoutSet 设置过期时间
     * @return(true代表获取锁成功，false代表获取锁失败)
     */
    public boolean tryLock(Long timeoutSet) {
        //获取当前线程标识
        String currentId =ID_PREFIX+ Thread.currentThread().getId();
        //存入的value就用当前线程id
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, currentId, timeoutSet, TimeUnit.SECONDS);
        //不能直接返回success是因为Boolean转换成boolean会进行自动拆箱
        //中间可能会报空指针异常
        //这样就是判断success是不是true，是的话发返回的是基本类型的true不是的话会返回false
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    public void unLock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX+ Thread.currentThread().getId());
    }
//    /**
//     * 释放锁
//     */
//    public void unLock() {
//        //获取线程的标识
//        String currentId =ID_PREFIX+ Thread.currentThread().getId();
//       //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是锁是不是自己的
//        if(currentId.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
