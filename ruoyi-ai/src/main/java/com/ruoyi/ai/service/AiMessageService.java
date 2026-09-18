package com.ruoyi.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.common.exception.ServiceException;

@Service
public class AiMessageService
{
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiRunMapper runMapper;
    private final AiPendingToolCallMapper pendingMapper;

    public AiMessageService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
            AiRunMapper runMapper, AiPendingToolCallMapper pendingMapper)
    {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.pendingMapper = pendingMapper;
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
        AiRun run = runMapper.selectById(message.getRunId());
        if (run == null || !"RUNNING".equals(run.getStatus()) || runMapper.complete(run.getRunId()) != 1)
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
        AiRun run = runMapper.selectById(message.getRunId());
        if (run == null || !"RUNNING".equals(run.getStatus())
                || runMapper.updateActiveState(run.getRunId(), "WAITING_TOOL") != 1)
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
        AiRun run = runMapper.selectById(message.getRunId());
        if (run == null || !"WAITING_TOOL".equals(run.getStatus()))
        {
            return false;
        }
        if (pendingMapper.resolve(pendingId) != 1
                || runMapper.updateActiveState(run.getRunId(), "RUNNING") != 1)
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
