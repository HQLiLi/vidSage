package com.vidsage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文加载冒烟：验证全量自动装配（含 MyBatis-Plus 数据源）可启动。
 *
 * <p><b>显式激活 {@code local} profile</b>：测试类路径的 application.yml 遮蔽了主配置、且不带
 * {@code spring.profiles.default}，不激活本 profile 则 application-local.yml（数据源所在）不加载，
 * 全量上下文会以「Failed to determine a suitable driver class」失败。本测试因此依赖本机
 * MySQL（vidsage 库）可达——它存在的意义就是验证真库连通，其余切片/单测不受影响。
 */
@SpringBootTest
@ActiveProfiles("local")
class VidSageApplicationTests {

    @Test
    void contextLoads() {
    }
}
