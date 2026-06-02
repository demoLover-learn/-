package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    private static  final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //线程池,singleThreadExecute：单线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    @PostConstruct//在当前类初始化完毕之后直接来做
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }

    private class VoucherOrderHandle implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                try{//1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if (list==null|| list.isEmpty()){
                        //如果获取失败说明没消息，继续下一次循环
                        continue;
                    }
                    //3.解析订单中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果获取成功可以下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                }catch (Exception e){
                log.error("处理订单异常");
                handlePendingList();
                }

            }
        }
        private void handlePendingList(){
            while(true){
                try{//1.获取pend-list中的订单信息 XREADGROUP GROUP g1 c1 count 1 streams stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if (list==null|| list.isEmpty()){
                        //如果获取失败说明没消息，继续下一次循环
                       break;
                    }
                    //3.解析订单中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果获取成功可以下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                }catch (Exception e){
                    log.error("处理penging-list异常");
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }

            }
        }


    }


//    //阻塞队列特点，当一个线程从队列中获取元素的时候，当队列中没有元素的时候
//    //这个线程就会被阻塞，直到队列中有元素才会被唤醒
//    private BlockingQueue<VoucherOrder> orderTask=new ArrayBlockingQueue<>(1024*1024);
//    private class VoucherOrderHandle implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//                try{//1.获取订单中的订单信息
//                    VoucherOrder voucherOrder = orderTask.take();
//                //创建订单
//                handleVoucherOrder(voucherOrder);
//                }catch (Exception e){
//                log.error("处理订单异常");
//                }
//
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //获取锁
        RLock lock = redissonClient.getLock("lock:orders:" + userId);
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            //失败
            log.error("不允许重复下单");
            return ;
        }
           try{
            proxy.createVoucherOrder(voucherOrder);}
           finally {
               //释放锁
               lock.unlock();
           }

    }
    private  IVoucherOrderService proxy;

    /**
     * 秒杀下单业务逻辑
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),String.valueOf(orderId)
        );
        //判断结果是否为0,不为零返回异常信息
        int r = result.intValue();
        if (r !=0) {
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        //获取代理对象
        //获取代理对象（事务）
        proxy =(IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok();

    }

    //    public Result seckillVoucher(Long voucherId) {
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        //判断结果是否为0,不为零返回异常信息
//        int r = result.intValue();
//        if (r !=0) {
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //为0将优惠卷id用户id订单id村存入阻塞队列
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        //订单id
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //2.6创建阻塞队列,放入阻塞队列
//        orderTask.add(voucherOrder);
//        //获取代理对象
//        //获取代理对象（事务）
//      proxy =(IVoucherOrderService) AopContext.currentProxy();
//        //返回订单id
//        return Result.ok();
//
//    }

    /**
     * 查询秒杀相关信息
     *
     * @param voucherOrder
     * @return
     */
//
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀时间是否开启
//        Integer stock = voucher.getStock();
//
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开启返回异常
//            return Result.fail("活动暂未开启");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //未开启返回异常
//            return Result.fail("活动已经结束");
//        }
//        //判断库存是否充足
//        if (stock < 1) {
//            //数量不足返回异常
//            return Result.fail("已售罄");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //获取锁
//        RLock lock = redissonClient.getLock("lock:orders:" + userId);
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if (!isLock) {
//            return Result.fail("一个用户只允许下一单");
//        }
//           try{ //获取代理对象（事务）
//            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);}
//           finally {
//               //释放锁
//               lock.unlock();
//           }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
    //根据优惠券id和用户id查询订单 一人一单子
    Long userId = voucherOrder.getUserId();
        //查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //订单存在，返回错误
        if (count > 0) {
           log.error("用户已经下单过一次了");
            return;
        }
        //判断是否存在
        //充足扣减数据库库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            //数量不足返回异常
            log.error("库存不足");
            return ;
        }

        save(voucherOrder);

}
}