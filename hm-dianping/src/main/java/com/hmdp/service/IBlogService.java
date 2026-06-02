package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /**
     * 热点信息
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 查询商品列表
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 判断用户是否点赞
     * @param id
     * @return
     */
    Result weatherLike(Long id);

    /**
     * 查询点赞用户
     * @param id
     * @return
     */
    Result queryBlogByLikes(Long id);

    /**
     * 保存
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);
    /**
     * 滚动查询
     * @param max
     * @param offSet
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offSet);
}
