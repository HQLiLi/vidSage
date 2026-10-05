package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.infrastructure.persistence.entity.VideoTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code task} 表的数据访问接口：只继承 {@link BaseMapper} 获得单表 CRUD，
 * <b>不用</b> MyBatis-Plus 的 {@code IService} / {@code ServiceImpl} 泛型外壳
 * （directory-structure.md「不用 MyBatis-Plus 的 service 层抽象」）。
 *
 * <p>可见性边界（硬规则）：仅 {@code infrastructure/persistence/repository/} 可注入，{@code application/} 禁止出现
 * 本接口与 BaseMapper / Wrapper 等 MP 类型。{@code @Mapper} 交给启动类的全量扫描发现，
 * 不另写 {@code @MapperScan}。
 */
@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskEntity> {
}
