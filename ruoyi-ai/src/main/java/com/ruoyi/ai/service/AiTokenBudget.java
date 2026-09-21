package com.ruoyi.ai.service;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.runtime.AgentRuntimeRequest;
import com.ruoyi.common.exception.ServiceException;

/** 复用现成分词估算，保留输出余量；不把未知提供方容量猜成模型能力。 */
public final class AiTokenBudget
{
    private static final JTokkitTokenCountEstimator TOKENS = new JTokkitTokenCountEstimator();
    private static final ObjectMapper JSON = new ObjectMapper();

    private AiTokenBudget() { }

    public static int estimate(String text)
    {
        if (text == null || text.isEmpty()) return 0;
        // 旧字符估算作为下限；补足中文、结构化参数及混合文本的明显低估。
        return Math.max((text.length() + 3) / 4, TOKENS.estimate(text));
    }

    public static int window(AiModel model)
    {
        return model.getContextWindowTokens() == null ? 65536 : model.getContextWindowTokens();
    }

    public static int reserve(AiModel model)
    {
        return Math.max(1024, Math.min(8192, window(model) / 8));
    }

    public static int outputLimit(AiModel model)
    {
        return reserve(model) / 2;
    }

    public static void requireFits(AiModel model, AgentRuntimeRequest request)
    {
        try
        {
            int input = estimate(JSON.writeValueAsString(request.messages()))
                    + estimate(JSON.writeValueAsString(request.tools())) + 512;
            if ((long) input + reserve(model) > window(model))
                throw new ServiceException("本次模型输入超过工作预算，请缩小任务或先整理会话上下文");
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法核算模型输入预算"); }
    }
}
