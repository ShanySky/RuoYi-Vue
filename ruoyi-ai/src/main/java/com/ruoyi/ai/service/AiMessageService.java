package com.ruoyi.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.common.exception.ServiceException;

@Service
public class AiMessageService
{
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    public AiMessageService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper)
    {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public AiMessage append(AiMessage message)
    {
        if (message == null || message.getConversationId() == null)
        {
            throw new ServiceException("AI 消息缺少 Conversation");
        }
        if (conversationMapper.lockById(message.getConversationId()) == null)
        {
            throw new ServiceException("Conversation 不存在");
        }
        message.setSequenceNo(messageMapper.nextSequence(message.getConversationId()));
        messageMapper.insert(message);
        return message;
    }
}
