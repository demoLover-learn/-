package com.hmdp.utils;


public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSet 设置过期时间
     * @return(true代表获取锁成功，false代表获取锁失败)
     */
    boolean tryLock(Long timeoutSet);

    /**
     * 释放锁
     */
    void unLock();
}
