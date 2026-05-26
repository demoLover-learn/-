package com.hmdp.service.impl;


import cn.hutool.core.util.RandomUtil;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result typeList() {
        //查询redis中有没有缓存数据
        String str = stringRedisTemplate.opsForValue().get("cache:shopList");
        //判断是否为空，不为空直接返回
        if (str != null) {
            List<ShopType> list = JSONUtil.toList(str, ShopType.class);
            return Result.ok(list);
        }else{

        }
        //为空查询数据库，
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //判断数据库中如果不存在抛出异常
        if(shopTypeList==null || shopTypeList.isEmpty()){
            return  Result.fail("数据不存在");
        }
        String jsonStr = JSONUtil.toJsonStr(shopTypeList);
        //存在直接存入到redis缓存中
        stringRedisTemplate.opsForValue().set("cache:shopList", jsonStr, RandomUtil.randomLong(10,20), TimeUnit.MINUTES);
        //返回
        return Result.ok(shopTypeList);
    }
    /**查询集合列表cache:shopList
     * @return
     */
//    public Result typeList() {
//       //查看redis中是否存在集合
//        List<String> shopList = stringRedisTemplate.opsForList().range("cash:shoplist", 0, -1);
//        //如果存在直接返回
//        if(shopList != null && shopList.size() > 0) {
//            List<ShopType> collect = shopList.stream()
//                    .map(json -> JSONUtil.toBean(json, ShopType.class))
//                    .collect(Collectors.toList());
//            return Result.ok(collect);}
//        //如果不存在，查询数据库
//        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
//        //判断查询出来的集合是否为空，为空抛出错误
//        if(shopTypeList==null || shopTypeList.isEmpty()) {
//            return Result.fail("集合为空不存在");}
//        //不为空，插入到redis缓存中
//        List<String> list = shopTypeList.stream()
//                .map(JSONUtil::toJsonStr)
//                .collect(Collectors.toList());
//        stringRedisTemplate.opsForList().leftPushAll("cash:shoplist", list);
//        //返回结果对象
//        return Result.ok(shopTypeList);
//    }

}
