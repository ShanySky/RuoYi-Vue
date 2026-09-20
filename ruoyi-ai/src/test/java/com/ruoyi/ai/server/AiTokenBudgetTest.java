package com.ruoyi.ai.server;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.runtime.AgentRuntimeMessage;
import com.ruoyi.ai.runtime.AgentRuntimeRequest;
import com.ruoyi.ai.runtime.AgentRuntimeTool;
import com.ruoyi.ai.service.AiTokenBudget;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;

class AiTokenBudgetTest
{
    @Test
    void chineseAndActualToolDefinitionsCannotHideBehindTheOldCharacterEstimate()
    {
        String chinese = "查询本人部门的用户资料并核对岗位权限。".repeat(300);
        assertTrue(AiTokenBudget.estimate(chinese) > chinese.length() / 2);
        AiModel model = new AiModel();
        model.setContextWindowTokens(8192);
        var request = new AgentRuntimeRequest(1L, null, null,
                List.of(AgentRuntimeMessage.user("查询岗位")),
                List.of(new AgentRuntimeTool("large_contract", "真实大契约", "x".repeat(40000))));
        assertThrows(ServiceException.class, () -> AiTokenBudget.requireFits(model, request));
    }

    @Test
    void largerConfiguredWindowsStillReserveOutputAndRejectOversizedInputs()
    {
        for (int window : List.of(65536, 131072, 262144))
        {
            AiModel model = new AiModel();
            model.setContextWindowTokens(window);
            assertTrue(AiTokenBudget.reserve(model) >= AiTokenBudget.outputLimit(model));
            assertDoesNotThrow(() -> AiTokenBudget.requireFits(model,
                    new AgentRuntimeRequest(1L, null, null, List.of(AgentRuntimeMessage.user("查询岗位")), List.of())));
            assertThrows(ServiceException.class, () -> AiTokenBudget.requireFits(model,
                    new AgentRuntimeRequest(1L, null, null,
                            List.of(AgentRuntimeMessage.user("x".repeat(window * 4))), List.of())));
        }
    }
}
