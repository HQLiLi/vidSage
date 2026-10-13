package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.infrastructure.persistence.entity.VideoTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code task} 表的数据访问接口：只继承 {@link BaseMapper} 获得单表 CRUD，
 * <b>不用</b> MyBatis-Plus 的 {@code IService} / {@code ServiceImpl} 泛型外壳
 * （directory-structure.md「不用 MyBatis-Plus 的 service 层抽象」）。
 */
@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskEntity> {
}
