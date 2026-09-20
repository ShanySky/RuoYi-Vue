package com.ruoyi.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiConversation;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.dto.AiToolResultRequest;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.protocol.AiCapabilityProtocolValidator;
import com.ruoyi.ai.runtime.AgentRuntimeResult;
import com.ruoyi.ai.tool.AiFrontendToolPolicy;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;
import com.ruoyi.common.exception.ServiceException;

class AiToolCallCoordinatorTest
{
    @Test
    void multipleServerActionsOnlyPersistTheFirstAndNeverQueueAnUnconfirmedWrite()
    {
        AiMessageService messageService = mock(AiMessageService.class);
        var server = mock(com.ruoyi.ai.server.AiServerToolService.class);
        when(server.supports(anyString())).thenReturn(true);
        when(messageService.persistToolCall(any(), any())).thenReturn(true);
        AiToolCallCoordinator coordinator = new AiToolCallCoordinator(mock(AiPendingToolCallMapper.class),
                mock(AiMessageMapper.class), messageService, mock(AiFrontendToolPolicy.class),
                mock(AiRunService.class), mock(AiPageConfigService.class), new AiCapabilityProtocolValidator(),
                new ObjectMapper(), server);
        AiRun run = new AiRun();
        run.setRunId(33L);
        var output = new AgentRuntimeResult(5L, "mock", "两个动作", List.of(
                new AgentRuntimeResult.ToolCall("one", "server_api_search", "{}"),
                new AgentRuntimeResult.ToolCall("two", "api_write", "{}")), null, "tool_calls");
        coordinator.prepareToolCall(conversation(), request("system.user:one", 1L), run, 5L, "mock", null,
                List.of(new ApprovedTool("server_api_search", "搜索", Map.of(), "READ", null)), output);
        ArgumentCaptor<AiPendingToolCall> pending = ArgumentCaptor.forClass(AiPendingToolCall.class);
        ArgumentCaptor<com.ruoyi.ai.domain.AiMessage> message = ArgumentCaptor.forClass(com.ruoyi.ai.domain.AiMessage.class);
        verify(messageService, times(1)).persistToolCall(message.capture(), pending.capture());
        assertEquals("server_api_search", pending.getValue().getToolName());
        assertEquals("READ", pending.getValue().getRiskLevel());
        assertTrue(message.getValue().getContent().contains("其他请求尚未执行"));
        verify(server, times(1)).prepare(pending.getValue());
        verify(server, never()).execute(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void pendingToolPersistsTrustedProtocolSnapshot()
    {
        AiPendingToolCallMapper pending = mock(AiPendingToolCallMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiMessageService messageService = mock(AiMessageService.class);
        AiFrontendToolPolicy policy = mock(AiFrontendToolPolicy.class);
        AiRunService runs = mock(AiRunService.class);
        AiPageConfigService pages = mock(AiPageConfigService.class);
        when(messageService.persistToolCall(any(), any())).thenReturn(true);

        AiToolCallCoordinator coordinator = coordinator(pending, messages, messageService, policy, runs, pages);
        AiConversation conversation = conversation();
        AiRun run = new AiRun();
        run.setRunId(33L);
        AiChatTurnRequest request = request("system.user:one", 4L);
        ApprovedTool approved = new ApprovedTool("page_system_user_search", "search", Map.of(), "READ", "system:user:list");
        AgentRuntimeResult output = new AgentRuntimeResult(5L, "mock", null,
                List.of(new AgentRuntimeResult.ToolCall("provider-call", "page_system_user_search", "{}")),
                null, "tool_calls");

        AiToolCallCoordinator.PreparedToolCall prepared = coordinator.prepareToolCall(
                conversation, request, run, 5L, "mock", "high", List.of(approved), output);
        assertNotNull(prepared);

        ArgumentCaptor<AiPendingToolCall> capture = ArgumentCaptor.forClass(AiPendingToolCall.class);
        verify(messageService).persistToolCall(any(), capture.capture());
        AiPendingToolCall saved = capture.getValue();
        assertEquals(AiCapabilityProtocolValidator.PROTOCOL_V1, saved.getCapabilityProtocol());
        assertEquals("system.user", saved.getPageId());
        assertEquals("/system/user", saved.getRoute());
        assertEquals("system.user:one", saved.getPageInstanceId());
        assertEquals(4L, saved.getPageVersion());
    }

    @Test
    void stalePageResultIsRejectedBeforeToolResultPersistence()
    {
        AiPendingToolCallMapper pending = mock(AiPendingToolCallMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiMessageService messageService = mock(AiMessageService.class);
        AiFrontendToolPolicy policy = mock(AiFrontendToolPolicy.class);
        AiRunService runs = mock(AiRunService.class);
        AiPageConfigService pages = mock(AiPageConfigService.class);

        AiPendingToolCall saved = new AiPendingToolCall();
        saved.setPendingId(2L);
        saved.setCallId("call");
        saved.setConversationId(1L);
        saved.setUserId(1L);
        saved.setRunId(33L);
        saved.setStatus("PENDING");
        saved.setToolName("page_system_user_search");
        saved.setCapabilityProtocol(AiCapabilityProtocolValidator.PROTOCOL_V1);
        saved.setPageId("system.user");
        saved.setRoute("/system/user");
        saved.setPageInstanceId("system.user:old");
        saved.setPageVersion(4L);
        when(pending.selectByCall(1L, "call")).thenReturn(saved);

        AiRun run = new AiRun();
        run.setRunId(33L);
        run.setUserId(1L);
        run.setStatus("WAITING_TOOL");
        when(runs.requireOwned(33L, 1L)).thenReturn(run);

        AiToolCallCoordinator coordinator = coordinator(pending, messages, messageService, policy, runs, pages);
        AiChatTurnRequest request = request("system.user:new", 4L);
        AiToolResultRequest result = new AiToolResultRequest();
        result.setCallId("call");
        result.setSuccess(true);
        request.setToolResult(result);

        assertThrows(ServiceException.class, () -> coordinator.acceptToolResult(conversation(), 1L, request));
        verify(messageService, never()).resolveToolResult(any(), anyLong());
    }

    @Test
    void missingPendingCallRejectsDuplicateOrLateResult()
    {
        AiPendingToolCallMapper pending = mock(AiPendingToolCallMapper.class);
        AiToolCallCoordinator coordinator = coordinator(pending, mock(AiMessageMapper.class),
                mock(AiMessageService.class), mock(AiFrontendToolPolicy.class),
                mock(AiRunService.class), mock(AiPageConfigService.class));
        AiChatTurnRequest request = request("system.user:one", 4L);
        AiToolResultRequest result = new AiToolResultRequest();
        result.setCallId("already-resolved");
        request.setToolResult(result);

        assertThrows(ServiceException.class, () -> coordinator.acceptToolResult(conversation(), 1L, request));
    }

    private AiToolCallCoordinator coordinator(AiPendingToolCallMapper pending, AiMessageMapper messages,
            AiMessageService messageService, AiFrontendToolPolicy policy, AiRunService runs, AiPageConfigService pages)
    {
        return new AiToolCallCoordinator(pending, messages, messageService, policy, runs, pages,
                new AiCapabilityProtocolValidator(), new ObjectMapper(), mock(com.ruoyi.ai.server.AiServerToolService.class));
    }

    private AiConversation conversation()
    {
        AiConversation conversation = new AiConversation();
        conversation.setConversationId(1L);
        conversation.setUserId(1L);
        return conversation;
    }

    private AiChatTurnRequest request(String instanceId, Long version)
    {
        AiChatTurnRequest request = new AiChatTurnRequest();
        request.setCapabilityProtocol(AiCapabilityProtocolValidator.PROTOCOL_V1);
        request.setPageId("system.user");
        request.setRoute("/system/user");
        request.setPageInstanceId(instanceId);
        request.setPageVersion(version);
        return request;
    }
}
