package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

@Resource
private IUserService userService;
@Resource
private StringRedisTemplate stringRedisTemplate;
@Resource
private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查看详细信息
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
       //1.查询id
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //查询blog是否被点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //用户未登录，无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key="block:liked"+blog.getId();
        //判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    /**
     * 判断用户是否点赞
     * @param id
     * @return
     */
    @Override
    public Result weatherLike(Long id) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
         String key="block:liked"+id;
        //判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //如果没有点赞，数据库字段加1，把用用户加入set集合//zset
        if (score==null) {
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //如果已经点赞，数据库字段-1，把用户移除集合
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogByLikes(Long id) {
        String key="block:liked"+id;
        //查询top5的点赞用户
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if ((range==null||range.isEmpty())){
            return Result.ok(Collections.emptyList());
        }
        //解析出用户id
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据用户id查询用户
        List<User> users = userService.query().in("id",ids)
                .last("ORDER BY FIELD(id,"+idStr+")").list();
        List<UserDTO> userDTOS = users
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return  Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }
        //查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有的粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String Key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(Key,blog.getId().toString(),System.currentTimeMillis());
        }
        //返回id
       return Result.ok(blog.getId());
    }
    /**
     * 滚动查询
     * @param max
     * @param offSet
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offSet) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //查询收件箱///zreverseRangeByscore key max min limit offset count
        String Key="feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(Key, 0, max, offSet, System.currentTimeMillis());
        //判断是否为空
        if (typedTuples==null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
       long minTime=0;
        int os= 1;
        List<Long> ids=new ArrayList<>(typedTuples.size());
        //解析收件箱 blogId,(时间戳)最小时间戳，offset//
        for (ZSetOperations.TypedTuple<String> tuple: typedTuples) {
            //获取id
            Long blogId =Long.valueOf(tuple.getValue());
            ids.add(blogId);
            //获取分数
            long time = tuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else {
                minTime=time;
                os=1;
            }
        }
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        //根据id查询blog
        List<Blog> blogs =query().in("id",ids)
                .last("ORDER BY FIELD(id,"+idStr+")").list();
        //看看blog是否被点赞
        for (Blog blog : blogs) {
            Long usersId = blog.getUserId();
            User user = userService.getById(usersId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询blog是否被点赞了
            isBlogLiked(blog);
        }
        //封装结果并且返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);

    }
}


