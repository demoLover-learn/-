package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {
    /**
     * 实现关注业务
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);
    /**
     * 实现查看是否关注业务
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);
    /**
     * 共同关注
     * @param id
     * @return
     */
    Result followCommons(Long id);
}
