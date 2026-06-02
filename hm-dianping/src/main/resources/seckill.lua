--优惠券id
local voucherId=ARGV[1]
--用户id
local userId=ARGV[2]
--订单id
local orderId=ARGV[3]
--数据的key
--库存的key
local stockKey='seckill:stock:'..voucherId
--订单的key
local orderKey='seckill:order:'..voucherId
--脚本业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey))<=0)then
    return 1
end
if(redis.call('sismember',orderKey,userId)==1)then
    return 2
end
--扣减库存
redis.call('incrby',stockKey,-1)
--把userid加入set集合
redis.call('sadd',orderKey,userId)
--发送消息到队列中
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
