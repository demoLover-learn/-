package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {


    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        //添加redis地址,这里添加了单点地址
        config.useSingleServer().setAddress("redis://192.168.200.15:6379").setPassword("123456");
        //创建客户端
        return Redisson.create(config);
    }
}
