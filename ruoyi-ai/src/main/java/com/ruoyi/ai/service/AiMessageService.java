package com.ruoyi.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.common.exception.ServiceException;

@Service
public class AiMessageService
{
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiPendingToolCallMapper pendingMapper;
    private final RunLifecycleService lifecycle;

    public AiMessageService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
            AiPendingToolCallMapper pendingMapper, RunLifecycleService lifecycle)
    {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.pendingMapper = pendingMapper;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public AiMessage append(AiMessage message)
    {
        requireConversation(message);
        appendLocked(message);
        return message;
    }

    @Transactional
    public boolean completeWithAssistant(AiMessage message)
    {
        requireConversation(message);
        if (!lifecycle.tryComplete(message.getRunId()))
        {
            return false;
        }
        appendLocked(message);
        return true;
    }

    @Transactional
    public boolean persistToolCall(AiMessage message, AiPendingToolCall pending)
    {
        requireConversation(message);
        if (!lifecycle.tryWaitingTool(message.getRunId()))
        {
            return false;
        }
        appendLocked(message);
        pendingMapper.insert(pending);
        return true;
    }

    @Transactional
    public boolean resolveToolResult(AiMessage message, Long pendingId)
    {
        requireConversation(message);
        if (pendingMapper.resolve(pendingId) != 1 || !lifecycle.tryResumeTool(message.getRunId()))
        {
            return false;
        }
        appendLocked(message);
        return true;
    }

    private void requireConversation(AiMessage message)
    {
        if (message == null || message.getConversationId() == null)
        {
            throw new ServiceException("AI 消息缺少 Conversation");
        }
        if (conversationMapper.lockById(message.getConversationId()) == null)
        {
            throw new ServiceException("Conversation 不存在");
        }
    }

    private void appendLocked(AiMessage message)
    {
        message.setSequenceNo(messageMapper.nextSequence(message.getConversationId()));
        messageMapper.insert(message);
    }
}
