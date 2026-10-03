package com.vidsage.provider.llm;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 模型探针：验证 spring.ai.model.chat 开关切到的供应商是否真实在应答。
 *
 * <p>走的是管线实际使用的那颗 ChatClient（subtitleCleanupChatClient）。模型身份取自
 * 响应元数据（服务端回显的 model 字段），不猜、不打印配置——服务端说自己是谁就是谁。
 *
 * <p>测试 classpath 的 application.yml 会遮蔽主配置且不带真实密钥，所以这里显式激活
 * local profile（真 key），供应商由 PROBE_PROVIDER 指定（缺省 openai = mimo）。
 *
 * <p><b>会真实调用付费 API</b>，所以用环境变量 MODEL_PROBE=true 门禁，
 * 常规 mvn test 不会执行。用法：
 * {@code MODEL_PROBE=true PROBE_PROVIDER=openai mvn -f backend/pom.xml test -Dtest=ModelProbeTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=local",
                "spring.ai.model.chat=${PROBE_PROVIDER:openai}"
        })
@EnabledIfEnvironmentVariable(named = "MODEL_PROBE", matches = "true")
class ModelProbeTest {

    @Test
    void probeWhichModelAnswers(@Qualifier("subtitleCleanupChatClient") ChatClient client) {
        ChatResponse response = client.prompt()
                .user("请只回复两个字：收到")
                .call()
                .chatResponse();

        assertNotNull(response, "应答为空");
        // 应答方是谁以服务端元数据为准；有些兼容端点会在这里回显真实型号
        System.out.println("PROBE>> model = " + response.getMetadata().getModel());
        System.out.println("PROBE>> text  = " + response.getResult().getOutput().getText());
    }
}
