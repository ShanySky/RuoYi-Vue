package com.ruoyi.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.dto.AiFrontendToolDefinition;
import com.ruoyi.common.exception.ServiceException;

class AiCapabilityProtocolValidatorTest
{
    private final AiCapabilityProtocolValidator validator = new AiCapabilityProtocolValidator();

    @Test
    void allowsOrdinaryChatAndNavigationWithoutSemanticPage()
    {
        AiChatTurnRequest chat = new AiChatTurnRequest();
        chat.setRoute("/index");
        chat.setFrontendTools(List.of());
        assertDoesNotThrow(() -> validator.validateRequest(chat));

        AiFrontendToolDefinition navigate = new AiFrontendToolDefinition();
        navigate.setName("app_navigate");
        chat.setFrontendTools(List.of(navigate));
        assertDoesNotThrow(() -> validator.validateRequest(chat));
    }

    @Test
    void requiresCompleteV1RuntimeWhenPageToolIsOffered()
    {
        AiChatTurnRequest request = new AiChatTurnRequest();
        AiFrontendToolDefinition pageTool = new AiFrontendToolDefinition();
        pageTool.setName("page_system_user_search");
        request.setFrontendTools(List.of(pageTool));

        assertThrows(ServiceException.class, () -> validator.validateRequest(request));

        request.setCapabilityProtocol(AiCapabilityProtocolValidator.PROTOCOL_V1);
        request.setPageId("system.user");
        request.setRoute("/system/user");
        request.setPageInstanceId("system.user:one");
        request.setPageVersion(1L);
        assertDoesNotThrow(() -> validator.validateRequest(request));
    }

    @Test
    void rejectsInvalidProtocolAndVersion()
    {
        AiChatTurnRequest request = validRequest();
        request.setCapabilityProtocol("ruoyi-semantic-page-v2");
        assertThrows(ServiceException.class, () -> validator.validateRequest(request));

        request.setCapabilityProtocol(AiCapabilityProtocolValidator.PROTOCOL_V1);
        request.setPageVersion(0L);
        assertThrows(ServiceException.class, () -> validator.validateRequest(request));
    }

    @Test
    void continuationMustMatchPendingSemanticPageSnapshot()
    {
        AiChatTurnRequest request = validRequest();
        AiPendingToolCall pending = pendingFrom(request);
        assertDoesNotThrow(() -> validator.validateContinuation(request, pending, true));

        request.setPageId("system.role");
        assertThrows(ServiceException.class, () -> validator.validateContinuation(request, pending, true));

        request = validRequest();
        pending = pendingFrom(request);
        request.setPageInstanceId("system.user:two");
        AiChatTurnRequest staleInstance = request;
        AiPendingToolCall saved = pending;
        assertThrows(ServiceException.class, () -> validator.validateContinuation(staleInstance, saved, true));
    }

    @Test
    void navigationContinuationDoesNotRequireSourcePageIdentity()
    {
        AiChatTurnRequest request = new AiChatTurnRequest();
        request.setRoute("/system/user");
        AiPendingToolCall pending = new AiPendingToolCall();
        pending.setToolName("app_navigate");
        assertDoesNotThrow(() -> validator.validateContinuation(request, pending, false));
    }

    private AiChatTurnRequest validRequest()
    {
        AiChatTurnRequest request = new AiChatTurnRequest();
        request.setCapabilityProtocol(AiCapabilityProtocolValidator.PROTOCOL_V1);
        request.setPageId("system.user");
        request.setRoute("/system/user");
        request.setPageInstanceId("system.user:one");
        request.setPageVersion(1L);
        return request;
    }

    private AiPendingToolCall pendingFrom(AiChatTurnRequest request)
    {
        AiPendingToolCall pending = new AiPendingToolCall();
        pending.setCapabilityProtocol(request.getCapabilityProtocol());
        pending.setPageId(request.getPageId());
        pending.setRoute(request.getRoute());
        pending.setPageInstanceId(request.getPageInstanceId());
        pending.setPageVersion(request.getPageVersion());
        return pending;
    }
}
